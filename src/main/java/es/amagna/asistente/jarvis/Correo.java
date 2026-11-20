package es.amagna.asistente.jarvis;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Correo de verdad, sin librerías: SMTP para enviar e IMAP para leer, con la contraseña (o «contraseña de aplicación») en el
 * almacén cifrado de Windows; nunca en un fichero ni en un registro. Sirve para Gmail y para cualquier correo normal por IMAP/SMTP
 * (también uno propio de Jarvis, que es lo más prudente). Microsoft 365 exige un inicio de sesión distinto (OAuth) que no está.
 *
 * ENVIAR no lo decide nadie por sí solo: lo que llega aquí ya lo ha confirmado la persona leyendo el borrador completo.
 * LEER no marca nada como leído, y lo leído son DATOS: nunca órdenes para Jarvis.
 */
public final class Correo {
    /** Cómo se conecta a un servidor. NINGUNO solo se admite hacia el propio ordenador (para las pruebas). */
    public enum Seguridad { SSL, STARTTLS, NINGUNO }

    public record Cuenta(String direccion, String usuario, String smtp, int smtpPuerto, Seguridad smtpSeguridad,
                         String imap, int imapPuerto, Seguridad imapSeguridad) {
        /** Los servidores habituales según el dominio de la dirección; para otros, smtp.dominio e imap.dominio. */
        public static Cuenta de(String direccion) {
            String dom = direccion.substring(direccion.indexOf('@') + 1).toLowerCase(Locale.ROOT);
            if (dom.equals("gmail.com") || dom.equals("googlemail.com")) {
                return new Cuenta(direccion, direccion, "smtp.gmail.com", 465, Seguridad.SSL, "imap.gmail.com", 993, Seguridad.SSL);
            }
            if (dom.equals("outlook.com") || dom.equals("hotmail.com") || dom.equals("live.com") || dom.equals("outlook.es") || dom.equals("hotmail.es")) {
                return new Cuenta(direccion, direccion, "smtp-mail.outlook.com", 587, Seguridad.STARTTLS, "outlook.office365.com", 993, Seguridad.SSL);
            }
            return new Cuenta(direccion, direccion, "smtp." + dom, 465, Seguridad.SSL, "imap." + dom, 993, Seguridad.SSL);
        }
    }

    public record Mensaje(String de, String asunto, String fecha, String texto) {
    }

    private static final Pattern DIRECCION = Pattern.compile("[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final long MAX_ADJUNTOS = 20L * 1024 * 1024;
    private static final SecureRandom AZAR = new SecureRandom();

    private final Cuenta cuenta;
    private final Supplier<String> clave;

    public Correo(Cuenta cuenta, Supplier<String> clave) {
        this.cuenta = cuenta;
        this.clave = clave;
    }

    public Cuenta cuenta() {
        return cuenta;
    }

    /** Separa y valida las direcciones («a@x.com, b@y.es»); null si alguna no vale o hay más de 5. */
    public static List<String> direcciones(String texto) {
        List<String> r = new ArrayList<>();
        if (texto == null) {
            return null;
        }
        for (String d : texto.split("[,;\\s]+")) {
            if (d.isBlank()) {
                continue;
            }
            if (!DIRECCION.matcher(d).matches() || d.contains("\r") || d.contains("\n")) {
                return null;
            }
            r.add(d);
        }
        return r.isEmpty() || r.size() > 5 ? null : r;
    }

    // ------------------------------------------------------------------ enviar

    public void enviar(List<String> para, String asunto, String texto, List<Path> adjuntos) throws IOException {
        if (para == null || para.isEmpty()) {
            throw new IllegalArgumentException("Falta el destinatario.");
        }
        if (asunto == null || asunto.isBlank() || asunto.contains("\n") || asunto.contains("\r")) {
            throw new IllegalArgumentException("El asunto no vale.");
        }
        byte[] mensaje = construir(cuenta.direccion(), para, asunto, texto, adjuntos == null ? List.of() : adjuntos);
        try (Conexion c = Conexion.abrir(cuenta.smtp(), cuenta.smtpPuerto(), cuenta.smtpSeguridad())) {
            c.esperar("220");
            c.mandar("EHLO jarvis.local");
            c.esperar("250");
            if (cuenta.smtpSeguridad() == Seguridad.STARTTLS) {
                c.mandar("STARTTLS");
                c.esperar("220");
                c.subirATls(cuenta.smtp());
                c.mandar("EHLO jarvis.local");
                c.esperar("250");
            }
            c.mandar("AUTH LOGIN");
            c.esperar("334");
            c.mandar(b64(cuenta.usuario()));
            c.esperar("334");
            c.mandarSecreto(b64(clave.get()));
            c.esperar("235");
            c.mandar("MAIL FROM:<" + cuenta.direccion() + ">");
            c.esperar("250");
            for (String p : para) {
                c.mandar("RCPT TO:<" + p + ">");
                c.esperar("250", "251");
            }
            c.mandar("DATA");
            c.esperar("354");
            c.cuerpo(mensaje);
            c.esperar("250");
            c.mandar("QUIT");
        }
    }

    static byte[] construir(String desde, List<String> para, String asunto, String texto, List<Path> adjuntos) throws IOException {
        String frontera = "=_jarvis_" + Long.toHexString(AZAR.nextLong()) + Long.toHexString(AZAR.nextLong());
        StringBuilder b = new StringBuilder();
        b.append("From: ").append(desde).append("\r\n");
        b.append("To: ").append(String.join(", ", para)).append("\r\n");
        b.append("Subject: ").append(cabecera(asunto)).append("\r\n");
        b.append("Date: ").append(ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME)).append("\r\n");
        b.append("Message-ID: <").append(Long.toHexString(AZAR.nextLong())).append("@jarvis.local>\r\n");
        b.append("MIME-Version: 1.0\r\n");
        long total = 0;
        for (Path p : adjuntos) {
            total += Files.size(p);
        }
        if (total > MAX_ADJUNTOS) {
            throw new IllegalArgumentException("Los adjuntos pesan demasiado (máximo 20 MB).");
        }
        byte[] cuerpo = (texto == null ? "" : texto).getBytes(StandardCharsets.UTF_8);
        if (adjuntos.isEmpty()) {
            b.append("Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n").append(base64Lineas(cuerpo));
        } else {
            b.append("Content-Type: multipart/mixed; boundary=\"").append(frontera).append("\"\r\n\r\n");
            b.append("--").append(frontera).append("\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
                    .append(base64Lineas(cuerpo));
            for (Path p : adjuntos) {
                String nombre = p.getFileName().toString().replace("\"", "_").replace("\r", "_").replace("\n", "_");
                b.append("--").append(frontera).append("\r\nContent-Type: application/octet-stream; name=\"").append(cabecera(nombre)).append("\"\r\n")
                        .append("Content-Transfer-Encoding: base64\r\nContent-Disposition: attachment; filename=\"").append(cabecera(nombre)).append("\"\r\n\r\n")
                        .append(base64Lineas(Files.readAllBytes(p)));
            }
            b.append("--").append(frontera).append("--\r\n");
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String cabecera(String s) {
        boolean ascii = s.chars().allMatch(c -> c < 128);
        return ascii ? s : "=?UTF-8?B?" + b64(s) + "?=";
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Lineas(byte[] datos) {
        return Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(datos) + "\r\n";
    }

    // ------------------------------------------------------------------ leer

    /** Los últimos correos sin leer (como mucho {@code max}), sin marcarlos como leídos. */
    public List<Mensaje> noLeidos(int max) throws IOException {
        int tope = Math.max(1, Math.min(max, 10));
        List<Mensaje> r = new ArrayList<>();
        try (Conexion c = Conexion.abrir(cuenta.imap(), cuenta.imapPuerto(), cuenta.imapSeguridad())) {
            c.esperar("* OK");
            c.mandarSecreto("a1 LOGIN " + comillas(cuenta.usuario()) + " " + comillas(clave.get()));
            c.esperarEtiqueta("a1");
            c.mandar("a2 SELECT INBOX");
            c.esperarEtiqueta("a2");
            c.mandar("a3 SEARCH UNSEEN");
            String busqueda = c.esperarEtiqueta("a3");
            List<String> ids = new ArrayList<>();
            Matcher m = Pattern.compile("(?m)^\\* SEARCH([\\d ]*)").matcher(busqueda);
            if (m.find()) {
                for (String id : m.group(1).trim().split("\\s+")) {
                    if (!id.isBlank()) {
                        ids.add(id);
                    }
                }
            }
            for (String id : ids.subList(Math.max(0, ids.size() - tope), ids.size())) {
                c.mandar("f" + id + " FETCH " + id + " (BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE CONTENT-TYPE CONTENT-TRANSFER-ENCODING)] BODY.PEEK[TEXT]<0.6000>)");
                String resp = c.esperarEtiqueta("f" + id);
                r.add(leerFetch(resp));
            }
            c.mandar("a9 LOGOUT");
        }
        return r;
    }

    private static String comillas(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static Mensaje leerFetch(String resp) {
        // La respuesta trae dos «literales»: {n}\r\n y n bytes. Se sacan en orden: cabeceras y texto.
        List<String> partes = new ArrayList<>();
        byte[] bytes = resp.getBytes(StandardCharsets.ISO_8859_1);
        int i = 0;
        Matcher m = Pattern.compile("\\{(\\d+)\\}\\r\\n").matcher(resp);
        while (m.find(i)) {
            int n = Integer.parseInt(m.group(1));
            int ini = m.end();
            int fin = Math.min(bytes.length, ini + n);
            partes.add(new String(bytes, ini, fin - ini, StandardCharsets.ISO_8859_1));
            i = fin;
        }
        String cab = partes.isEmpty() ? "" : partes.get(0);
        String cuerpo = partes.size() > 1 ? partes.get(1) : "";
        String ct = valor(cab, "Content-Type");
        String cte = valor(cab, "Content-Transfer-Encoding");
        String texto = textoDe(ct, cte, cuerpo);
        if (texto.length() > 3000) {
            texto = texto.substring(0, 3000) + "…";
        }
        return new Mensaje(descodificar(valor(cab, "From")), descodificar(valor(cab, "Subject")), valor(cab, "Date"), texto.strip());
    }

    private static String valor(String cabeceras, String nombre) {
        Matcher m = Pattern.compile("(?im)^" + Pattern.quote(nombre) + ":[ \\t]*(.*(?:\\r?\\n[ \\t]+.*)*)").matcher(cabeceras);
        return m.find() ? m.group(1).replaceAll("\\r?\\n[ \\t]+", " ").strip() : "";
    }

    static String descodificar(String s) {
        Matcher m = Pattern.compile("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=").matcher(s);
        StringBuilder b = new StringBuilder();
        int ult = 0;
        while (m.find()) {
            b.append(s, ult, m.start());
            try {
                Charset cs = Charset.forName(m.group(1));
                byte[] datos = m.group(2).equalsIgnoreCase("B") ? Base64.getDecoder().decode(m.group(3))
                        : quotedPrintable(m.group(3).replace('_', ' '));
                b.append(new String(datos, cs));
            } catch (RuntimeException e) {
                b.append(m.group());
            }
            ult = m.end();
        }
        b.append(s.substring(ult));
        return b.toString().replaceAll("\\?=\\s+=\\?", "").strip();
    }

    private static byte[] quotedPrintable(String s) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < b.length; i++) {
            if (b[i] == '=' && i + 2 < b.length) {
                try {
                    o.write(Integer.parseInt(new String(b, i + 1, 2, StandardCharsets.ISO_8859_1), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException e) {
                    // se queda tal cual
                }
            } else if (b[i] == '=' && i + 1 < b.length && (b[i + 1] == '\r' || b[i + 1] == '\n')) {
                i += b[i + 1] == '\r' && i + 2 < b.length && b[i + 2] == '\n' ? 2 : 1;
                continue;
            }
            o.write(b[i]);
        }
        return o.toByteArray();
    }

    /** El texto legible de un cuerpo: si es multipart, la primera parte de texto; después se deshace base64 o quoted-printable. */
    public static String textoDe(String contentType, String cte, String cuerpo) {
        String ct = contentType == null ? "" : contentType;
        Matcher fr = Pattern.compile("(?i)boundary=\"?([^\";\\s]+)\"?").matcher(ct);
        if (ct.toLowerCase(Locale.ROOT).startsWith("multipart/") && fr.find()) {
            String sep = "--" + fr.group(1);
            String textoPlano = null;
            String otro = null;
            for (String parte : cuerpo.split(Pattern.quote(sep))) {
                int corte = parte.indexOf("\r\n\r\n");
                if (corte < 0) {
                    continue;
                }
                String cab = parte.substring(0, corte);
                String cu = parte.substring(corte + 4);
                String tipo = valor(cab, "Content-Type").toLowerCase(Locale.ROOT);
                if (tipo.startsWith("multipart/") || tipo.startsWith("text/plain") || tipo.startsWith("text/html") || tipo.isEmpty()) {
                    String t = textoDe(valor(cab, "Content-Type"), valor(cab, "Content-Transfer-Encoding"), cu);
                    if (tipo.startsWith("text/html")) {
                        otro = otro == null ? t.replaceAll("(?s)<style.*?</style>|<script.*?</script>", " ").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ") : otro;
                    } else if (textoPlano == null && !t.isBlank()) {
                        textoPlano = t;
                    }
                }
            }
            return textoPlano != null ? textoPlano : otro == null ? "(sin texto legible)" : otro;
        }
        Matcher cs = Pattern.compile("(?i)charset=\"?([^\";\\s]+)\"?").matcher(ct);
        Charset charset = StandardCharsets.UTF_8;
        if (cs.find()) {
            try {
                charset = Charset.forName(cs.group(1));
            } catch (RuntimeException e) {
                // UTF-8
            }
        }
        String e = cte == null ? "" : cte.toLowerCase(Locale.ROOT).strip();
        byte[] datos = cuerpo.getBytes(StandardCharsets.ISO_8859_1);
        try {
            if (e.equals("base64")) {
                datos = Base64.getMimeDecoder().decode(cuerpo.replaceAll("\\s", ""));
            } else if (e.equals("quoted-printable")) {
                datos = quotedPrintable(cuerpo);
            }
        } catch (RuntimeException ex) {
            return "(no se ha podido leer el texto)";
        }
        return new String(datos, charset);
    }

    // ------------------------------------------------------------------ conexión

    private static final class Conexion implements AutoCloseable {
        private Socket socket;
        private InputStream in;
        private OutputStream out;

        static Conexion abrir(String host, int puerto, Seguridad seg) throws IOException {
            boolean propio = host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1");
            if (seg == Seguridad.NINGUNO && !propio) {
                throw new IllegalArgumentException("Sin cifrado solo se admite el propio ordenador.");
            }
            Conexion c = new Conexion();
            Socket plano = new Socket();
            plano.connect(new InetSocketAddress(host, puerto), 15_000);
            plano.setSoTimeout(30_000);
            c.socket = plano;
            if (seg == Seguridad.SSL) {
                c.socket = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plano, host, puerto, true);
                ((SSLSocket) c.socket).startHandshake();
            }
            c.in = new BufferedInputStream(c.socket.getInputStream());
            c.out = c.socket.getOutputStream();
            return c;
        }

        void subirATls(String host) throws IOException {
            socket = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, socket.getPort(), true);
            ((SSLSocket) socket).startHandshake();
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        void mandar(String linea) throws IOException {
            out.write((linea + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        /** Igual que mandar, pero lo que va dentro nunca se escribe en ningún error. */
        void mandarSecreto(String linea) throws IOException {
            mandar(linea);
        }

        void cuerpo(byte[] mensaje) throws IOException {
            // El punto solo en una línea acabaría el mensaje: se dobla («dot-stuffing»).
            byte[] seguro = new String(mensaje, StandardCharsets.UTF_8).replace("\r\n.", "\r\n..").getBytes(StandardCharsets.UTF_8);
            out.write(seguro);
            if (seguro.length < 2 || seguro[seguro.length - 2] != '\r' || seguro[seguro.length - 1] != '\n') {
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            out.write(".\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        String linea() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') {
                    break;
                }
                if (c != '\r') {
                    b.write(c);
                }
            }
            if (c == -1 && b.size() == 0) {
                throw new IOException("el servidor ha cortado la conexión");
            }
            return b.toString(StandardCharsets.ISO_8859_1);
        }

        void esperar(String... codigos) throws IOException {
            while (true) {
                String l = linea();
                boolean multi = l.length() > 3 && l.charAt(3) == '-';
                if (multi) {
                    continue;
                }
                for (String cod : codigos) {
                    if (l.startsWith(cod)) {
                        return;
                    }
                }
                throw new IOException("el servidor de correo ha respondido «" + l.replaceAll("(?i)(pass\\w*|clave)\\S*", "…") + "»");
            }
        }

        /** Lee respuestas IMAP hasta la línea con la etiqueta; devuelve todo lo recibido (con los literales). */
        String esperarEtiqueta(String etiqueta) throws IOException {
            StringBuilder todo = new StringBuilder();
            while (true) {
                String l = linea();
                todo.append(l).append("\r\n");
                Matcher lit = Pattern.compile("\\{(\\d+)\\}$").matcher(l);
                if (lit.find()) {
                    int n = Integer.parseInt(lit.group(1));
                    byte[] datos = in.readNBytes(n);
                    todo.append(new String(datos, StandardCharsets.ISO_8859_1));
                    continue;
                }
                if (l.startsWith(etiqueta + " ")) {
                    if (!l.startsWith(etiqueta + " OK")) {
                        throw new IOException("el servidor de correo ha respondido «" + l.replaceAll("(?i)(pass\\w*|clave)\\S*", "…") + "»");
                    }
                    return todo.toString();
                }
            }
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException e) {
                // ya cerrado
            }
        }
    }
}
