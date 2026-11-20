package es.amagna.asistente.jarvis;

import es.amagna.asistente.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Outlook / Microsoft 365 con el inicio de sesión moderno (OAuth) y la API de Microsoft Graph: es lo que Microsoft deja usar hoy
 * (el usuario y la contraseña por IMAP/SMTP están cerrados casi en todas partes). Se inicia sesión UNA vez desde el navegador
 * con un código («flujo de dispositivo»): Jarvis no ve nunca la contraseña de Microsoft. Lo que se guarda, cifrado con Windows, es
 * el «token de actualización», que se puede revocar desde la cuenta de Microsoft cuando se quiera.
 *
 * Hace falta registrar una aplicación gratuita en Microsoft Entra (ver docs\JARVIS.md): su «client id» no es un secreto.
 * Permisos mínimos: Mail.Send y Mail.Read (de la persona que inicia sesión), y offline_access.
 */
public final class CorreoMicrosoft implements Buzon {
    public static final String LOGIN = "https://login.microsoftonline.com";
    public static final String GRAPH = "https://graph.microsoft.com";
    static final String PERMISOS = "offline_access Mail.Send Mail.Read User.Read";
    private static final long MAX_ADJUNTOS = 3L * 1024 * 1024;

    private final String clientId;
    private final String tenant;
    private final String direccion;
    private final String login;
    private final String graph;
    private final Supplier<String> refresco;
    private final Consumer<String> guardarRefresco;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private String acceso;
    private long caduca;

    public CorreoMicrosoft(String clientId, String tenant, String direccion, String login, String graph,
                           Supplier<String> refresco, Consumer<String> guardarRefresco) {
        this.clientId = clientId;
        this.tenant = tenant == null || tenant.isBlank() ? "common" : tenant;
        this.direccion = direccion;
        this.login = login == null ? LOGIN : login;
        this.graph = graph == null ? GRAPH : graph;
        this.refresco = refresco;
        this.guardarRefresco = guardarRefresco;
    }

    @Override
    public String direccion() {
        return direccion;
    }

    // ------------------------------------------------------------- iniciar sesión (una vez)

    /** Resultado del inicio de sesión: el token de actualización y la dirección de la cuenta. */
    public record Sesion(String refresco, String direccion) {
    }

    /**
     * Flujo de dispositivo: enseña un código y una dirección (microsoft.com/devicelogin); la persona los usa en el navegador y
     * aquí se espera a que termine. {@code mostrar} recibe lo que hay que enseñar.
     */
    public static Sesion iniciarSesion(String clientId, String tenant, String login, String graph, Consumer<String> mostrar) throws IOException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        String t = tenant == null || tenant.isBlank() ? "common" : tenant;
        String base = (login == null ? LOGIN : login) + "/" + t + "/oauth2/v2.0";
        Map<String, Object> dc = Json.objeto(post(http, base + "/devicecode", Map.of("client_id", clientId, "scope", PERMISOS), null));
        if (dc.get("device_code") == null) {
            throw new IOException("Microsoft no ha dado un código de acceso: " + dc.getOrDefault("error_description", dc.get("error")));
        }
        mostrar.accept(String.valueOf(dc.getOrDefault("message", "Abre " + dc.get("verification_uri") + " y escribe el código " + dc.get("user_code"))));
        long fin = System.currentTimeMillis() + 1000L * (dc.get("expires_in") instanceof Number n ? n.longValue() : 900);
        long espera = 1000L * (dc.get("interval") instanceof Number n ? n.longValue() : 5);
        while (System.currentTimeMillis() < fin) {
            sleep(espera);
            Map<String, Object> r = Json.objeto(post(http, base + "/token", Map.of("grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id", clientId, "device_code", String.valueOf(dc.get("device_code"))), null));
            if (r.get("refresh_token") != null && r.get("access_token") != null) {
                String yo = get(http, (graph == null ? GRAPH : graph) + "/v1.0/me?$select=mail,userPrincipalName", String.valueOf(r.get("access_token")), null);
                Map<String, Object> m = Json.objeto(yo);
                String dir = String.valueOf(m.get("mail") != null ? m.get("mail") : m.get("userPrincipalName"));
                return new Sesion(String.valueOf(r.get("refresh_token")), dir);
            }
            String err = String.valueOf(r.get("error"));
            if ("slow_down".equals(err)) {
                espera += 5000;
            } else if (!"authorization_pending".equals(err)) {
                throw new IOException("Microsoft no ha aceptado el inicio de sesión: " + r.getOrDefault("error_description", err));
            }
        }
        throw new IOException("Se acabó el tiempo sin que se usara el código. Vuelve a empezar.");
    }

    // ------------------------------------------------------------- token de acceso

    private synchronized String token() throws IOException {
        if (acceso != null && System.currentTimeMillis() < caduca - 60_000) {
            return acceso;
        }
        String base = login + "/" + tenant + "/oauth2/v2.0";
        Map<String, Object> r = Json.objeto(post(http, base + "/token", Map.of("grant_type", "refresh_token", "client_id", clientId,
                "refresh_token", refresco.get(), "scope", PERMISOS), null));
        if (r.get("access_token") == null) {
            throw new IOException("Microsoft ha rechazado la sesión del correo (" + r.getOrDefault("error", "?")
                    + "). Hay que volver a iniciarla con configurar-correo-microsoft.cmd.");
        }
        acceso = String.valueOf(r.get("access_token"));
        caduca = System.currentTimeMillis() + 1000L * (r.get("expires_in") instanceof Number n ? n.longValue() : 3000);
        if (r.get("refresh_token") != null && guardarRefresco != null) {
            guardarRefresco.accept(String.valueOf(r.get("refresh_token")));
        }
        return acceso;
    }

    // ------------------------------------------------------------- enviar y leer

    @Override
    public void enviar(List<String> para, String asunto, String texto, List<Path> adjuntos) throws IOException {
        if (para == null || para.isEmpty()) {
            throw new IllegalArgumentException("Falta el destinatario.");
        }
        if (asunto == null || asunto.isBlank()) {
            throw new IllegalArgumentException("El asunto no vale.");
        }
        List<Object> dest = new ArrayList<>();
        for (String p : para) {
            dest.add(Json.obj("emailAddress", Json.obj("address", p)));
        }
        List<Object> adj = new ArrayList<>();
        long total = 0;
        for (Path p : adjuntos == null ? List.<Path>of() : adjuntos) {
            total += Files.size(p);
            if (total > MAX_ADJUNTOS) {
                throw new IllegalArgumentException("Por Outlook los adjuntos no pueden pasar de 3 MB en total.");
            }
            adj.add(Json.obj("@odata.type", "#microsoft.graph.fileAttachment", "name", p.getFileName().toString(),
                    "contentType", "application/octet-stream", "contentBytes", Base64.getEncoder().encodeToString(Files.readAllBytes(p))));
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("subject", asunto);
        msg.put("body", Json.obj("contentType", "Text", "content", texto == null ? "" : texto));
        msg.put("toRecipients", dest);
        if (!adj.isEmpty()) {
            msg.put("attachments", adj);
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(graph + "/v1.0/me/sendMail")).timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token()).header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.escribir(Json.obj("message", msg, "saveToSentItems", true)))).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 202 && r.statusCode() != 200) {
                throw new IOException("Microsoft no ha enviado el correo (" + r.statusCode() + ")" + (r.statusCode() == 403 ? ": falta el permiso Mail.Send" : ""));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrumpido");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Correo.Mensaje> noLeidos(int max) throws IOException {
        int n = Math.max(1, Math.min(max, 10));
        String url = graph + "/v1.0/me/mailFolders/inbox/messages?$filter=" + URLEncoder.encode("isRead eq false", StandardCharsets.UTF_8)
                + "&$top=" + n + "&$orderby=" + URLEncoder.encode("receivedDateTime desc", StandardCharsets.UTF_8)
                + "&$select=from,subject,receivedDateTime,body";
        Map<String, Object> r = Json.objeto(get(http, url, token(), "outlook.body-content-type=\"text\""));
        List<Correo.Mensaje> res = new ArrayList<>();
        if (r.get("value") instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> m = (Map<String, Object>) o;
                String de = "";
                if (m.get("from") instanceof Map<?, ?> f && f.get("emailAddress") instanceof Map<?, ?> ea) {
                    de = (ea.get("name") == null ? "" : ea.get("name") + " ") + "<" + ea.get("address") + ">";
                }
                String cuerpo = m.get("body") instanceof Map<?, ?> b && b.get("content") != null ? String.valueOf(b.get("content")).strip() : "";
                res.add(new Correo.Mensaje(de, String.valueOf(m.getOrDefault("subject", "")), String.valueOf(m.getOrDefault("receivedDateTime", "")),
                        cuerpo.length() > 3000 ? cuerpo.substring(0, 3000) + "…" : cuerpo));
            }
        }
        return res;
    }

    // ------------------------------------------------------------- HTTP

    private static String post(HttpClient http, String url, Map<String, String> campos, String bearer) throws IOException {
        StringBuilder b = new StringBuilder();
        campos.forEach((k, v) -> b.append(b.length() == 0 ? "" : "&").append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("content-type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(b.toString())).build();
            return http.send(req, HttpResponse.BodyHandlers.ofString()).body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrumpido");
        }
    }

    private static String get(HttpClient http, String url, String token, String prefer) throws IOException {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token);
            if (prefer != null) {
                rb.header("Prefer", prefer);
            }
            HttpResponse<String> r = http.send(rb.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IOException("Microsoft ha respondido " + r.statusCode() + (r.statusCode() == 403 ? ": falta el permiso Mail.Read" : ""));
            }
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrumpido");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
