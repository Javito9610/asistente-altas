package es.amagna.asistente.cerebro;

import es.amagna.asistente.Json;
import es.amagna.asistente.manos.FrenoDeEmergencia;
import es.amagna.asistente.manos.Manos;
import es.amagna.asistente.manos.Controles;
import es.amagna.asistente.manos.Ventanas;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * El bucle del agente:
 *   1. se le manda a Claude la tarea (y sus instrucciones de especialista);
 *   2. Claude contesta con acciones de la herramienta de ordenador (captura, clic, escribir, tecla…);
 *   3. las manos las ejecutan al pie de la letra y se le devuelve el resultado (la captura, si la pidió);
 *   4. se repite hasta que Claude termina (sin más acciones) o se llega al tope de pasos.
 *
 * Herramienta: «computer_toolset_20260801» (la actual de la API de Claude; las coordenadas son las de la captura).
 * Para gastar poco: las instrucciones van en caché y, cuando hay más de 6 capturas, las viejas se sustituyen por un
 * texto («captura antigua») y se dejan las 3 últimas. Al cambiar así el principio de la conversación, los bloques de
 * pensamiento de Claude de después ya no valen (la API los ata a lo que había antes): se quitan, como pide la API.
 *
 * Además de la herramienta de ordenador, tres herramientas propias para las ventanas (abrir_programa, activar_ventana,
 * ventana_activa): el teclado tiene que ir a la ventana que toca.
 *
 * Para no gastar en balde: si la pantalla sale idéntica 3 capturas seguidas, se le avisa a Claude de que lo que hace no
 * surte efecto; a la 5.ª, se para solo. Y hay un tope de gasto en tokens por tarea.
 *
 * El resultado final: Claude acaba con «TERMINADO: …» o «PARADO: …» (lo piden las instrucciones).
 */
public final class Agente {
    public static final String HERRAMIENTA = "computer_toolset_20260801";
    /** Se recortan las capturas viejas por tandas: al pasar de MAX se dejan las últimas MIN. Así el principio de la
     *  conversación cambia pocas veces (la caché aguanta y el pensamiento de Claude sigue siendo válido). */
    private static final int CAPTURAS_MAX = 6;
    private static final int CAPTURAS_MIN = 3;

    public record Resultado(boolean terminado, String mensaje, int pasos, long tokensEntrada, long tokensSalida, long tokensCache) {
    }

    private final Cerebro cerebro;
    private final Manos manos;
    private final String modelo;
    private final String instrucciones;
    private final int pasosMax;
    private final Consumer<String> registro;
    private Expediente expediente;
    private final Ventanas ventanas;
    private Controles controles;
    private final long topeTokens;

    public static final long TOPE_TOKENS_POR_DEFECTO = 200_000;
    static final int AVISO_SIN_CAMBIOS = 3;
    static final int PARADA_SIN_CAMBIOS = 5;

    public Agente(Cerebro cerebro, Manos manos, String modelo, String instrucciones, int pasosMax, Consumer<String> registro) {
        this(cerebro, manos, null, modelo, instrucciones, pasosMax, TOPE_TOKENS_POR_DEFECTO, registro);
    }

    /**
     * @param topeTokens  gasto máximo por tarea: tokens de entrada + salida + una décima parte de los leídos de caché
     *                    (que se cobran mucho más baratos). Al pasarlo, se para.
     */
    /** Los controles por dentro (UI Automation). Sin esto, el agente solo tiene los ojos y las manos. */
    public Agente conControles(Controles c) {
        this.controles = c;
        return this;
    }

    public Agente(Cerebro cerebro, Manos manos, Ventanas ventanas, String modelo, String instrucciones, int pasosMax, long topeTokens,
            Consumer<String> registro) {
        this.cerebro = cerebro;
        this.manos = manos;
        this.ventanas = ventanas;
        this.modelo = modelo;
        this.instrucciones = instrucciones;
        this.pasosMax = pasosMax;
        this.topeTokens = topeTokens;
        this.registro = registro;
    }

    private List<Object> herramientas() {
        List<Object> h = new ArrayList<>();
        if (ventanas != null) {
            h.add(Json.obj("name", "abrir_programa", "description",
                    "Abre un programa de Windows directamente (más fiable que buscarlo en el menú Inicio). Ejemplo: programa «notepad.exe».",
                    "input_schema", Json.obj("type", "object", "properties", Json.obj(
                            "programa", Json.obj("type", "string", "description", "Ruta o nombre del ejecutable"),
                            "argumentos", Json.obj("type", "array", "items", Json.obj("type", "string"))), "required", List.of("programa"))));
            h.add(Json.obj("name", "activar_ventana", "description",
                    "Pone delante (con el foco del teclado) la ventana cuyo título contiene el texto dado. Devuelve si lo ha conseguido.",
                    "input_schema", Json.obj("type", "object", "properties", Json.obj(
                            "titulo", Json.obj("type", "string", "description", "Parte del título de la ventana")), "required", List.of("titulo"))));
            h.add(Json.obj("name", "leer_campo", "description",
                    "Lee de Windows (no de la imagen) el control que tiene el foco: su nombre, tipo, ventana y su texto EXACTO. "
                    + "No toca nada. Si das «esperado», lo compara carácter a carácter y dice IGUAL o DISTINTO y dónde. Úsalo "
                    + "siempre para comprobar lo que has escrito: es más fiable que mirar la captura.",
                    "input_schema", Json.obj("type", "object", "properties", Json.obj(
                            "esperado", Json.obj("type", "string", "description", "Texto que debería haber, exacto")))));
            h.add(Json.obj("name", "ventanas_abiertas", "description",
                    "Lista todas las ventanas abiertas, también las que están detrás o minimizadas y los diálogos que tienen "
                    + "dentro (Abrir, Guardar, avisos…). La captura solo enseña lo que está encima: usa esto para saber todo lo que hay.",
                    "input_schema", Json.obj("type", "object", "properties", Json.obj())));
            h.add(Json.obj("name", "cancelar_dialogo", "description",
                    "Cancela un diálogo abierto (como pulsar Cancelar o su aspa: no guarda ni confirma nada). Solo para "
                    + "diálogos que has abierto tú o que bloquean la ventana en la que trabajas. Devuelve cuál ha cerrado.",
                    "input_schema", Json.obj("type", "object", "properties", Json.obj(
                            "titulo", Json.obj("type", "string", "description", "Parte del título del diálogo")), "required", List.of("titulo"))));
            h.add(Json.obj("name", "cancelar_bloqueos", "description",
                    "Cancela lo que esté bloqueando una ventana marcada BLOQUEADA, aunque no se sepa el título del diálogo. "
                    + "Úsalo cuando una ventana no responda a los atajos. Cancelar no guarda ni confirma nada.",
                    "input_schema", Json.obj("type", "object", "properties", Json.obj())));
            h.add(Json.obj("name", "cerrar_ventana", "description",
                    "Cierra una ventana que HAYAS ABIERTO TÚ en esta tarea (como pulsar su aspa). Si la ventana no la has "
                    + "abierto tú, el programa no te deja y te lo dice: nunca le cierras a nadie su trabajo. Deja cerrado "
                    + "al terminar todo lo que hayas abierto.",
                    "input_schema", Json.obj("type", "object", "properties", Json.obj(
                            "titulo", Json.obj("type", "string", "description", "Parte del título de la ventana")), "required", List.of("titulo"))));
            h.add(Json.obj("name", "ventana_activa", "description",
                    "Dice el título de la ventana que tiene el foco del teclado (adonde irá lo que escribas o las teclas que pulses).",
                    "input_schema", Json.obj("type", "object", "properties", Json.obj())));
        }
        if (controles != null) {
            Map<String, Object> ventana = Json.obj("type", "string", "description", "Parte del título de la ventana");
            Map<String, Object> control = Json.obj("type", "string",
                    "description", "Cómo se llama el control. Cuatro formas, de menos a más precisa: «Guardar» (por su nombre); "
                    + "«boton:Guardar» (tipo + nombre: boton, campo, desplegable, casilla, opcion-de-menu, lista, fila…); «#txtNif» "
                    + "(por su identificador interno, el que nunca cambia: el más seguro); «campo junto a NIF» (el cuadro que hay al "
                    + "lado de la etiqueta «NIF», para formularios cuyos cuadros no tienen nombre). No hacen falta acentos ni mayúsculas. "
                    + "Si dos controles encajan igual de bien no se toca ninguno y se te dicen cuáles son: afina entonces el nombre.");
            h.add(Json.obj("name", "ver_controles", "description",
                    "Lista los controles de una ventana POR DENTRO (nombre, tipo, valor exacto y si están activos), como los lee "
                    + "un lector de pantalla. Es mucho más fiable que mirar la captura: úsalo antes de rellenar nada.",
                    "input_schema", Json.obj("type", "object", "properties", Json.obj(
                            "ventana", ventana, "profundidad", Json.obj("type", "integer", "description", "Niveles a mirar (3 por defecto)")),
                            "required", List.of("ventana"))));
            h.add(Json.obj("name", "leer_control", "description",
                    "Lee el valor EXACTO de un control concreto de una ventana, sin tocarlo.",
                    "input_schema", Json.obj("type", "object", "properties", Json.obj("ventana", ventana, "control", control),
                            "required", List.of("ventana", "control"))));
            h.add(Json.obj("name", "escribir_en", "description",
                    "Escribe un texto EXACTO en un control, sin teclear (no se pierde ningún carácter) y lo relee para "
                    + "comprobarlo: contesta IGUAL o DISTINTO. Es la forma buena de rellenar un formulario.",
                    "input_schema", Json.obj("type", "object", "properties", Json.obj("ventana", ventana, "control", control,
                            "texto", Json.obj("type", "string", "description", "El valor exacto, tal cual")),
                            "required", List.of("ventana", "control", "texto"))));
            h.add(Json.obj("name", "esperar_a", "description",
                    "Espera MIRANDO a que ocurra algo de verdad, en vez de esperar un rato a ciegas: que aparezca una ventana o un "
                    + "control, que desaparezca, que se active un botón, que un campo valga algo, o que la pantalla deje de cambiar "
                    + "(el programa ha terminado de cargar). Úsalo SIEMPRE en vez de «wait»: así da igual que el ordenador vaya lento. "
                    + "Si se acaba el tiempo te dice qué había en ese momento.",
                    "input_schema", Json.obj("type", "object", "properties", Json.obj(
                            "que", Json.obj("type", "string", "enum", List.of("aparezca", "desaparezca", "se active", "valga", "pantalla quieta"),
                                    "description", "Qué tiene que pasar"),
                            "ventana", ventana, "control", control,
                            "valor", Json.obj("type", "string", "description", "Con «valga»: el valor exacto que tiene que tener"),
                            "segundos", Json.obj("type", "number", "description", "Cuánto esperar como mucho (15 por defecto, 120 como máximo)")),
                            "required", List.of("que"))));
            h.add(Json.obj("name", "pulsar_control", "description",
                    "Pulsa un botón, una opción de menú o una fila de una lista por su nombre, sin coordenadas: no hay "
                    + "forma de fallar el clic. Si el control no se deja, dice dónde está para pulsarlo con el ratón.",
                    "input_schema", Json.obj("type", "object", "properties", Json.obj("ventana", ventana, "control", control),
                            "required", List.of("ventana", "control"))));
        }
        // La herramienta de ordenador va la última: el cache_control cubre todas las herramientas.
        h.add(Json.obj("type", HERRAMIENTA, "configs", Json.obj("zoom", Json.obj("enabled", true)), "cache_control", Json.obj("type", "ephemeral")));
        return h;
    }

    @SuppressWarnings("unchecked")
    /** Deja constancia de todo lo que haga en una carpeta propia (pasos, capturas e informe). */
    public Agente conExpediente(boolean si) {
        this.expediente = si ? Expediente.abrir("(pendiente)") : null;
        return this;
    }

    public Expediente expediente() {
        return expediente;
    }

    public Resultado ejecutar(String tarea) {
        if (expediente != null) {
            expediente = Expediente.abrir(tarea);
        }
        List<Object> mensajes = new ArrayList<>();
        mensajes.add(Json.obj("role", "user", "content", tarea));
        long ent = 0, sal = 0, cache = 0;
        String huellaAnterior = null;
        int iguales = 1;
        for (int paso = 1; paso <= pasosMax; paso++) {
            int cambiado = recortarCapturas(mensajes);
            if (cambiado >= 0) {
                quitarPensamientos(mensajes, cambiado);
            }
            Map<String, Object> peticion = Json.obj(
                    "model", modelo,
                    "max_tokens", 4096,
                    "system", List.of(Json.obj("type", "text", "text", instrucciones, "cache_control", Json.obj("type", "ephemeral"))),
                    "tools", herramientas(),
                    "messages", mensajes);
            Map<String, Object> r = cerebro.pensar(peticion);
            Map<String, Object> uso = (Map<String, Object>) r.getOrDefault("usage", Map.of());
            ent += num(uso.get("input_tokens"));
            sal += num(uso.get("output_tokens"));
            cache += num(uso.get("cache_read_input_tokens"));
            long gasto = ent + sal + cache / 10;
            List<Object> contenido = (List<Object>) r.getOrDefault("content", List.of());
            mensajes.add(Json.obj("role", "assistant", "content", contenido));

            List<Object> resultados = new ArrayList<>();
            boolean fallo = false;
            StringBuilder texto = new StringBuilder();
            for (Object o : contenido) {
                Map<String, Object> b = (Map<String, Object>) o;
                if ("text".equals(b.get("type"))) {
                    texto.append(b.get("text"));
                    registro.accept("  Claude: " + String.valueOf(b.get("text")).strip());
                    anotar(e -> e.dice(String.valueOf(b.get("text")).strip()));
                } else if ("tool_use".equals(b.get("type"))) {
                    String id = (String) b.get("id");
                    String accion = (String) b.get("name");
                    boolean ordenador = "computer".equals(b.get("toolset_name"));
                    Map<String, Object> in = (Map<String, Object>) b.getOrDefault("input", Map.of());
                    if (fallo) {
                        resultados.add(resultado(id, List.of(textoBloque("Not executed: an earlier computer action in this turn failed.")), true, ordenador));
                        continue;
                    }
                    registro.accept("  paso " + paso + " · " + accion + (in.isEmpty() ? "" : " " + resumen(in)));
                    try {
                        List<Object> salida = ordenador ? ejecutarAccion(accion, in) : ejecutarPropia(accion, in);
                        final int nPaso = paso;
                        final List<Object> vista = salida;
                        anotar(x -> {
                            x.paso(nPaso, accion, in.isEmpty() ? "" : resumen(in), texto(vista));
                            byte[] png = pngDe(vista);
                            if (png != null) {
                                x.captura(png);
                            }
                        });
                        if (ordenador && ("screenshot".equals(accion))) {
                            String huella = huella(salida);
                            iguales = huella.equals(huellaAnterior) ? iguales + 1 : 1;
                            huellaAnterior = huella;
                            if (iguales >= PARADA_SIN_CAMBIOS) {
                                registro.accept("    sin avances: " + iguales + " capturas idénticas seguidas");
                                                return cerrar(new Resultado(false, "PARADO: sin avances. La pantalla ha salido idéntica en " + iguales
                                        + " capturas seguidas: lo que se intentaba no surtía efecto. Se ha parado para no gastar en balde.",
                                        paso, ent, sal, cache));
                            }
                            if (iguales >= AVISO_SIN_CAMBIOS) {
                                salida = new ArrayList<>(salida);
                                salida.add(textoBloque("AVISO del programa: la pantalla es idéntica en las " + iguales + " últimas capturas: "
                                        + "lo que estás haciendo no surte efecto. No lo repitas igual: comprueba con ventana_activa adónde va el "
                                        + "teclado, cambia de forma de hacerlo o termina con PARADO."));
                                registro.accept("    aviso: " + iguales + " capturas idénticas seguidas");
                            }
                        }
                        resultados.add(resultado(id, salida, false, ordenador));
                    } catch (FrenoDeEmergencia e) {
                        throw e;
                    } catch (RuntimeException e) {
                        fallo = true;
                        registro.accept("    error: " + e.getMessage());
                        anotar(x -> x.error(accion + ": " + e.getMessage()));
                        resultados.add(resultado(id, List.of(textoBloque("Error: " + e.getMessage())), true, ordenador));
                    }
                }
            }
            if (resultados.isEmpty()) { // sin acciones: ha terminado
                String t = texto.toString().strip();
                return cerrar(new Resultado(t.startsWith("TERMINADO"), t.isEmpty() ? "(sin mensaje final)" : t, paso, ent, sal, cache));
            }
            if (gasto > topeTokens) {
                return cerrar(new Resultado(false, "PARADO: se ha llegado al tope de gasto de la tarea (" + gasto + " de " + topeTokens
                        + " tokens) sin terminar.", paso, ent, sal, cache));
            }
            mensajes.add(Json.obj("role", "user", "content", resultados));
        }
        return new Resultado(false, "PARADO: se ha llegado al tope de " + pasosMax + " pasos sin terminar.", pasosMax, ent, sal, cache);
    }

    /** Ejecuta una acción de la herramienta de ordenador y devuelve el contenido del resultado. */
    @SuppressWarnings("unchecked")
    List<Object> ejecutarAccion(String accion, Map<String, Object> in) {
        List<Object> c = in.get("coordinate") instanceof List<?> l ? (List<Object>) l : null;
        Integer x = c == null ? null : (int) num(c.get(0));
        Integer y = c == null ? null : (int) num(c.get(1));
        String mods = (String) in.get("text");
        switch (accion) {
            case "screenshot":
                return List.of(imagen(manos.captura()));
            case "zoom": {
                List<Object> r = (List<Object>) in.get("region");
                return List.of(imagen(manos.zoom((int) num(r.get(0)), (int) num(r.get(1)), (int) num(r.get(2)), (int) num(r.get(3)))));
            }
            case "left_click": manos.clic(x, y, 1, 1, mods); break;
            case "right_click": manos.clic(x, y, 3, 1, mods); break;
            case "middle_click": manos.clic(x, y, 2, 1, mods); break;
            case "double_click": manos.clic(x, y, 1, 2, mods); break;
            case "triple_click": manos.clic(x, y, 1, 3, mods); break;
            case "left_click_drag": {
                List<Object> s = (List<Object>) in.get("start_coordinate");
                manos.arrastrar((int) num(s.get(0)), (int) num(s.get(1)), x, y, mods);
                break;
            }
            case "mouse_move": manos.mover(x, y); break;
            case "left_mouse_down": manos.bajarBotonIzquierdo(); break;
            case "left_mouse_up": manos.subirBotonIzquierdo(); break;
            case "cursor_position": {
                int[] p = manos.posicion();
                return List.of(textoBloque("X=" + p[0] + ", Y=" + p[1]));
            }
            case "scroll":
                manos.rueda((String) in.get("scroll_direction"), (int) num(in.getOrDefault("scroll_amount", 3L)), x, y, mods);
                break;
            case "type": manos.escribir((String) in.get("text")); break;
            case "key": manos.tecla((String) in.get("text"), (int) num(in.getOrDefault("repeat", 1L))); break;
            case "hold_key": manos.mantenerTecla((String) in.get("text"), dbl(in.get("duration"))); break;
            case "wait": manos.esperar(dbl(in.getOrDefault("duration", 1L))); break;
            default:
                throw new IllegalArgumentException("Acción desconocida: " + accion);
        }
        return List.of(textoBloque("OK"));
    }

    /** Herramientas propias (ventanas). */
    List<Object> ejecutarPropia(String accion, Map<String, Object> in) {
        if (ventanas == null) {
            throw new IllegalArgumentException("Herramienta no disponible: " + accion);
        }
        switch (accion) {
            case "abrir_programa": {
                List<String> args = new ArrayList<>();
                if (in.get("argumentos") instanceof List<?> l) {
                    l.forEach(a -> args.add(String.valueOf(a)));
                }
                ventanas.abrir((String) in.get("programa"), args);
                return List.of(textoBloque("Abierto. Espera un momento y haz una captura para comprobarlo."));
            }
            case "activar_ventana": {
                boolean ok = ventanas.activar((String) in.get("titulo"));
                return List.of(textoBloque(ok ? "Hecho: la ventana está delante. Ventana activa: " + ventanas.activa()
                        : "No se ha encontrado ninguna ventana con ese título."));
            }
            case "cerrar_ventana":
                return List.of(textoBloque(ventanas instanceof es.amagna.asistente.manos.VentanasNativas vn
                        ? vn.cerrarSiEsNuestra((String) in.get("titulo"))
                        : "Aquí no se pueden cerrar ventanas."));
            case "ventana_activa":
                return List.of(textoBloque("Ventana activa: «" + ventanas.activa() + "»"));
            case "leer_campo": {
                Ventanas.Campo c = ventanas.campoConFoco();
                String donde = "Control con el foco: «" + c.nombre() + "» (" + c.tipo() + ") en la ventana «" + c.ventana() + "». ";
                if (c.valor() == null) {
                    return List.of(textoBloque(donde + "Este control no deja leer su texto: no se puede comprobar así."));
                }
                Object esperado = in.get("esperado");
                return List.of(textoBloque(donde + (esperado == null ? "Texto: «" + c.valor() + "»" : comparar(String.valueOf(esperado), c.valor()))));
            }
            case "ventanas_abiertas":
                return List.of(textoBloque("Ventanas abiertas:\n" + ventanas.abiertas()));
            case "ver_controles":
                return List.of(textoBloque(controles.arbol((String) in.get("ventana"), (int) num(in.getOrDefault("profundidad", 3L)))));
            case "leer_control":
                return List.of(textoBloque(controles.leer((String) in.get("ventana"), (String) in.get("control"))));
            case "escribir_en":
                return List.of(textoBloque(controles.escribir((String) in.get("ventana"), (String) in.get("control"), (String) in.get("texto"))));
            case "pulsar_control":
                return List.of(textoBloque(controles.pulsar((String) in.get("ventana"), (String) in.get("control"))));
            case "esperar_a": {
                double segundos = Math.min(120, Math.max(0.5, dbl(in.getOrDefault("segundos", 15L))));
                String que = String.valueOf(in.getOrDefault("que", "aparezca"));
                if (que.startsWith("pantalla")) {
                    return List.of(textoBloque(esperarPantallaQuieta(segundos)));
                }
                Controles.Que q = switch (que) {
                    case "desaparezca" -> Controles.Que.DESAPAREZCA;
                    case "se active" -> Controles.Que.SE_ACTIVE;
                    case "valga" -> Controles.Que.VALGA;
                    default -> Controles.Que.APAREZCA;
                };
                return List.of(textoBloque(controles.esperar((String) in.get("ventana"), (String) in.get("control"), q,
                        (String) in.get("valor"), segundos)));
            }
            case "cancelar_bloqueos": {
                List<String> l = ventanas.cancelarBloqueos();
                return List.of(textoBloque(l.isEmpty() ? "No había ninguna ventana bloqueada." : "Cancelado: " + String.join(", ", l)));
            }
            case "cancelar_dialogo": {
                String t = ventanas.cancelarDialogo((String) in.get("titulo"));
                return List.of(textoBloque(t == null ? "No hay ningún diálogo con ese título." : "Cancelado el diálogo «" + t + "»."));
            }
            default:
                throw new IllegalArgumentException("Herramienta desconocida: " + accion);
        }
    }

    private void anotar(Consumer<Expediente> que) {
        if (expediente != null) {
            try {
                que.accept(expediente);
            } catch (RuntimeException ignorado) {
                // El expediente nunca puede impedir trabajar.
            }
        }
    }

    private Resultado cerrar(Resultado r) {
        anotar(e -> e.cerrar(r.terminado(), r.mensaje(), r.pasos(), r.tokensEntrada(), r.tokensSalida(), r.tokensCache()));
        return r;
    }

    /** El texto que devolvió una herramienta (para el expediente). */
    @SuppressWarnings("unchecked")
    static String texto(List<Object> salida) {
        StringBuilder b = new StringBuilder();
        for (Object o : salida) {
            if (o instanceof Map<?, ?> m && "text".equals(m.get("type"))) {
                b.append(b.isEmpty() ? "" : "\n").append(m.get("text"));
            }
        }
        return b.toString();
    }

    /** La captura que devolvió una herramienta, si la hay (para el expediente). */
    @SuppressWarnings("unchecked")
    static byte[] pngDe(List<Object> salida) {
        for (Object o : salida) {
            if (o instanceof Map<?, ?> m && "image".equals(m.get("type")) && m.get("source") instanceof Map<?, ?> f) {
                try {
                    return Base64.getDecoder().decode(String.valueOf(f.get("data")));
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Espera a que la pantalla deje de cambiar: la forma honrada de saber que un programa ha terminado de cargar. */
    String esperarPantallaQuieta(double segundos) {
        long fin = System.currentTimeMillis() + (long) (segundos * 1000);
        byte[] antes = null;
        int iguales = 0;
        int vueltas = 0;
        while (System.currentTimeMillis() < fin) {
            try {
                Thread.sleep(500);
                byte[] ahora = manos.captura();
                vueltas++;
                iguales = antes != null && java.util.Arrays.equals(antes, ahora) ? iguales + 1 : 0;
                antes = ahora;
                if (iguales >= 2) {
                    return "La pantalla lleva más de un segundo sin cambiar: el programa ha terminado de dibujar. Sigue.";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Interrumpido.";
            } catch (Exception e) {
                return "No se ha podido mirar la pantalla: " + e.getMessage();
            }
        }
        return "Se han pasado los " + (long) segundos + " s y la pantalla sigue cambiando (" + vueltas
                + " miradas): el programa sigue trabajando o hay algo animado. Haz una captura para ver qué pasa.";
    }

    /** Comparación exacta, carácter a carácter (solo se igualan los saltos de línea de Windows y los demás). */
    public static String comparar(String esperado, String hay) {
        String e = esperado.replace("\r\n", "\n").replace('\r', '\n');
        String h = hay.replace("\r\n", "\n").replace('\r', '\n');
        if (e.equals(h)) {
            return "IGUAL: el campo tiene exactamente «" + h + "» (" + h.length() + " caracteres).";
        }
        int i = 0;
        while (i < e.length() && i < h.length() && e.charAt(i) == h.charAt(i)) {
            i++;
        }
        String fe = i < e.length() ? "«" + e.charAt(i) + "»" : "(fin)";
        String fh = i < h.length() ? "«" + h.charAt(i) + "»" : "(fin)";
        return "DISTINTO en el carácter " + (i + 1) + ": se esperaba " + fe + " y hay " + fh + ". Esperado: «" + e + "» ("
                + e.length() + "). Hay: «" + h + "» (" + h.length() + "). Corrígelo y vuelve a comprobar.";
    }

    @SuppressWarnings("unchecked")
    private static String huella(List<Object> salida) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Object o : salida) {
                Object src = ((Map<String, Object>) o).get("source");
                if (src instanceof Map<?, ?> m) {
                    md.update(String.valueOf(m.get("data")).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                }
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> resultado(String id, List<Object> contenido, boolean error, boolean ordenador) {
        Map<String, Object> m = Json.obj("type", "tool_result", "tool_use_id", id, "content", contenido);
        if (ordenador) {
            m.put("toolset_name", "computer");
        }
        if (error) {
            m.put("is_error", true);
        }
        return m;
    }

    private static Map<String, Object> imagen(byte[] png) {
        return Json.obj("type", "image", "source", Json.obj("type", "base64", "media_type", "image/png", "data", Base64.getEncoder().encodeToString(png)));
    }

    private static Map<String, Object> textoBloque(String t) {
        return Json.obj("type", "text", "text", t);
    }

    /**
     * Si hay más de CAPTURAS_MAX capturas, deja las CAPTURAS_MIN últimas y cambia las demás por un texto.
     * Devuelve el índice del primer mensaje cambiado, o -1 si no ha tocado nada.
     */
    @SuppressWarnings("unchecked")
    static int recortarCapturas(List<Object> mensajes) {
        int total = 0;
        for (Object o : mensajes) {
            total += Json.escribir(o).split("\"type\":\"image\"", -1).length - 1;
        }
        if (total <= CAPTURAS_MAX) {
            return -1;
        }
        int quitar = total - CAPTURAS_MIN;
        int primero = -1;
        for (int k = 0; k < mensajes.size() && quitar > 0; k++) {
            Map<String, Object> m = (Map<String, Object>) mensajes.get(k);
            if (!"user".equals(m.get("role")) || !(m.get("content") instanceof List<?> partes)) {
                continue;
            }
            for (Object p : partes) {
                Map<String, Object> tr = (Map<String, Object>) p;
                if (quitar == 0 || !"tool_result".equals(tr.get("type")) || !(tr.get("content") instanceof List<?> cont)) {
                    continue;
                }
                List<Object> nuevo = new ArrayList<>();
                for (Object x : cont) {
                    Map<String, Object> bloque = (Map<String, Object>) x;
                    if (quitar > 0 && "image".equals(bloque.get("type"))) {
                        nuevo.add(textoBloque("[captura antigua omitida]"));
                        quitar--;
                        if (primero < 0) {
                            primero = k;
                        }
                    } else {
                        nuevo.add(bloque);
                    }
                }
                tr.put("content", nuevo);
            }
        }
        return primero;
    }

    /** Quita los bloques de pensamiento de las respuestas de Claude posteriores al mensaje «desde» (ya no valen). */
    @SuppressWarnings("unchecked")
    static void quitarPensamientos(List<Object> mensajes, int desde) {
        for (int k = desde + 1; k < mensajes.size(); k++) {
            Map<String, Object> m = (Map<String, Object>) mensajes.get(k);
            if ("assistant".equals(m.get("role")) && m.get("content") instanceof List<?> partes) {
                List<Object> limpio = new ArrayList<>();
                for (Object p : partes) {
                    Object tipo = ((Map<String, Object>) p).get("type");
                    if (!"thinking".equals(tipo) && !"redacted_thinking".equals(tipo)) {
                        limpio.add(p);
                    }
                }
                m.put("content", limpio);
            }
        }
    }

    private static String resumen(Map<String, Object> in) {
        String s = Json.escribir(in);
        return s.length() > 140 ? s.substring(0, 140) + "…" : s;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 1.0;
    }
}
