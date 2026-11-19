package es.amagna.asistente.jarvis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * El trabajo de oficina de Jarvis, sin pantalla y sin librerías: crear PDF, Word y Excel, citas de calendario (.ics) y
 * borradores de correo (.eml), y leer Word y Excel. Todo se escribe con {@link Ficheros} (solo carpetas permitidas, sin
 * sobrescribir sin decirlo). Un borrador de correo NO se envía: queda como fichero para que la persona lo abra y lo envíe.
 *
 * Texto de los documentos: una línea por párrafo. «# » = título, «## » = subtítulo, «- » = viñeta, línea vacía = espacio.
 */
public final class Oficina {
    static final int MAX_PARRAFOS = 5000;
    static final int MAX_FILAS = 5000;
    static final Pattern CORREO = Pattern.compile("[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private final Ficheros ficheros;

    public Oficina(Ficheros ficheros) {
        this.ficheros = ficheros;
    }

    // ───────────────────────────── PDF ─────────────────────────────

    private static final int[] ANCHOS = {
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556, 556, 556,
        278, 278, 584, 584, 584, 556, 1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, 667, 778, 722, 667,
        611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, 333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833,
        556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584};

    private static double ancho(String t, double tam, boolean negrita) {
        double w = 0;
        for (char c : t.toCharArray()) {
            w += c >= 32 && c <= 126 ? ANCHOS[c - 32] : 556;
        }
        return w * tam / 1000.0 * (negrita ? 1.06 : 1.0);
    }

    private record Linea(String texto, double tam, boolean negrita, double sangria, double antes) { }

    private static List<Linea> maquetar(String titulo, List<String> parrafos, double anchoUtil) {
        List<Linea> r = new ArrayList<>();
        if (titulo != null && !titulo.isBlank()) {
            partir(r, titulo.strip(), 20, true, 0, 0, anchoUtil);
            r.add(new Linea("", 11, false, 0, 6));
        }
        for (String p : parrafos) {
            String t = p == null ? "" : p.replace("\t", "    ");
            if (t.isBlank()) {
                r.add(new Linea("", 11, false, 0, 4));
            } else if (t.startsWith("## ")) {
                partir(r, t.substring(3).strip(), 13, true, 0, 8, anchoUtil);
            } else if (t.startsWith("# ")) {
                partir(r, t.substring(2).strip(), 16, true, 0, 12, anchoUtil);
            } else if (t.startsWith("- ")) {
                partir(r, "•  " + t.substring(2).strip(), 11, false, 14, 0, anchoUtil);
            } else {
                partir(r, t.strip(), 11, false, 0, 0, anchoUtil);
            }
        }
        return r;
    }

    private static void partir(List<Linea> r, String t, double tam, boolean negrita, double sangria, double antes, double anchoUtil) {
        StringBuilder actual = new StringBuilder();
        boolean primera = true;
        for (String palabra : t.split(" ")) {
            String prueba = actual.length() == 0 ? palabra : actual + " " + palabra;
            if (actual.length() > 0 && ancho(prueba, tam, negrita) > anchoUtil - sangria) {
                r.add(new Linea(actual.toString(), tam, negrita, sangria, primera ? antes : 0));
                primera = false;
                actual = new StringBuilder(palabra);
            } else {
                actual = new StringBuilder(prueba);
            }
        }
        r.add(new Linea(actual.toString(), tam, negrita, sangria, primera ? antes : 0));
    }

    private static final Charset WIN = Charset.forName("windows-1252");

    private static String escaparPdf(String t) {
        byte[] b = t.getBytes(WIN); // lo que no existe en Windows-1252 sale como «?»
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            int c = x & 0xff;
            if (c == '(' || c == ')' || c == '\\') {
                s.append('\\').append((char) c);
            } else if (c < 32 || c > 126) {
                s.append(String.format("\\%03o", c));
            } else {
                s.append((char) c);
            }
        }
        return s.toString();
    }

    /** Un PDF A4 con el texto maquetado y paginado (Helvetica). */
    public static byte[] pdf(String titulo, List<String> parrafos) throws IOException {
        if (parrafos.size() > MAX_PARRAFOS) {
            throw new IllegalArgumentException("Demasiado texto (máximo " + MAX_PARRAFOS + " párrafos).");
        }
        final double W = 595, H = 842, M = 56;
        List<Linea> lineas = maquetar(titulo, parrafos, W - 2 * M);
        List<StringBuilder> paginas = new ArrayList<>();
        StringBuilder pag = new StringBuilder();
        double y = H - M;
        for (Linea l : lineas) {
            double alto = l.tam * 1.35;
            if (y - l.antes - alto < M && pag.length() > 0) {
                paginas.add(pag);
                pag = new StringBuilder();
                y = H - M;
            }
            y -= l.antes + alto;
            if (!l.texto.isEmpty()) {
                pag.append("BT /").append(l.negrita ? "F2" : "F1").append(' ').append(l.tam).append(" Tf ")
                        .append(String.format(Locale.ROOT, "%.2f %.2f Td ", M + l.sangria, y))
                        .append('(').append(escaparPdf(l.texto)).append(") Tj ET\n");
            }
        }
        paginas.add(pag);
        int n = paginas.size();
        // objetos: 1 catálogo, 2 páginas, 3 y 4 fuentes, luego (página, contenido) por cada página
        List<String> objs = new ArrayList<>();
        objs.add("<< /Type /Catalog /Pages 2 0 R >>");
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) {
            kids.append(5 + 2 * i).append(" 0 R ");
        }
        objs.add("<< /Type /Pages /Kids [" + kids + "] /Count " + n + " >>");
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
        for (int i = 0; i < n; i++) {
            objs.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + (int) W + " " + (int) H + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents "
                    + (6 + 2 * i) + " 0 R >>");
            String c = paginas.get(i).toString();
            objs.add("<< /Length " + c.getBytes(StandardCharsets.ISO_8859_1).length + " >>\nstream\n" + c + "endstream");
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write("%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1));
        int[] pos = new int[objs.size() + 1];
        for (int i = 0; i < objs.size(); i++) {
            pos[i + 1] = o.size();
            o.write(((i + 1) + " 0 obj\n" + objs.get(i) + "\nendobj\n").getBytes(StandardCharsets.ISO_8859_1));
        }
        int xref = o.size();
        StringBuilder x = new StringBuilder("xref\n0 " + (objs.size() + 1) + "\n0000000000 65535 f \n");
        for (int i = 1; i <= objs.size(); i++) {
            x.append(String.format("%010d 00000 n \n", pos[i]));
        }
        x.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        o.write(x.toString().getBytes(StandardCharsets.ISO_8859_1));
        return o.toByteArray();
    }

    // ───────────────────────────── Word ─────────────────────────────

    static String xml(String t) {
        StringBuilder s = new StringBuilder();
        for (char c : t.toCharArray()) {
            switch (c) {
                case '&' -> s.append("&amp;");
                case '<' -> s.append("&lt;");
                case '>' -> s.append("&gt;");
                case '"' -> s.append("&quot;");
                default -> {
                    if (c >= 32 || c == '\t' || c == '\n') {
                        s.append(c);
                    }
                }
            }
        }
        return s.toString();
    }

    private static byte[] zip(String[][] entradas) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b, StandardCharsets.UTF_8)) {
            for (String[] e : entradas) {
                z.putNextEntry(new ZipEntry(e[0]));
                z.write(e[1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return b.toByteArray();
    }

    private static String parrafoWord(String t) {
        int sz = 22;
        boolean neg = false;
        String pPr = "";
        if (t.startsWith("## ")) {
            t = t.substring(3);
            sz = 26;
            neg = true;
            pPr = "<w:pPr><w:spacing w:before=\"160\" w:after=\"60\"/></w:pPr>";
        } else if (t.startsWith("# ")) {
            t = t.substring(2);
            sz = 32;
            neg = true;
            pPr = "<w:pPr><w:spacing w:before=\"240\" w:after=\"80\"/></w:pPr>";
        } else if (t.startsWith("- ")) {
            t = "•  " + t.substring(2);
            pPr = "<w:pPr><w:ind w:left=\"360\" w:hanging=\"260\"/></w:pPr>";
        }
        return "<w:p>" + pPr + "<w:r><w:rPr>" + (neg ? "<w:b/>" : "") + "<w:sz w:val=\"" + sz + "\"/></w:rPr><w:t xml:space=\"preserve\">"
                + xml(t) + "</w:t></w:r></w:p>";
    }

    /** Un .docx válido (Word/LibreOffice) con el mismo formato de texto que el PDF. */
    public static byte[] docx(String titulo, List<String> parrafos) throws IOException {
        if (parrafos.size() > MAX_PARRAFOS) {
            throw new IllegalArgumentException("Demasiado texto (máximo " + MAX_PARRAFOS + " párrafos).");
        }
        StringBuilder cuerpo = new StringBuilder();
        if (titulo != null && !titulo.isBlank()) {
            cuerpo.append("<w:p><w:pPr><w:spacing w:after=\"160\"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val=\"40\"/></w:rPr><w:t xml:space=\"preserve\">")
                    .append(xml(titulo.strip())).append("</w:t></w:r></w:p>");
        }
        for (String p : parrafos) {
            cuerpo.append(parrafoWord(p == null ? "" : p));
        }
        String doc = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + cuerpo + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>";
        return zip(new String[][] {
            {"[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>"},
            {"_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"},
            {"word/document.xml", doc}});
    }

    // ───────────────────────────── Excel ─────────────────────────────

    static String columna(int i) {
        StringBuilder s = new StringBuilder();
        for (i++; i > 0; i = (i - 1) / 26) {
            s.insert(0, (char) ('A' + (i - 1) % 26));
        }
        return s.toString();
    }

    /**
     * Un .xlsx válido. {@code filas}: cada fila es una lista de celdas (texto o número). La primera fila va en negrita.
     * El texto es siempre texto (nunca una fórmula): un «=…» se queda como texto.
     */
    public static byte[] xlsx(String hoja, List<List<Object>> filas) throws IOException {
        if (filas.size() > MAX_FILAS) {
            throw new IllegalArgumentException("Demasiadas filas (máximo " + MAX_FILAS + ").");
        }
        String nombre = (hoja == null || hoja.isBlank() ? "Hoja1" : hoja.replaceAll("[\\\\/?*\\[\\]:]", " ").strip());
        if (nombre.length() > 31) {
            nombre = nombre.substring(0, 31);
        }
        int cols = 0;
        for (List<Object> f : filas) {
            cols = Math.max(cols, f.size());
        }
        int[] anchos = new int[Math.max(cols, 1)];
        StringBuilder datos = new StringBuilder();
        for (int r = 0; r < filas.size(); r++) {
            datos.append("<row r=\"").append(r + 1).append("\">");
            List<Object> f = filas.get(r);
            for (int c = 0; c < f.size(); c++) {
                Object v = f.get(c);
                if (v == null || (v instanceof String s0 && s0.isEmpty())) {
                    continue;
                }
                String ref = columna(c) + (r + 1);
                String estilo = r == 0 ? " s=\"1\"" : "";
                if (v instanceof Number n) {
                    datos.append("<c r=\"").append(ref).append('"').append(estilo).append("><v>").append(n.toString()).append("</v></c>");
                    anchos[c] = Math.max(anchos[c], n.toString().length());
                } else {
                    String s = String.valueOf(v);
                    datos.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(estilo).append("><is><t xml:space=\"preserve\">")
                            .append(xml(s)).append("</t></is></c>");
                    anchos[c] = Math.max(anchos[c], s.length());
                }
            }
            datos.append("</row>");
        }
        StringBuilder cs = new StringBuilder("<cols>");
        for (int c = 0; c < anchos.length; c++) {
            cs.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"").append(Math.min(60, Math.max(8, anchos[c] + 2)))
                    .append("\" customWidth=\"1\"/>");
        }
        cs.append("</cols>");
        String cab = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
        String sheet = cab + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" + cs + "<sheetData>" + datos + "</sheetData></worksheet>";
        return zip(new String[][] {
            {"[Content_Types].xml", cab + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>"},
            {"_rels/.rels", cab + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"},
            {"xl/workbook.xml", cab + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\""
                + xml(nombre) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"},
            {"xl/_rels/workbook.xml.rels", cab + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"},
            {"xl/styles.xml", cab + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs></styleSheet>"},
            {"xl/worksheets/sheet1.xml", sheet}});
    }

    // ───────────────────────────── Calendario y correo ─────────────────────────────

    private static String ics(String t) {
        return t.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r", "").replace("\n", "\\n");
    }

    private static String plegar(String linea) {
        StringBuilder s = new StringBuilder();
        int n = 0;
        for (int i = 0; i < linea.length(); ) {
            int cp = linea.codePointAt(i);
            int bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (n + bytes > 73) {
                s.append("\r\n ");
                n = 1;
            }
            s.appendCodePoint(cp);
            n += bytes;
            i += Character.charCount(cp);
        }
        return s.toString();
    }

    /** Un evento de calendario (.ics) que Outlook, Google y el móvil abren con «Añadir». Hora local, sin zona. */
    public static byte[] cita(String titulo, String inicioIso, int minutos, String lugar, String descripcion) {
        if (titulo == null || titulo.isBlank()) {
            throw new IllegalArgumentException("Falta el título de la cita.");
        }
        LocalDateTime ini;
        try {
            ini = LocalDateTime.parse(inicioIso.strip().replace(' ', 'T').length() == 10 ? inicioIso.strip() + "T09:00" : inicioIso.strip().replace(' ', 'T'));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("La fecha y hora no se entienden (usa AAAA-MM-DDTHH:mm).");
        }
        if (minutos < 5 || minutos > 60 * 24) {
            minutos = 60;
        }
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        List<String> l = new ArrayList<>(List.of("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Jarvis//ES", "CALSCALE:GREGORIAN", "BEGIN:VEVENT",
                "UID:" + java.util.UUID.randomUUID() + "@jarvis", "DTSTAMP:" + LocalDateTime.now(java.time.ZoneOffset.UTC).format(f) + "Z",
                "DTSTART:" + ini.format(f), "DTEND:" + ini.plusMinutes(minutos).format(f), "SUMMARY:" + ics(titulo.strip())));
        if (lugar != null && !lugar.isBlank()) {
            l.add("LOCATION:" + ics(lugar.strip()));
        }
        if (descripcion != null && !descripcion.isBlank()) {
            l.add("DESCRIPTION:" + ics(descripcion.strip()));
        }
        l.addAll(List.of("BEGIN:VALARM", "TRIGGER:-PT15M", "ACTION:DISPLAY", "DESCRIPTION:Recordatorio", "END:VALARM", "END:VEVENT", "END:VCALENDAR"));
        StringBuilder s = new StringBuilder();
        for (String x : l) {
            s.append(plegar(x)).append("\r\n");
        }
        return s.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Un borrador de correo (.eml, sin enviar). Outlook y Thunderbird lo abren listo para revisar y enviar. */
    public static byte[] borradorCorreo(List<String> para, String asunto, String texto) {
        if (para.isEmpty()) {
            throw new IllegalArgumentException("Falta el destinatario.");
        }
        for (String d : para) {
            if (!CORREO.matcher(d.strip()).matches()) {
                throw new IllegalArgumentException("Dirección de correo no válida: «" + d + "».");
            }
        }
        String as = asunto == null ? "" : asunto.replaceAll("[\\r\\n]+", " ").strip();
        String cuerpo = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString((texto == null ? "" : texto).getBytes(StandardCharsets.UTF_8));
        String eml = "X-Unsent: 1\r\nTo: " + String.join(", ", para.stream().map(String::strip).toList()) + "\r\nSubject: =?UTF-8?B?"
                + Base64.getEncoder().encodeToString(as.getBytes(StandardCharsets.UTF_8)) + "?=\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n" + cuerpo + "\r\n";
        return eml.getBytes(StandardCharsets.US_ASCII);
    }

    // ───────────────────────────── Crear con Ficheros ─────────────────────────────

    @SuppressWarnings("unchecked")
    public String crear(String tipo, String ruta, java.util.Map<String, Object> in) throws IOException {
        boolean sobre = Boolean.TRUE.equals(in.get("sobrescribir"));
        String ext = "pdf".equals(tipo) ? ".pdf" : "docx".equals(tipo) ? ".docx" : "xlsx".equals(tipo) ? ".xlsx" : "cita".equals(tipo) ? ".ics" : ".eml";
        if (!ruta.toLowerCase(Locale.ROOT).endsWith(ext)) {
            ruta += ext;
        }
        byte[] b;
        switch (tipo) {
            case "pdf" -> b = pdf(String.valueOf(in.getOrDefault("titulo", "")), lista(in.get("parrafos")));
            case "docx" -> b = docx(String.valueOf(in.getOrDefault("titulo", "")), lista(in.get("parrafos")));
            case "xlsx" -> {
                List<List<Object>> filas = new ArrayList<>();
                if (in.get("filas") instanceof List<?> fs) {
                    for (Object f : fs) {
                        filas.add(f instanceof List<?> c ? new ArrayList<Object>(c) : new ArrayList<>(List.of(String.valueOf(f))));
                    }
                }
                b = xlsx(in.get("hoja") == null ? null : String.valueOf(in.get("hoja")), filas);
            }
            case "cita" -> b = cita(String.valueOf(in.getOrDefault("titulo", "")), String.valueOf(in.getOrDefault("inicio", "")),
                    in.get("minutos") instanceof Number n ? n.intValue() : 60, (String) in.get("lugar"), (String) in.get("descripcion"));
            default -> b = borradorCorreo(in.get("para") instanceof List<?> l ? l.stream().map(String::valueOf).toList()
                    : in.get("para") == null ? List.of() : List.of(String.valueOf(in.get("para")).split("[,;]")),
                    (String) in.get("asunto"), String.valueOf(in.getOrDefault("texto", "")));
        }
        String r = ficheros.escribirBytes(ruta, b, sobre);
        return "eml".equals(ext.substring(1)) ? r + " Es un BORRADOR: no se ha enviado nada; hay que abrirlo y enviarlo." : r;
    }

    private static List<String> lista(Object o) {
        List<String> r = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                r.add(String.valueOf(x));
            }
        } else if (o != null) {
            r.addAll(List.of(String.valueOf(o).split("\\R", -1)));
        }
        return r;
    }

    // ───────────────────────────── Leer Word y Excel ─────────────────────────────

    private static String entrada(ZipFile z, String nombre) throws IOException {
        ZipEntry e = z.getEntry(nombre);
        if (e == null) {
            return "";
        }
        try (InputStream in = z.getInputStream(e)) {
            return new String(in.readNBytes(8_000_000), StandardCharsets.UTF_8);
        }
    }

    private static String desxml(String t) {
        return t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    /** El texto de un .docx o un .xlsx (las celdas, separadas por tabuladores). */
    public String leerDocumento(String ruta) throws IOException {
        Path p = ficheros.rutaPermitida(ruta);
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("No es un fichero: " + ruta);
        }
        if (Files.size(p) > 30_000_000) {
            throw new IllegalArgumentException("Documento demasiado grande.");
        }
        try (ZipFile z = new ZipFile(p.toFile())) {
            String t;
            if (n.endsWith(".docx")) {
                String d = entrada(z, "word/document.xml").replaceAll("</w:p>", "\n").replaceAll("<w:tab/>", "\t").replaceAll("<[^>]+>", "");
                t = desxml(d);
            } else if (n.endsWith(".xlsx")) {
                List<String> comp = new ArrayList<>();
                Matcher ms = Pattern.compile("<si>(.*?)</si>", Pattern.DOTALL).matcher(entrada(z, "xl/sharedStrings.xml"));
                while (ms.find()) {
                    comp.add(desxml(ms.group(1).replaceAll("<[^>]+>", "")));
                }
                StringBuilder s = new StringBuilder();
                Matcher mr = Pattern.compile("<row[^>]*>(.*?)</row>", Pattern.DOTALL).matcher(entrada(z, "xl/worksheets/sheet1.xml"));
                while (mr.find()) {
                    List<String> cel = new ArrayList<>();
                    Matcher mc = Pattern.compile("<c([^>]*?)(?:/>|>(.*?)</c>)", Pattern.DOTALL).matcher(mr.group(1));
                    while (mc.find()) {
                        String attrs = mc.group(1);
                        String cuerpo = mc.group(2) == null ? "" : mc.group(2);
                        Matcher v = Pattern.compile("<v>(.*?)</v>", Pattern.DOTALL).matcher(cuerpo);
                        String val;
                        if (attrs.contains("t=\"inlineStr\"")) {
                            val = desxml(cuerpo.replaceAll("<[^>]+>", ""));
                        } else if (v.find()) {
                            val = attrs.contains("t=\"s\"") ? comp.get(Integer.parseInt(v.group(1).strip())) : desxml(v.group(1));
                        } else {
                            val = "";
                        }
                        cel.add(val);
                    }
                    s.append(String.join("\t", cel)).append('\n');
                }
                t = s.toString();
            } else {
                throw new IllegalArgumentException("Solo sé leer .docx y .xlsx (para texto, .txt/.csv con leer_fichero).");
            }
            return t.length() > 200_000 ? t.substring(0, 200_000) + "\n[… cortado]" : t;
        } catch (java.util.zip.ZipException e) {
            throw new IllegalArgumentException("El documento no es válido o está dañado.");
        }
    }
}
