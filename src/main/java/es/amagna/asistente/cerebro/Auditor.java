package es.amagna.asistente.cerebro;

import es.amagna.asistente.Json;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * El auditor: una segunda opinión INDEPENDIENTE sobre lo que hay en pantalla. Mira la captura de una ventana y la
 * compara con lo que tenía que haber. No usa UIA ni los mismos caminos que el asistente: si el asistente escribió algo
 * mal y UIA lo leyó mal igual, el auditor lo ve con los ojos.
 *
 * Regla de oro: lo que no se ve en la captura NO está verificado. «No se ve» nunca cuenta como «bien».
 */
public final class Auditor {
    public static final String MODELO_POR_DEFECTO = "claude-haiku-5-5";

    public record Veredicto(boolean ok, List<String> diferencias, List<String> noSeVe, String vio) {
        public String resumen() {
            if (ok) {
                return "AUDITORÍA OK";
            }
            List<String> p = new ArrayList<>();
            if (!diferencias.isEmpty()) {
                p.add("no coincide: " + String.join("; ", diferencias));
            }
            if (!noSeVe.isEmpty()) {
                p.add("no se ve: " + String.join("; ", noSeVe));
            }
            return "AUDITORÍA NO OK (" + String.join(" · ", p) + ")";
        }
    }

    static final String SISTEMA = """
            Eres el auditor de un asistente que teclea datos en un programa de gestión de un despacho. Te enseño la \
            captura de UNA ventana y la lista de lo que DEBE poner en cada campo. Tu trabajo es comprobar, campo a campo, \
            que lo que se VE coincide con lo esperado.

            Reglas:
            - Solo cuenta lo que se ve en la captura. No supongas, no completes, no des por bueno lo que no se lee.
            - Si un campo esperado no se ve o no se lee con claridad, va en "no_se_ve", y eso NO es un «ok».
            - Compara el texto tal cual (ignora solo mayúsculas/minúsculas y espacios de sobra). Una letra distinta, un \
            acento, una cifra o un nombre distinto es una diferencia.
            - Un campo que debía estar vacío y tiene algo es una diferencia; uno vacío cuando debía tener valor también.
            - Si ves algo raro en la ventana (un aviso, un campo en rojo, un diálogo encima), dilo en "otros".

            Contesta SOLO un objeto JSON, sin texto antes ni después:
            {"ok": true|false, "vio": {"campo": "lo que se ve"}, "diferencias": ["campo: esperado «x», se ve «y»"], \
            "no_se_ve": ["campo"], "otros": []}
            "ok" solo es true si TODOS los campos esperados se ven y coinciden y no hay nada raro.""";

    private Auditor() {
    }

    static String pregunta(Map<String, String> esperado, String queSeVe) {
        StringBuilder b = new StringBuilder("Esto es " + queSeVe + ". Esperado en cada campo:\n");
        for (var e : esperado.entrySet()) {
            b.append("- ").append(e.getKey()).append(": «").append(e.getValue()).append("»\n");
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    public static Veredicto auditar(Cerebro cerebro, String modelo, byte[] png, Map<String, String> esperado, String queSeVe) {
        Map<String, Object> r = cerebro.pensar(Json.obj(
                "model", modelo,
                "max_tokens", 1500,
                "system", SISTEMA,
                "messages", List.of(Json.obj("role", "user", "content", List.of(
                        Json.obj("type", "image", "source", Json.obj("type", "base64", "media_type", "image/png",
                                "data", Base64.getEncoder().encodeToString(png))),
                        Json.obj("type", "text", "text", pregunta(esperado, queSeVe)))))));
        StringBuilder texto = new StringBuilder();
        for (Object o : (List<Object>) r.getOrDefault("content", List.of())) {
            if (o instanceof Map<?, ?> b && "text".equals(b.get("type"))) {
                texto.append(b.get("text"));
            }
        }
        return deJson(texto.toString(), esperado);
    }

    /** Lee el veredicto. Un veredicto ilegible, o un «ok» al que le falta un campo esperado, NO es un ok. */
    @SuppressWarnings("unchecked")
    public static Veredicto deJson(String contestacion, Map<String, String> esperado) {
        int a = contestacion.indexOf('{');
        int z = contestacion.lastIndexOf('}');
        if (a < 0 || z <= a) {
            return new Veredicto(false, List.of(), List.of("el auditor no ha dado un veredicto legible"), "");
        }
        Map<String, Object> m;
        try {
            m = Json.objeto(contestacion.substring(a, z + 1));
        } catch (RuntimeException e) {
            return new Veredicto(false, List.of(), List.of("el auditor no ha dado un veredicto legible"), "");
        }
        List<String> dif = new ArrayList<>();
        for (Object o : (List<Object>) m.getOrDefault("diferencias", List.of())) {
            dif.add(String.valueOf(o));
        }
        List<String> falta = new ArrayList<>();
        for (Object o : (List<Object>) m.getOrDefault("no_se_ve", List.of())) {
            falta.add(String.valueOf(o));
        }
        List<String> otros = new ArrayList<>();
        for (Object o : (List<Object>) m.getOrDefault("otros", List.of())) {
            otros.add(String.valueOf(o));
        }
        Map<String, Object> vio = (Map<String, Object>) m.getOrDefault("vio", Map.of());
        // Cinturón y tirantes: aunque diga ok, si no ha visto un campo esperado, no vale.
        for (String campo : esperado.keySet()) {
            boolean visto = vio.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(campo));
            if (!visto && !falta.contains(campo) && !dif.stream().anyMatch(d -> d.toLowerCase().startsWith(campo.toLowerCase()))) {
                falta.add(campo);
            }
        }
        boolean ok = Boolean.TRUE.equals(m.get("ok")) && dif.isEmpty() && falta.isEmpty() && otros.isEmpty();
        if (!otros.isEmpty()) {
            dif.addAll(otros.stream().map(o -> "ventana: " + o).toList());
        }
        return new Veredicto(ok, dif, falta, String.valueOf(vio));
    }
}
