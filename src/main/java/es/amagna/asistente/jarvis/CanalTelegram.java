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

        /** Baja un fichero (hasta 20 MB). Por defecto no se puede (las pruebas). */
        default byte[] bajar(String url) throws IOException {
            throw new IOException("bajar no disponible");
        }

        /** Sube un fichero (multipart). Por defecto no se puede (las pruebas). */
        default String subir(String url, java.util.Map<String, String> campos, String campoFichero, java.nio.file.Path fichero) throws IOException {
            throw new IOException("subir no disponible");
        }
    }

    public static final String ORIGEN = "telegram";
    private String base = "https://api.telegram.org";
    private java.util.function.Predicate<String> autorizado = u -> false;
    private java.util.function.BiFunction<String, byte[], String> guardarFichero;
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

    /**
     * Los documentos que mande una persona AUTORIZADA se bajan (máx. 20 MB) y se guardan con {@code guardar(nombre, bytes)}, que
     * devuelve dónde quedaron; a Jarvis le llega como un mensaje («He recibido el fichero…»). De los demás no se baja nada.
     */
    public CanalTelegram conRecepcion(java.util.function.Predicate<String> autorizado, java.util.function.BiFunction<String, byte[], String> guardar) {
        this.autorizado = autorizado;
        this.guardarFichero = guardar;
        return this;
    }

    /** Solo para pruebas: apunta a un servidor local que imita a Telegram. */
    public CanalTelegram conBase(String base) {
        this.base = base;
        return this;
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
            public byte[] bajar(String url) throws IOException {
                try {
                    HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
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
        return base + "/bot" + token + "/" + metodo + (query.isEmpty() ? "" : "?" + query);
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
                if (!(u.get("message") instanceof Map<?, ?> m) || !(m.get("from") instanceof Map<?, ?> from) || !(m.get("chat") instanceof Map<?, ?> chat)
                        || !"private".equals(chat.get("type"))) {
                    continue; // solo chat privado
                }
                String quien = String.valueOf(((Number) from.get("id")).longValue());
                if (m.get("text") instanceof String texto) {
                    alRecibir.accept(quien, texto);
                    n++;
                } else if (m.get("document") instanceof Map<?, ?> doc && guardarFichero != null) {
                    String aviso = recibirDocumento(quien, (Map<String, Object>) doc, m.get("caption") instanceof String c ? c : "");
                    if (aviso != null) {
                        alRecibir.accept(quien, aviso);
                        n++;
                    }
                }
            }
        } catch (RuntimeException e) {
            registro.accept("Telegram: respuesta rara, se ignora.");
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private String recibirDocumento(String quien, Map<String, Object> doc, String pie) {
        if (!autorizado.test(quien)) {
            registro.accept("Telegram: documento de " + quien + " (no autorizado): no se baja.");
            return null;
        }
        String nombre = String.valueOf(doc.getOrDefault("file_name", "fichero")).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replace("..", "_").strip();
        if (nombre.isEmpty() || nombre.startsWith(".")) {
            nombre = "fichero" + nombre;
        }
        if (nombre.length() > 80) {
            String ext = nombre.contains(".") ? nombre.substring(nombre.lastIndexOf('.')) : "";
            nombre = nombre.substring(0, 80 - Math.min(ext.length(), 10)) + ext.substring(0, Math.min(ext.length(), 10));
        }
        long tam = doc.get("file_size") instanceof Number t ? t.longValue() : 0;
        if (tam > 20_000_000) {
            enviar(quien, "Ese fichero pesa más de 20 MB y Telegram no me deja bajarlo.");
            return null;
        }
        try {
            Map<String, Object> r = Json.objeto(red.get(api("getFile", "file_id=" + URLEncoder.encode(String.valueOf(doc.get("file_id")), StandardCharsets.UTF_8))));
            String ruta = String.valueOf(((Map<String, Object>) r.get("result")).get("file_path"));
            byte[] datos = red.bajar(base + "/file/bot" + token + "/" + ruta);
            if (datos.length > 20_000_000) {
                enviar(quien, "Ese fichero es demasiado grande.");
                return null;
            }
            String donde = guardarFichero.apply(nombre, datos);
            return "He recibido el fichero «" + donde + "»." + (pie.isBlank() ? " (No dijo nada más: pregúntame qué quiere que haga con él.)" : " " + pie);
        } catch (IOException | RuntimeException e) {
            registro.accept("Telegram: no se pudo bajar el fichero (" + sinToken(String.valueOf(e.getMessage())) + ").");
            enviar(quien, "No he podido bajar ese fichero: " + sinToken(String.valueOf(e.getMessage())));
            return null;
        }
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

    /** El nombre del bot (@usuario) si el token vale; si no, lanza con el motivo (sin el token). */
    @SuppressWarnings("unchecked")
    public String quienSoy() throws IOException {
        try {
            Map<String, Object> r = Json.objeto(red.get(api("getMe", "")));
            if (!Boolean.TRUE.equals(r.get("ok"))) {
                throw new IOException("Telegram no acepta ese token.");
            }
            Map<String, Object> res = (Map<String, Object>) r.get("result");
            return "@" + res.get("username") + " (" + res.get("first_name") + ")";
        } catch (IOException e) {
            throw new IOException(sinToken(String.valueOf(e.getMessage())));
        } catch (RuntimeException e) {
            throw new IOException("Respuesta rara de Telegram.");
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
