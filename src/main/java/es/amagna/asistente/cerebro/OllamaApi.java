package es.amagna.asistente.cerebro;

import es.amagna.asistente.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Un modelo local con Ollama (gratis, sin límite y sin que nada salga del ordenador). Solo texto; para lo sencillo. */
public final class OllamaApi implements Cerebro {
    public static final String BASE = "http://127.0.0.1:11434";

    private final String modelo;
    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public OllamaApi(String modelo, String base) {
        if (modelo == null || modelo.isBlank()) {
            throw new IllegalArgumentException("Falta el modelo de Ollama.");
        }
        this.modelo = modelo.strip();
        this.base = base == null ? BASE : base;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> pensar(Map<String, Object> peticion) {
        if (!GeminiApi.soloTexto(peticion)) {
            throw new IllegalArgumentException("El modelo local solo atiende peticiones de texto.");
        }
        List<Object> msgs = new ArrayList<>();
        String sistema = GeminiApi.texto(peticion.get("system"));
        if (!sistema.isBlank()) {
            msgs.add(Json.obj("role", "system", "content", sistema));
        }
        for (Object o : (List<Object>) peticion.get("messages")) {
            Map<String, Object> m = (Map<String, Object>) o;
            msgs.add(Json.obj("role", String.valueOf(m.get("role")), "content", GeminiApi.texto(m.get("content"))));
        }
        String cuerpo = Json.escribir(Json.obj("model", modelo, "messages", msgs, "stream", false));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/chat")).timeout(Duration.ofSeconds(180))
                    .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IllegalStateException("Ollama respondió " + r.statusCode() + (r.statusCode() == 404 ? ": el modelo no está descargado (ollama pull " + modelo + ")" : ""));
            }
            Map<String, Object> j = Json.objeto(r.body());
            String texto = j.get("message") instanceof Map<?, ?> mm && mm.get("content") != null ? String.valueOf(mm.get("content")) : "";
            if (texto.isBlank()) {
                throw new IllegalStateException("Ollama no devolvió texto.");
            }
            return Json.obj("content", List.of(Json.obj("type", "text", "text", texto)), "usage",
                    Json.obj("input_tokens", j.get("prompt_eval_count") instanceof Number a ? a.longValue() : 0L,
                            "output_tokens", j.get("eval_count") instanceof Number b ? b.longValue() : 0L));
        } catch (IOException e) {
            throw new IllegalStateException("Ollama no responde (¿está arrancado?)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido", e);
        }
    }
}
