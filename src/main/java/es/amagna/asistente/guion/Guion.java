package es.amagna.asistente.guion;

import es.amagna.asistente.Json;
import es.amagna.asistente.manos.Controles;
import es.amagna.asistente.manos.VentanasNativas;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * El guion: hacer una tarea entera de forma determinista, sin inteligencia artificial, y comprobándola.
 *
 * Esta es la parte que hace que dar un alta en MN no dependa cada noche de que el agente acierte. El guion dice qué
 * hay que hacer, el {@link Mapa} dice dónde está cada cosa, y aquí se ejecuta siempre igual:
 *
 * <ol>
 *   <li><b>Encuentra cada control probando sus apodos</b> por orden. Si el primero ya no vale y funciona otro, lo
 *       ejecuta igualmente y lo avisa en el parte: así una actualización de MN no para el trabajo, solo deja un aviso.</li>
 *   <li><b>Comprueba cada paso al hacerlo.</b> Lo escrito se relee: solo vale IGUAL.</li>
 *   <li><b>Doble lectura al final.</b> Cuando ha terminado, vuelve a leer TODO lo que escribió y lo compara con lo que
 *       tenía que poner. Como el arqueo de una caja: si algo no cuadra, no se da por bueno.</li>
 *   <li><b>No repite un trabajo ya hecho.</b> Cada guion lleva una clave (por ejemplo el alta). Si esa clave ya se
 *       completó, no se vuelve a ejecutar: es lo que impide crear dos veces el mismo cliente.</li>
 *   <li><b>Ensayo.</b> En modo ensayo recorre el guion entero y comprueba que encuentra todos los controles, pero no
 *       escribe ni pulsa nada. Sirve para decir con pruebas «esto va a funcionar» antes de tocar MN.</li>
 * </ol>
 *
 * Cuando algo falla, para y cuenta exactamente qué esperaba y qué había: entonces (y solo entonces) tiene sentido que
 * entre la inteligencia artificial a resolverlo.
 *
 * Un guion es un fichero así:
 * <pre>
 * {
 *   "clave": "alta-2026-000123",
 *   "pasos": [
 *     {"hacer": "esperar",  "control": "nif", "que": "aparezca", "segundos": 30},
 *     {"hacer": "escribir", "control": "nif", "valor": "00000000T"},
 *     {"hacer": "pulsar",   "control": "guardar", "toca": true,
 *      "luego": {"control": "aviso", "que": "aparezca", "segundos": 20}}
 *   ]
 * }
 * </pre>
 * «toca: true» marca los pasos que cambian algo de verdad: son los que el ensayo no hace.
 *
 * @package Amagna\Altas
 */
public final class Guion {
    private final Controles controles;
    private final VentanasNativas ventanas;
    private final Mapa mapa;
    private final Consumer<String> registro;

    public Guion(Controles controles, VentanasNativas ventanas, Mapa mapa, Consumer<String> registro) {
        this.controles = controles;
        this.ventanas = ventanas;
        this.mapa = mapa;
        this.registro = registro;
    }

    public record Parte(boolean bien, String resumen, List<String> lineas, int hechos, int avisos, int rescates) {
    }

    /**
     * Lo que se llama cuando el guion se atasca: la inteligencia artificial, con todo el contexto, para que vea qué
     * pasa y lo resuelva. Devuelve lo que ha hecho. Después el guion vuelve a intentar el paso y sigue por donde iba.
     */
    public interface Rescate {
        String intentar(String ventana, String control, String queSeQueria, String porQueHaFallado);
    }

    private Rescate rescate;

    /** Pone a la IA de guardia: el guion la llama solo cuando se atasca. */
    public Guion conRescate(Rescate r) {
        this.rescate = r;
        return this;
    }

    /** Dónde se apunta lo ya hecho, para no repetirlo nunca. */
    public static Path carpetaHechos() {
        String local = System.getenv("LOCALAPPDATA");
        return Path.of(local != null ? local : System.getProperty("user.home"), "AsistenteAltas", "hechos");
    }

    public Parte ejecutar(Path fichero, boolean ensayo) throws IOException {
        Map<String, Object> g = Json.objeto(Files.readString(fichero, StandardCharsets.UTF_8));
        String clave = String.valueOf(g.getOrDefault("clave", "")).trim();
        // Cada paso se cuenta EN EL MOMENTO. Antes se guardaban todos y se soltaban al final, y mientras tanto
        // parecía que el programa se había colgado. Quedarse mudo es la peor forma de trabajar.
        List<String> lineas = new ArrayList<>() {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean add(String linea) {
                registro.accept(linea);
                return super.add(linea);
            }
        };
        int avisos = 0;

        if (!ensayo && !clave.isEmpty()) {
            Path hecho = carpetaHechos().resolve(limpio(clave) + ".txt");
            if (Files.isRegularFile(hecho)) {
                String cuando = Files.readString(hecho, StandardCharsets.UTF_8).strip();
                return new Parte(false, "NO SE REPITE: «" + clave + "» ya se hizo el " + cuando
                        + ". Si de verdad hay que rehacerlo, hay que borrar " + hecho + " a mano.", lineas, 0, 0, 0);
            }
        }

        List<Object> pasos = g.get("pasos") instanceof List<?> l ? new ArrayList<>(l) : List.of();
        if (pasos.isEmpty()) {
            return new Parte(false, "El guion no tiene pasos.", lineas, 0, 0, 0);
        }

        // Antes de tocar nada: ¿están todos los secretos que el guion va a necesitar? Es mejor no empezar que
        // quedarse a medias de un alta porque falta una contraseña.
        for (Object o : pasos) {
            if (o instanceof Map<?, ?> crudo && crudo.get("valor") != null) {
                String v = String.valueOf(crudo.get("valor"));
                String cual = v.startsWith("secreto:") ? v.substring("secreto:".length())
                        : v.startsWith("usuario:") ? v.substring("usuario:".length()) : null;
                if (cual != null) {
                    boolean falta = v.startsWith("secreto:") ? !es.amagna.asistente.manos.Secreto.hay(cual)
                            : es.amagna.asistente.manos.Secreto.usuario(cual) == null;
                    if (falta) {
                        return new Parte(false, "NO SE EMPIEZA: falta " + (v.startsWith("secreto:") ? "la contraseña" : "el usuario")
                                + " de «" + cual + "». Guárdalo con «asistente secreto " + cual + "» y vuelve a lanzarlo.",
                                lineas, 0, 0, 0);
                    }
                }
            }
        }

        // Lo escrito se apunta para la doble lectura del final.
        Map<String, String[]> escrito = new LinkedHashMap<>(); // control -> {apodo que funcionó, valor}
        java.util.Set<String> yaAvisado = new java.util.HashSet<>();
        int hechos = 0;
        int rescates = 0;

        for (Object o : pasos) {
            if (!(o instanceof Map<?, ?> crudo)) {
                continue;
            }
            Map<String, Object> m = mapaDe(crudo);
            String hacer = String.valueOf(m.getOrDefault("hacer", "")).trim();
            String control = String.valueOf(m.getOrDefault("control", "")).trim();
            String valor = m.get("valor") == null ? null : String.valueOf(m.get("valor"));
            boolean toca = Boolean.TRUE.equals(m.get("toca"));
            double segundos = m.get("segundos") instanceof Number n ? n.doubleValue() : 20;

            // Hay pasos que no van contra ningún control (pulsar una tecla, esperar un rato): no hay nada que buscar.
            if (control.isBlank()) {
                String soloTecla = hacerPaso(hacer, control, "", valor, segundos, m, ensayo, lineas, escrito);
                if (soloTecla != null && soloTecla != SALTADO) {
                    lineas.add("FALLA  " + hacer + ": " + soloTecla);
                    return parte(false, "PARADO en «" + hacer + "»: " + soloTecla, lineas, hechos, avisos, rescates, clave, ensayo);
                }
                if (soloTecla == null) {
                    lineas.add("ok     " + hacer + (valor == null ? "" : " " + valor));
                }
                hechos++;
                continue;
            }
            String apodo;
            try {
                apodo = resolver(control, hacer.equals("esperar") ? segundos : 0);
            } catch (Fallo f) {
                lineas.add("FALLA  " + hacer + " " + control + ": " + f.getMessage());
                return parte(false, "PARADO en «" + hacer + " " + control + "»: " + f.getMessage(), lineas, hechos, avisos, rescates, clave, ensayo);
            }
            List<String> todos = mapa.apodos(control);
            if (!apodo.equals(todos.get(0)) && yaAvisado.add(control)) {
                avisos++;
                lineas.add("AVISO  «" + control + "»: «" + todos.get(0) + "» ya no vale; ha funcionado «" + apodo
                        + "». Conviene actualizar el mapa.");
            }

            if (ensayo && toca) {
                lineas.add("ENSAYO " + hacer + " " + control + " (" + apodo + "): encontrado, NO se toca");
                hechos++;
                continue;
            }

            // Se hace el paso. Si se atasca y hay IA de guardia, se le pasa el problema con todo el contexto,
            // y después se vuelve a intentar: la rutina la lleva el guion, lo raro lo resuelve la cabeza.
            String error = hacerPaso(hacer, control, apodo, valor, segundos, m, ensayo, lineas, escrito);
            if (error == SALTADO) {
                hechos++;
                continue;
            }
            if (error != null && rescate != null && !ensayo) {
                lineas.add("ATASCO " + hacer + " " + control + ": " + error);
                registro.accept("   … atascado en «" + hacer + " " + control + "»: entra la inteligencia artificial");
                rescates++;
                String hizo;
                try {
                    hizo = rescate.intentar(mapa.ventana(), control,
                            hacer + " «" + control + "»" + (valor == null ? "" : " con el valor exacto «" + valor + "»"), error);
                } catch (RuntimeException e) {
                    hizo = "no ha podido: " + e.getMessage();
                }
                lineas.add("IA     " + hizo);
                try {
                    apodo = resolver(control, 5); // la pantalla puede haber cambiado
                    error = hacerPaso(hacer, control, apodo, valor, segundos, m, ensayo, lineas, escrito);
                    if (error == SALTADO) {
                        error = null;
                    }
                } catch (Fallo f) {
                    error = f.getMessage();
                }
                if (error == null) {
                    lineas.add("ok     RESCATADO por la IA: " + hacer + " " + control);
                }
            }
            if (error != null) {
                lineas.add("FALLA  " + hacer + " " + control + ": " + error);
                return parte(false, "PARADO en «" + hacer + " " + control + "»: " + error, lineas, hechos, avisos, rescates, clave, ensayo);
            }
            if (valor == null || !valor.startsWith("secreto:")) {
                lineas.add("ok     " + hacer + " " + control + (valor == null ? "" : " = «" + valor + "»"));
            }
            hechos++;

            // Condición de después: no se sigue hasta que el programa reaccione de verdad.
            if (m.get("luego") instanceof Map<?, ?> crudo2) {
                Map<String, Object> l2 = mapaDe(crudo2);
                String ctrl2 = String.valueOf(l2.getOrDefault("control", control));
                double s2 = l2.get("segundos") instanceof Number n2 ? n2.doubleValue() : 20;
                Controles.Que que2 = que(l2.get("que"));
                String v2 = l2.get("valor") == null ? null : String.valueOf(l2.get("valor"));
                String apodo2;
                try {
                    apodo2 = resolver(ctrl2, s2);
                } catch (Fallo f) {
                    if (ensayo) {
                        lineas.add("ENSAYO después de " + hacer + " " + control + ": no se comprueba (no se ha tocado nada)");
                        continue;
                    }
                    lineas.add("FALLA  después de " + hacer + " " + control + ": " + f.getMessage());
                    return parte(false, "PARADO: después de «" + hacer + " " + control + "» no ha pasado lo que tenía que pasar: "
                            + f.getMessage(), lineas, hechos, avisos, rescates, clave, ensayo);
                }
                String r2 = controles.esperar(mapa.ventana(), apodo2, que2, v2, s2);
                if (!r2.startsWith("Ya está")) {
                    lineas.add("FALLA  después de " + hacer + " " + control + ": " + r2);
                    return parte(false, "PARADO: después de «" + hacer + " " + control + "»: " + r2, lineas, hechos, avisos, rescates, clave, ensayo);
                }
                lineas.add("ok     después de " + hacer + " " + control + ": " + r2);
            }
        }

        // La doble lectura: todo lo escrito se vuelve a leer y se compara. Como arquear una caja.
        if (!ensayo && !escrito.isEmpty()) {
            List<String> mal = new ArrayList<>();
            // Si la pantalla ya no está, no hay nada que releer: ESO es exactamente lo que tenía que pasar. Una
            // pantalla de entrada se cierra al entrar, y un diálogo se cierra al aceptarlo. Pedirle cuentas a una
            // ventana que ya ha hecho su trabajo y se ha ido no tiene ningún sentido.
            boolean sigueAhi = ventanas != null && ventanas.handleDe(mapa.ventana()) != null;
            if (!sigueAhi) {
                lineas.add("ok     la pantalla «" + mapa.ventana() + "» ya se ha cerrado: hizo lo que tenía que hacer");
            }
            for (Map.Entry<String, String[]> e : sigueAhi ? escrito.entrySet() : java.util.Map.<String, String[]>of().entrySet()) {
                String hay = controles.valorDe(mapa.ventana(), e.getValue()[0]);
                if (!e.getValue()[1].equals(hay)) {
                    mal.add("«" + e.getKey() + "» tenía que valer «" + e.getValue()[1] + "» y vale «" + hay + "»");
                }
            }
            if (!mal.isEmpty()) {
                lineas.add("FALLA  doble lectura: " + String.join("; ", mal));
                return parte(false, "PARADO en la comprobación final: " + String.join("; ", mal), lineas, hechos, avisos, rescates, clave, ensayo);
            }
            lineas.add("ok     doble lectura: los " + escrito.size() + " datos escritos siguen siendo exactos");
        }

        String resumen = (ensayo ? "ENSAYO CORRECTO: " : "HECHO: ") + hechos + " pasos"
                + (escrito.isEmpty() ? "" : ", " + escrito.size() + " datos comprobados dos veces")
                + (avisos == 0 ? "" : " · " + avisos + " aviso(s) para actualizar el mapa")
                + (rescates == 0 ? "" : " · " + rescates + " atasco(s) resueltos por la IA");
        return parte(true, resumen, lineas, hechos, avisos, rescates, clave, ensayo);
    }

    /** Marca de «este paso no había que hacerlo» (ensayo), para distinguirlo de «ha ido bien». */
    private static final String SALTADO = new String("saltado");

    /** Hace un paso. Devuelve null si ha ido bien, SALTADO si no tocaba hacerlo, o por qué ha fallado. */
    private String hacerPaso(String hacer, String control, String apodo, String valor, double segundos,
            Map<String, Object> m, boolean ensayo, List<String> lineas, Map<String, String[]> escrito) {
        switch (hacer) {
            case "escribir":
                if (valor == null) {
                    return "el guion no dice qué valor poner";
                }
                // «secreto:mn» = la contraseña guardada con DPAPI. Se descifra aquí, se escribe y se borra de memoria.
                // Nunca se enseña, nunca se apunta en el parte, y la comprobación es «hay algo escrito», no qué.
                if (valor.startsWith("secreto:")) {
                    return escribirSecreto(control, apodo, valor.substring("secreto:".length()), ensayo, lineas);
                }
                // «usuario:mn» = el usuario guardado junto a esa contraseña. Este sí se puede ver y comprobar.
                if (valor.startsWith("usuario:")) {
                    String cual = valor.substring("usuario:".length());
                    try {
                        String u = es.amagna.asistente.manos.Secreto.usuario(cual);
                        if (u == null) {
                            return "no hay usuario guardado para «" + cual + "»: guárdalo con «asistente secreto " + cual + "»";
                        }
                        valor = u;
                    } catch (IOException e) {
                        return "no se ha podido leer el usuario de «" + cual + "»: " + e.getMessage();
                    }
                }
                if (ensayo) {
                    lineas.add("ENSAYO escribir " + control + " (" + apodo + "): encontrado, NO se escribe");
                    return SALTADO;
                }
                String e = controles.escribir(mapa.ventana(), apodo, valor);
                if (!e.startsWith("IGUAL")) {
                    return e;
                }
                escrito.put(control, new String[] {apodo, valor});
                return null;
            case "pulsar":
                String p = controles.pulsar(mapa.ventana(), apodo);
                return p.startsWith("Pulsado") || p.startsWith("Desplegado") ? null : p;
            case "esperar":
                String s = controles.esperar(mapa.ventana(), apodo, que(m.get("que")), valor, segundos);
                return s.startsWith("Ya está") ? null : s;
            case "comprobar":
                if (ensayo) {
                    lineas.add("ENSAYO comprobar " + control + " (" + apodo + "): encontrado; su valor no se mira, "
                            + "porque en ensayo no se ha escrito nada");
                    return SALTADO;
                }
                String hay = controles.valorDe(mapa.ventana(), apodo);
                return valor == null || valor.equals(hay) ? null
                        : "se esperaba «" + valor + "» y hay «" + hay + "»";
            case "tecla":
                if (valor == null || valor.isBlank()) {
                    return "el guion no dice qué tecla pulsar";
                }
                if (ensayo) {
                    lineas.add("ENSAYO tecla " + valor + ": NO se pulsa");
                    return SALTADO;
                }
                try {
                    new es.amagna.asistente.manos.ManosRobot(false).tecla(valor, 1);
                    return null;
                } catch (RuntimeException ex) {
                    return "no se ha podido pulsar «" + valor + "»: " + ex.getMessage();
                }
            default:
                return "«" + hacer + "» no se sabe hacer (escribir, pulsar, esperar, comprobar, tecla)";
        }
    }

    /** Escribe un secreto sin que aparezca en ningún sitio. */
    private String escribirSecreto(String control, String apodo, String nombre, boolean ensayo, List<String> lineas) {
        if (ensayo) {
            lineas.add("ENSAYO escribir " + control + " (" + apodo + "): el secreto «" + nombre + "» "
                    + (es.amagna.asistente.manos.Secreto.hay(nombre) ? "está guardado" : "NO ESTÁ GUARDADO"));
            return es.amagna.asistente.manos.Secreto.hay(nombre) ? SALTADO
                    : "el secreto «" + nombre + "» no está guardado: guárdalo con «asistente secreto " + nombre + "»";
        }
        char[] clave;
        try {
            clave = es.amagna.asistente.manos.Secreto.leer(nombre);
        } catch (IOException e) {
            return e.getMessage();
        }
        if (clave == null) {
            return "el secreto «" + nombre + "» no está guardado: guárdalo con «asistente secreto " + nombre + "»";
        }
        try {
            String r = controles.escribir(mapa.ventana(), apodo, new String(clave));
            // Aquí la comprobación de siempre («escribo y lo releo igual») NO VALE, y es importante entender por qué:
            // Windows PROTEGE los campos de contraseña. Al leerlos devuelve vacío o puntos, nunca lo que llevan
            // dentro. Eso no es un fallo, es lo que tiene que pasar; si se pudieran leer, cualquier programa podría
            // robar contraseñas de cualquier otro.
            //
            // Así que se distingue lo único que importa: si el campo ACEPTÓ lo que se le dio o no.
            //   · IGUAL    → el campo no está protegido y además coincide: comprobado del todo.
            //   · DISTINTO → se escribió bien, pero al releerlo sale tapado: es lo normal en una contraseña.
            //   · otra cosa→ el campo no dejó escribir: eso sí es un fallo.
            // Y la comprobación de verdad viene después, y es la buena: si la contraseña no fuera la que es, no se
            // entraría, y la sesión se queda esperando una ventana principal que no llega.
            if (r.startsWith("IGUAL")) {
                lineas.add("ok     escribir " + control + " = «(secreto " + nombre + ", " + clave.length + " caracteres)»");
                return null;
            }
            if (r.startsWith("DISTINTO")) {
                String hay = controles.valorDe(mapa.ventana(), apodo);
                int dentro = hay == null ? 0 : hay.length();
                lineas.add("ok     escribir " + control + " = «(secreto " + nombre + ", " + clave.length + " caracteres)» · "
                        + (dentro == 0 ? "el campo está protegido y no se deja leer (normal en una contraseña)"
                                       : "el campo lo tapa: se ven " + dentro + " caracteres")
                        + "; se comprobará al entrar");
                return null;
            }
            // El mensaje del control podría llevar dentro lo escrito: se limpia antes de devolverlo.
            return sinSecretos(r, new String(clave));
        } finally {
            java.util.Arrays.fill(clave, '\0');
        }
    }

    /** Por si acaso: que un mensaje de error no acabe enseñando la contraseña. */
    private static String sinSecretos(String mensaje, String secreto) {
        return secreto.isEmpty() ? mensaje : mensaje.replace(secreto, "(secreto)");
    }

    private static Controles.Que que(Object v) {
        return switch (String.valueOf(v == null ? "aparezca" : v)) {
            case "desaparezca" -> Controles.Que.DESAPAREZCA;
            case "se active" -> Controles.Que.SE_ACTIVE;
            case "valga" -> Controles.Que.VALGA;
            default -> Controles.Que.APAREZCA;
        };
    }

    private static Map<String, Object> mapaDe(Map<?, ?> crudo) {
        Map<String, Object> r = new LinkedHashMap<>();
        crudo.forEach((k, v) -> r.put(String.valueOf(k), v));
        return r;
    }

    /** Prueba los apodos del mapa por orden y devuelve el que funciona. */
    private String resolver(String control, double esperarSegundos) {
        List<String> apodos = mapa.apodos(control);
        long fin = System.currentTimeMillis() + (long) (Math.max(0, esperarSegundos) * 1000);
        do {
            for (String a : apodos) {
                if (controles.existe(mapa.ventana(), a)) {
                    return a;
                }
            }
            if (System.currentTimeMillis() >= fin) {
                break;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (true);
        throw new Fallo("no se encuentra «" + control + "» en la ventana «" + mapa.ventana() + "» de ninguna de sus formas ("
                + String.join(" / ", apodos) + "). O no estamos en esa pantalla, o el programa ha cambiado y hay que repasar el mapa.");
    }

    private Parte parte(boolean bien, String resumen, List<String> lineas, int hechos, int avisos, int rescates, String clave, boolean ensayo) {
        registro.accept(resumen);
        if (bien && !ensayo && !clave.isEmpty()) {
            try {
                Files.createDirectories(carpetaHechos());
                Files.writeString(carpetaHechos().resolve(limpio(clave) + ".txt"), LocalDateTime.now().toString(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                registro.accept("AVISO: no se ha podido apuntar que «" + clave + "» ya está hecho: " + e.getMessage());
            }
        }
        return new Parte(bien, resumen, lineas, hechos, avisos, rescates);
    }

    /** Abre el programa del mapa si no está ya abierto. */
    public String abrirSiHaceFalta() {
        if (ventanas.handleDe(mapa.ventana()) != null) {
            return "Ya estaba abierto.";
        }
        if (mapa.ejecutable().isBlank()) {
            return "No está abierto «" + mapa.ventana() + "» y el mapa no dice con qué programa se abre.";
        }
        ventanas.abrir(mapa.ejecutable(), mapa.argumentos());
        return controles.esperar(mapa.ventana(), null, Controles.Que.APAREZCA, null, 60);
    }

    private static String limpio(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Lo que pasa cuando el programa no está donde el mapa dice. */
    public static final class Fallo extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Fallo(String m) {
            super(m);
        }
    }
}
