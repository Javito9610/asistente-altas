package es.amagna.asistente.cerebro;

import es.amagna.asistente.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * La API de Mensajes de Claude por HTTPS (sin librerías: el cliente HTTP de Java). La clave va en la cabecera x-api-key y
 * nunca se escribe en registros ni mensajes de error. Reintenta solo lo que tiene sentido reintentar: 429 (demasiadas
 * peticiones), 529 (saturado), 5xx y cortes de red, con esperas crecientes.
 */
public final class ClaudeApi implements Cerebro {
    private static final URI URL = URI.create("https://api.anthropic.com/v1/messages");
    private static final String VERSION = "2023-06-01";

    private final String clave;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public ClaudeApi(String clave) {
        if (clave == null || clave.isBlank()) {
            throw new IllegalArgumentException("Falta la clave de la API de Claude.");
        }
        this.clave = clave.trim();
    }

    @Override
    public Map<String, Object> pensar(Map<String, Object> peticion) {
        String cuerpo = Json.escribir(peticion);
        RuntimeException ultimo = null;
        for (int intento = 1; intento <= 4; intento++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URL).timeout(Duration.ofSeconds(180))
                        .header("content-type", "application/json")
                        .header("x-api-key", clave)
                        .header("anthropic-version", VERSION)
                        .POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build();
                HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return Json.objeto(r.body());
                }
                String tipo = "";
                try {
                    Object e = Json.objeto(r.body()).get("error");
                    tipo = e instanceof Map<?, ?> m ? m.get("type") + ": " + m.get("message") : "";
                } catch (RuntimeException ignorada) {
                    // cuerpo no JSON
                }
                ultimo = new IllegalStateException("La API de Claude respondió " + r.statusCode() + (tipo.isEmpty() ? "" : " (" + tipo + ")"));
                boolean reintentable = r.statusCode() == 429 || r.statusCode() == 529 || r.statusCode() >= 500;
                if (!reintentable) {
                    throw ultimo;
                }
            } catch (IOException e) {
                ultimo = new IllegalStateException("Sin respuesta de la API de Claude: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrumpido", e);
            }
            try {
                Thread.sleep(2000L << intento); // 4 s, 8 s, 16 s
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw ultimo;
    }
}
