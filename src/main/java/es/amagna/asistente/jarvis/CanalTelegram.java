package es.amagna.asistente.jarvis;

import es.amagna.asistente.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Hablar con Jarvis por Telegram (un bot). Sin librerías: la API de bots es HTTPS + JSON. El token (que da @BotFather) se guarda
 * cifrado con la cuenta de Windows («asistente secreto telegram») y NUNCA se escribe en registros. Solo se atiende a los
 * números de usuario autorizados; al resto se le dice cuál es su número para que alguien lo autorice, y nada más.
 */
public final class CanalTelegram {
    /** Lo único que se necesita de la red (se cambia por uno falso en las pruebas). */
    public interface Red {
        String get(String url) throws IOException;

        /** Sube un fichero (multipart). Por defecto no se puede (las pruebas). */
        default String subir(String url, java.util.Map<String, String> campos, String campoFichero, java.nio.file.Path fichero) throws IOException {
            throw new IOException("subir no disponible");
        }
    }

    public static final String ORIGEN = "telegram";
    private final String token;
    private final Red red;
    private final Consumer<String> registro;
    private long siguiente;
    private volatile boolean parar;

    public CanalTelegram(String token, Red red, Consumer<String> registro) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Falta el token del bot de Telegram.");
        }
        this.token = token.trim();
        this.red = red;
        this.registro = registro;
    }

    public static Red redReal() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        return new Red() {
            @Override
            public String get(String url) throws IOException {
                try {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (r.statusCode() != 200) {
                        throw new IOException("Telegram respondió " + r.statusCode());
                    }
                    return r.body();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrumpido");
                }
            }

            @Override
            public String subir(String url, java.util.Map<String, String> campos, String campoFichero, java.nio.file.Path fichero) throws IOException {
                String limite = "----jarvis" + System.nanoTime();
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                for (var e : campos.entrySet()) {
                    o.write(("--" + limite + "\r\nContent-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n" + e.getValue() + "\r\n")
                            .getBytes(StandardCharsets.UTF_8));
                }
                String nombre = fichero.getFileName().toString().replace("\"", "_").replace("\r", "").replace("\n", "");
                o.write(("--" + limite + "\r\nContent-Disposition: form-data; name=\"" + campoFichero + "\"; filename=\"" + nombre
                        + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                o.write(java.nio.file.Files.readAllBytes(fichero));
                o.write(("\r\n--" + limite + "--\r\n").getBytes(StandardCharsets.UTF_8));
                try {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(180))
                            .header("Content-Type", "multipart/form-data; boundary=" + limite)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(o.toByteArray())).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (r.statusCode() != 200) {
                        throw new IOException("Telegram respondió " + r.statusCode());
                    }
                    return r.body();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrumpido");
                }
            }
        };
    }

    private String api(String metodo, String query) {
        return "https://api.telegram.org/bot" + token + "/" + metodo + (query.isEmpty() ? "" : "?" + query);
    }

    /** Pregunta una vez por mensajes nuevos y se los pasa a quien escucha: (usuario, texto). Devuelve cuántos llegaron. */
    @SuppressWarnings("unchecked")
    public int sondear(int esperaSegundos, BiConsumer<String, String> alRecibir) {
        String cuerpo;
        try {
            cuerpo = red.get(api("getUpdates", "timeout=" + esperaSegundos + "&offset=" + siguiente + "&allowed_updates=%5B%22message%22%5D"));
        } catch (IOException e) {
            registro.accept("Telegram: sin conexión (" + sinToken(e.getMessage()) + ").");
            return -1;
        }
        int n = 0;
        try {
            Map<String, Object> r = Json.objeto(cuerpo);
            for (Object o : (List<Object>) r.getOrDefault("result", List.of())) {
                Map<String, Object> u = (Map<String, Object>) o;
                siguiente = Math.max(siguiente, ((Number) u.get("update_id")).longValue() + 1);
                if (!(u.get("message") instanceof Map<?, ?> m) || !(m.get("text") instanceof String texto)
                        || !(m.get("from") instanceof Map<?, ?> from) || !(m.get("chat") instanceof Map<?, ?> chat)
                        || !"private".equals(chat.get("type"))) {
                    continue; // solo mensajes de texto en chat privado
                }
                alRecibir.accept(String.valueOf(((Number) from.get("id")).longValue()), texto);
                n++;
            }
        } catch (RuntimeException e) {
            registro.accept("Telegram: respuesta rara, se ignora.");
        }
        return n;
    }

    public void enviar(String usuario, String texto) {
        // Telegram admite 4096 caracteres por mensaje.
        for (int i = 0; i < Math.max(1, texto.length()); i += 3800) {
            String trozo = texto.substring(i, Math.min(texto.length(), i + 3800));
            try {
                red.get(api("sendMessage", "chat_id=" + usuario + "&text=" + URLEncoder.encode(trozo, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                registro.accept("Telegram: no se pudo enviar (" + sinToken(e.getMessage()) + ").");
                return;
            }
        }
    }

    /** Manda un fichero como documento (hasta 45 MB). */
    public void enviarFichero(String usuario, java.nio.file.Path fichero) {
        try {
            red.subir(api("sendDocument", ""), java.util.Map.of("chat_id", usuario), "document", fichero);
        } catch (IOException | RuntimeException e) {
            registro.accept("Telegram: no se pudo enviar el fichero (" + sinToken(String.valueOf(e.getMessage())) + ").");
        }
    }

    /** Bucle de escucha (en su propio hilo). Ante un corte de red espera y vuelve a intentarlo. */
    public Thread escuchar(BiConsumer<String, String> alRecibir) {
        Thread h = new Thread(() -> {
            long espera = 2000;
            while (!parar) {
                int n = sondear(25, alRecibir);
                if (n < 0) {
                    try {
                        Thread.sleep(espera);
                    } catch (InterruptedException e) {
                        return;
                    }
                    espera = Math.min(espera * 2, 60_000);
                } else {
                    espera = 2000;
                }
            }
        }, "telegram");
        h.setDaemon(true);
        h.start();
        return h;
    }

    public void parar() {
        parar = true;
    }

    private String sinToken(String s) {
        return s == null ? "" : s.replace(token, "***");
    }
}
