package es.amagna.asistente.cerebro;

import es.amagna.asistente.Json;
import es.amagna.asistente.manos.FrenoDeEmergencia;
import es.amagna.asistente.manos.Manos;
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
 * Para gastar poco: las instrucciones van en caché y solo se conservan las últimas capturas; las anteriores se
 * sustituyen por un texto («captura antigua»).
 *
 * El resultado final: Claude acaba con «TERMINADO: …» o «PARADO: …» (lo piden las instrucciones).
 */
public final class Agente {
    public static final String HERRAMIENTA = "computer_toolset_20260801";
    private static final int CAPTURAS_QUE_SE_GUARDAN = 3;

    public record Resultado(boolean terminado, String mensaje, int pasos, long tokensEntrada, long tokensSalida, long tokensCache) {
    }

    private final Cerebro cerebro;
    private final Manos manos;
    private final String modelo;
    private final String instrucciones;
    private final int pasosMax;
    private final Consumer<String> registro;

    public Agente(Cerebro cerebro, Manos manos, String modelo, String instrucciones, int pasosMax, Consumer<String> registro) {
        this.cerebro = cerebro;
        this.manos = manos;
        this.modelo = modelo;
        this.instrucciones = instrucciones;
        this.pasosMax = pasosMax;
        this.registro = registro;
    }

    @SuppressWarnings("unchecked")
    public Resultado ejecutar(String tarea) {
        List<Object> mensajes = new ArrayList<>();
        mensajes.add(Json.obj("role", "user", "content", tarea));
        long ent = 0, sal = 0, cache = 0;
        for (int paso = 1; paso <= pasosMax; paso++) {
            recortarCapturas(mensajes);
            Map<String, Object> peticion = Json.obj(
                    "model", modelo,
                    "max_tokens", 4096,
                    "system", List.of(Json.obj("type", "text", "text", instrucciones, "cache_control", Json.obj("type", "ephemeral"))),
                    "tools", List.of(Json.obj("type", HERRAMIENTA, "configs", Json.obj("zoom", Json.obj("enabled", true)), "cache_control", Json.obj("type", "ephemeral"))),
                    "messages", mensajes);
            Map<String, Object> r = cerebro.pensar(peticion);
            Map<String, Object> uso = (Map<String, Object>) r.getOrDefault("usage", Map.of());
            ent += num(uso.get("input_tokens"));
            sal += num(uso.get("output_tokens"));
            cache += num(uso.get("cache_read_input_tokens"));
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
                } else if ("tool_use".equals(b.get("type"))) {
                    String id = (String) b.get("id");
                    String accion = (String) b.get("name");
                    Map<String, Object> in = (Map<String, Object>) b.getOrDefault("input", Map.of());
                    if (fallo) {
                        resultados.add(resultado(id, List.of(textoBloque("Not executed: an earlier computer action in this turn failed.")), true));
                        continue;
                    }
                    registro.accept("  paso " + paso + " · " + accion + (in.isEmpty() ? "" : " " + resumen(in)));
                    try {
                        resultados.add(resultado(id, ejecutarAccion(accion, in), false));
                    } catch (FrenoDeEmergencia e) {
                        throw e;
                    } catch (RuntimeException e) {
                        fallo = true;
                        registro.accept("    error: " + e.getMessage());
                        resultados.add(resultado(id, List.of(textoBloque("Error: " + e.getMessage())), true));
                    }
                }
            }
            if (resultados.isEmpty()) { // sin acciones: ha terminado
                String t = texto.toString().strip();
                return new Resultado(t.startsWith("TERMINADO"), t.isEmpty() ? "(sin mensaje final)" : t, paso, ent, sal, cache);
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

    private static Map<String, Object> resultado(String id, List<Object> contenido, boolean error) {
        Map<String, Object> m = Json.obj("type", "tool_result", "tool_use_id", id, "toolset_name", "computer", "content", contenido);
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

    /** Deja solo las últimas capturas; las anteriores pasan a ser un texto. Ahorra mucho en cada paso. */
    @SuppressWarnings("unchecked")
    static void recortarCapturas(List<Object> mensajes) {
        int vistas = 0;
        for (int k = mensajes.size() - 1; k >= 0; k--) {
            Map<String, Object> m = (Map<String, Object>) mensajes.get(k);
            if (!"user".equals(m.get("role")) || !(m.get("content") instanceof List<?> partes)) {
                continue;
            }
            for (Object p : partes) {
                Map<String, Object> tr = (Map<String, Object>) p;
                if (!"tool_result".equals(tr.get("type")) || !(tr.get("content") instanceof List<?> cont)) {
                    continue;
                }
                List<Object> nuevo = new ArrayList<>();
                for (Object x : cont) {
                    Map<String, Object> bloque = (Map<String, Object>) x;
                    if ("image".equals(bloque.get("type")) && ++vistas > CAPTURAS_QUE_SE_GUARDAN) {
                        nuevo.add(textoBloque("[captura antigua omitida]"));
                    } else {
                        nuevo.add(bloque);
                    }
                }
                tr.put("content", nuevo);
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
