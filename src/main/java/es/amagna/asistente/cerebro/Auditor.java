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
            return "AUDITORÍA NO OK (" + String.join(" · ", p) + ")" + (vio == null || vio.isBlank() || vio.equals("{}") ? "" : " [el auditor vio: " + vio + "]");
        }
    }

    static final String SISTEMA = """
            Eres el auditor de un asistente que teclea datos en un programa de gestión de un despacho. Te enseño la \
            captura de UNA ventana y la lista de los campos que me interesan. Tu trabajo es TRANSCRIBIR: decir qué se \
            VE en cada uno. No juzgas si está bien o mal: eso lo decide el código comparando tu transcripción.

            Reglas:
            - Solo cuenta lo que se ve en la captura. No supongas, no completes, no corrijas lo que lees.
            - Para cada campo de la lista, pon en "vio" EXACTAMENTE el valor que se ve (solo el valor, sin la etiqueta del \
            campo). Si el campo está vacío, pon "". Si el campo no se ve o no se lee con claridad, no lo pongas en "vio" \
            y apúntalo en "no_se_ve". «No se ve» nunca cuenta como «bien».
            - En "otros" apunta SOLO avisos, mensajes de error, campos en rojo o diálogos que tapen la ventana. NO apuntes \
            lo normal: filtros activos, listas vacías, textos de ayuda, botones, nada que sea contenido corriente.

            Contesta SOLO un objeto JSON, sin texto antes ni después:
            {"vio": {"campo": "lo que se ve"}, "no_se_ve": ["campo"], "otros": []}""";

    /**
     * Lectura PURA de la lista de adjuntos: el modelo solo transcribe lo que ve (total y nombres tal como salen,
     * cortados o no) y no juzga nada. El juicio lo hace el código con comprobarAdjuntos. Mezclar las dos cosas en un
     * veredicto hacía que el modelo diera por «diferencia» un nombre cortado por la columna.
     * Devuelve el texto «Total Adjuntos=N, Nombre Adjunto=…» listo para comprobarAdjuntos.
     */
    @SuppressWarnings("unchecked")
    public static String transcribirAdjuntos(Cerebro cerebro, String modelo, byte[] png) {
        Map<String, Object> r = cerebro.pensar(Json.obj(
                "model", modelo,
                "max_tokens", 600,
                "system", "Transcribes lo que ves, sin juzgar. Contesta SOLO un objeto JSON, sin texto antes ni después.",
                "messages", List.of(Json.obj("role", "user", "content", List.of(
                        Json.obj("type", "image", "source", Json.obj("type", "base64", "media_type", "image/png",
                                "data", Base64.getEncoder().encodeToString(png))),
                        Json.obj("type", "text", "text", "En la pestaña «Adjuntos» de esta ventana hay una lista de documentos y debajo "
                                + "un «Total Adjuntos: N». Transcribe el número N y el texto de la columna «Nombre Adjunto» de CADA fila, "
                                + "tal como se lee, aunque termine en «...». No corrijas ni completes. Formato: "
                                + "{\"total\": N, \"nombres\": [\"...\", \"...\"]}. Si no se ve la lista: {\"total\": null, \"nombres\": []}"))))));
        StringBuilder texto = new StringBuilder();
        for (Object o : (List<Object>) r.getOrDefault("content", List.of())) {
            if (o instanceof Map<?, ?> b && "text".equals(b.get("type"))) {
                texto.append(b.get("text"));
            }
        }
        String t = texto.toString();
        int a = t.indexOf('{');
        int z = t.lastIndexOf('}');
        if (a < 0 || z <= a) {
            return "";
        }
        try {
            Map<String, Object> m = Json.objeto(t.substring(a, z + 1));
            StringBuilder b = new StringBuilder();
            if (m.get("total") != null) {
                b.append("Total Adjuntos=").append(String.valueOf(m.get("total")).replaceAll("\\.0$", ""));
            }
            for (Object n : (List<Object>) m.getOrDefault("nombres", List.of())) {
                b.append(", Nombre Adjunto=").append(n);
            }
            return b.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Comprueba la lista de adjuntos EN CÓDIGO sobre lo que el auditor dice que vio: el total y que cada nombre
     * empieza por lo esperado (la columna corta los nombres largos, así que se compara el principio). Dejar esto al
     * «ok» del modelo no valía: veía bien los adjuntos pero los apuntaba con otro nombre de campo y lo daba por no visto.
     * Devuelve null si cuadra, o por qué no.
     */
    public static String comprobarAdjuntos(String vio, int total, List<String> inicios) {
        String v = vio == null ? "" : vio;
        if (!java.util.regex.Pattern.compile("(?is)total[^=,}]*=\\s*" + total + "(?!\\d)").matcher(v).find()) {
            return "el total de adjuntos debía ser " + total + " y el auditor vio: " + v;
        }
        String llano = llano(v);
        for (String i : inicios) {
            if (!llano.contains(llano(i))) {
                return "no se ve ningún adjunto que empiece por «" + i + "» (el auditor vio: " + v + ")";
            }
        }
        return null;
    }

    private static String llano(String t) {
        return java.text.Normalizer.normalize(t == null ? "" : t, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
    }

    private Auditor() {
    }

    /** Igual sin mirar mayúsculas, acentos ni espacios de sobra; un valor que la pantalla corta con «…» vale si es un principio del esperado. */
    static boolean igualLeido(String leido, String esperado) {
        String l = llano(leido).replaceAll("\\s+", " ").strip();
        String e = llano(esperado).replaceAll("\\s+", " ").strip();
        if (l.equals(e)) {
            return true;
        }
        if (l.endsWith("...") || l.endsWith("…")) {
            String base = l.replaceAll("(\\.\\.\\.|…)$", "").strip();
            return !base.isEmpty() && e.startsWith(base);
        }
        return false;
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
        List<String> falta = new ArrayList<>();
        for (Object o : (List<Object>) m.getOrDefault("no_se_ve", List.of())) {
            falta.add(String.valueOf(o));
        }
        List<String> otros = new ArrayList<>();
        for (Object o : (List<Object>) m.getOrDefault("otros", List.of())) {
            otros.add(String.valueOf(o));
        }
        Map<String, Object> vio = (Map<String, Object>) m.getOrDefault("vio", Map.of());
        // El juez es el código, no el modelo: se compara lo transcrito con lo esperado, campo a campo.
        List<String> dif = new ArrayList<>();
        for (var e : esperado.entrySet()) {
            String campo = e.getKey();
            var visto = vio.entrySet().stream().filter(k -> k.getKey().equalsIgnoreCase(campo)).findFirst();
            if (visto.isEmpty()) {
                if (!falta.contains(campo)) {
                    falta.add(campo);
                }
                continue;
            }
            String leido = String.valueOf(visto.get().getValue() == null ? "" : visto.get().getValue());
            if (!igualLeido(leido, e.getValue())) {
                dif.add(campo + ": esperado «" + e.getValue() + "», se ve «" + leido + "»");
            }
        }
        boolean ok = dif.isEmpty() && falta.isEmpty() && otros.isEmpty();
        if (!otros.isEmpty()) {
            dif.addAll(otros.stream().map(o -> "ventana: " + o).toList());
        }
        return new Veredicto(ok, dif, falta, String.valueOf(vio));
    }
}
