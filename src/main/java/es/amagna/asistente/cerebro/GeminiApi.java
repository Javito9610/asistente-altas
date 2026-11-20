package es.amagna.asistente.cerebro;

import es.amagna.asistente.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini (Google) por HTTPS, con el mismo molde que Claude: recibe la petición en formato de la API de Mensajes y devuelve la
 * respuesta en ese mismo formato, así el resto del programa no sabe con quién habla. SOLO texto: nada de herramientas ni de
 * imágenes (eso va siempre a Claude). La clave va en la cabecera x-goog-api-key y no aparece en ningún mensaje de error.
 * Un solo reintento: si está saturado o sin cuota, mejor pasar enseguida al siguiente que quedarse esperando.
 */
public final class GeminiApi implements Cerebro {
    public static final String MODELO_POR_DEFECTO = "gemini-flash-latest";
    public static final String BASE = "https://generativelanguage.googleapis.com";

    /** Sin cuota (429) o demasiado lento: quien llama puede dejarlo descansar un rato. */
    public static final class SinCuota extends IllegalStateException {
        public SinCuota(String m) {
            super(m);
        }
    }

    private final String clave;
    private final String modelo;
    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public GeminiApi(String clave, String modelo, String base) {
        if (clave == null || clave.isBlank()) {
            throw new IllegalArgumentException("Falta la clave de Gemini.");
        }
        this.clave = clave.trim();
        this.modelo = modelo == null || modelo.isBlank() ? MODELO_POR_DEFECTO : modelo.strip();
        this.base = base == null ? BASE : base;
    }

    /** ¿Se puede contestar esta petición solo con texto? (sin herramientas, imágenes ni documentos) */
    @SuppressWarnings("unchecked")
    public static boolean soloTexto(Map<String, Object> p) {
        if (p.get("tools") != null || p.get("tool_choice") != null) {
            return false;
        }
        Object msgs = p.get("messages");
        if (!(msgs instanceof List<?> l)) {
            return false;
        }
        for (Object o : l) {
            Object c = ((Map<String, Object>) o).get("content");
            if (c instanceof List<?> bloques) {
                for (Object b : bloques) {
                    if (!(b instanceof Map<?, ?> m) || !"text".equals(m.get("type"))) {
                        return false;
                    }
                }
            } else if (!(c instanceof String)) {
                return false;
            }
        }
        return true;
    }

    static String texto(Object c) {
        if (c instanceof String s) {
            return s;
        }
        StringBuilder b = new StringBuilder();
        if (c instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m && m.get("text") != null) {
                    b.append(m.get("text"));
                }
            }
        }
        return b.toString();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> pensar(Map<String, Object> peticion) {
        if (!soloTexto(peticion)) {
            throw new IllegalArgumentException("Gemini solo atiende peticiones de texto.");
        }
        List<Object> contenidos = new ArrayList<>();
        for (Object o : (List<Object>) peticion.get("messages")) {
            Map<String, Object> m = (Map<String, Object>) o;
            String rol = "assistant".equals(m.get("role")) ? "model" : "user";
            contenidos.add(Json.obj("role", rol, "parts", List.of(Json.obj("text", texto(m.get("content"))))));
        }
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("contents", contenidos);
        String sistema = texto(peticion.get("system"));
        if (!sistema.isBlank()) {
            cuerpo.put("systemInstruction", Json.obj("parts", List.of(Json.obj("text", sistema))));
        }
        Object max = peticion.get("max_tokens");
        if (max instanceof Number n) {
            cuerpo.put("generationConfig", Json.obj("maxOutputTokens", n.intValue()));
        }
        String json = Json.escribir(cuerpo);
        URI url = URI.create(base + "/v1beta/models/" + modelo + ":generateContent");
        RuntimeException ultimo = null;
        for (int intento = 1; intento <= 2; intento++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(25))
                        .header("content-type", "application/json").header("x-goog-api-key", clave)
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build();
                HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return convertir(Json.objeto(r.body()));
                }
                String detalle = "";
                try {
                    Object e = Json.objeto(r.body()).get("error");
                    detalle = e instanceof Map<?, ?> em ? String.valueOf(em.get("status")) : "";
                } catch (RuntimeException ignorada) {
                    // cuerpo no JSON
                }
                String msg = "Gemini respondió " + r.statusCode() + (detalle.isEmpty() || "null".equals(detalle) ? "" : " (" + detalle + ")")
                        + (r.statusCode() == 404 ? ": ese modelo no existe; cámbialo en datos\\gemini-modelo.txt" : "")
                        + (r.statusCode() == 400 || r.statusCode() == 403 ? ": revisa la clave (asistente secreto gemini)" : "");
                if (r.statusCode() == 429) {
                    throw new SinCuota(msg);
                }
                ultimo = new IllegalStateException(msg);
                if (r.statusCode() < 500) {
                    throw ultimo;
                }
            } catch (java.net.http.HttpTimeoutException e) {
                // Tardar tanto no se arregla reintentando: se pasa a otra IA y Gemini descansa un rato.
                throw new SinCuota("Gemini tarda demasiado (más de 25 s)");
            } catch (IOException e) {
                ultimo = new IllegalStateException("Sin respuesta de Gemini: " + e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrumpido", e);
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw ultimo;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> convertir(Map<String, Object> r) {
        StringBuilder b = new StringBuilder();
        Object cands = r.get("candidates");
        if (cands instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> c && c.get("content") instanceof Map<?, ?> cont
                && cont.get("parts") instanceof List<?> partes) {
            for (Object p : partes) {
                if (p instanceof Map<?, ?> pm && pm.get("text") != null) {
                    b.append(pm.get("text"));
                }
            }
        }
        if (b.length() == 0) {
            throw new IllegalStateException("Gemini no devolvió texto (puede haberlo bloqueado).");
        }
        Map<String, Object> uso = r.get("usageMetadata") instanceof Map<?, ?> u ? (Map<String, Object>) u : Map.of();
        return Json.obj("content", List.of(Json.obj("type", "text", "text", b.toString())),
                "usage", Json.obj("input_tokens", num(uso.get("promptTokenCount")), "output_tokens", num(uso.get("candidatesTokenCount"))));
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }
}
