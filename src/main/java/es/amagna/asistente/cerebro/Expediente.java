package es.amagna.asistente.cerebro;

import es.amagna.asistente.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * El expediente de una ejecución: la prueba de qué hizo el asistente, paso a paso.
 *
 * Cada vez que el asistente trabaja deja una carpeta propia con:
 *  - pasos.jsonl   una línea por cada cosa que hizo, con la hora, lo que pidió y lo que le contestó el ordenador;
 *  - NNN-....png   las capturas de pantalla que fue tomando;
 *  - informe.html  un resumen para mirar, con la tarea, cómo acabó, lo que costó y toda la secuencia.
 *
 * Esto no es un adorno: en un despacho hay que poder demostrar después qué se tocó y qué no. Es lo mismo que guardan
 * las herramientas profesionales de automatización, y es lo que permitirá revisar por la mañana lo que el asistente
 * hizo por la noche sin haber estado delante.
 *
 * No se guarda ningún dato personal que no esté ya en la pantalla del programa que se está manejando; las capturas son
 * las mismas que vería quien estuviera sentado delante. La carpeta es del usuario de Windows, fuera del repositorio.
 *
 * @package Amagna\Altas
 */
public final class Expediente implements AutoCloseable {
    private static final DateTimeFormatter CARPETA = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Path carpeta;
    private final String tarea;
    private final long empezo = System.currentTimeMillis();
    private final List<String[]> hitos = new ArrayList<>(); // hora, qué, detalle, fichero de captura (o null)
    private int capturas;
    private boolean roto;

    private Expediente(Path carpeta, String tarea) {
        this.carpeta = carpeta;
        this.tarea = tarea;
    }

    /** La carpeta donde se guardan todos los expedientes. */
    public static Path base() {
        String local = System.getenv("LOCALAPPDATA");
        return Path.of(local != null ? local : System.getProperty("user.home"), "AsistenteAltas", "expedientes");
    }

    /** Abre un expediente nuevo. Si no se puede escribir, devuelve uno que no hace nada: nunca impide trabajar. */
    public static Expediente abrir(String tarea) {
        return abrirEn(base(), tarea);
    }

    /** Igual, pero eligiendo dónde (lo usan las comprobaciones). */
    public static Expediente abrirEn(Path base, String tarea) {
        Path c = base.resolve(LocalDateTime.now().format(CARPETA));
        Expediente e = new Expediente(c, tarea);
        try {
            Files.createDirectories(c);
            e.linea(Json.obj("hora", LocalDateTime.now().toString(), "que", "tarea", "texto", tarea));
        } catch (IOException | RuntimeException ex) {
            e.roto = true;
        }
        return e;
    }

    public Path carpeta() {
        return carpeta;
    }

    public boolean sirve() {
        return !roto;
    }

    private void linea(Map<String, Object> o) {
        if (roto) {
            return;
        }
        try {
            Files.writeString(carpeta.resolve("pasos.jsonl"), Json.escribir(o) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ex) {
            roto = true;
        }
    }

    /** Lo que dijo o pensó el asistente en voz alta. */
    public void dice(String texto) {
        hitos.add(new String[] {LocalDateTime.now().format(HORA), "dice", texto, null});
        linea(Json.obj("hora", LocalDateTime.now().toString(), "que", "dice", "texto", texto));
    }

    /** Una acción: qué pidió y qué le contestó el ordenador. */
    public void paso(int numero, String accion, String entrada, String respuesta) {
        hitos.add(new String[] {LocalDateTime.now().format(HORA), accion + (entrada.isBlank() ? "" : " " + entrada), respuesta, null});
        linea(Json.obj("hora", LocalDateTime.now().toString(), "que", "paso", "numero", (long) numero,
                "accion", accion, "entrada", entrada, "respuesta", respuesta));
    }

    /** Guarda una captura de pantalla y la engancha al último paso. */
    public void captura(byte[] png) {
        if (roto || png == null || png.length == 0) {
            return;
        }
        String nombre = String.format("%03d-pantalla.png", ++capturas);
        try {
            Files.write(carpeta.resolve(nombre), png);
            if (!hitos.isEmpty()) {
                hitos.get(hitos.size() - 1)[3] = nombre;
            }
            linea(Json.obj("hora", LocalDateTime.now().toString(), "que", "captura", "fichero", nombre));
        } catch (IOException | RuntimeException ex) {
            roto = true;
        }
    }

    public void error(String mensaje) {
        hitos.add(new String[] {LocalDateTime.now().format(HORA), "error", mensaje, null});
        linea(Json.obj("hora", LocalDateTime.now().toString(), "que", "error", "texto", mensaje));
    }

    /** Cierra el expediente y escribe el informe para mirar. */
    public void cerrar(boolean bien, String desenlace, int pasos, long entrada, long salida, long cache) {
        long segundos = (System.currentTimeMillis() - empezo) / 1000;
        linea(Json.obj("hora", LocalDateTime.now().toString(), "que", "fin", "bien", bien, "desenlace", desenlace,
                "pasos", (long) pasos, "segundos", segundos, "tokens_entrada", entrada, "tokens_salida", salida, "tokens_cache", cache));
        if (roto) {
            return;
        }
        try {
            Files.writeString(carpeta.resolve("informe.html"), informe(bien, desenlace, pasos, segundos, entrada, salida, cache),
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            roto = true;
        }
    }

    @Override
    public void close() {
    }

    private String informe(boolean bien, String desenlace, int pasos, long segundos, long entrada, long salida, long cache) {
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><html lang=\"es\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Asistente Altas · ").append(esc(carpeta.getFileName().toString())).append("</title><style>")
                .append(":root{--f:#1a1a1a;--s:#5a5a5a;--b:#e3e0d8;--p:#f7f5f0;--v:#1d6b3f;--r:#9b2c2c}")
                .append("@media(prefers-color-scheme:dark){:root:not([data-theme=\"light\"]){--f:#eee;--s:#a8a8a8;--b:#39373a;--p:#232225;--v:#5fbd84;--r:#e07a7a}}")
                .append("body{margin:0;background:#fbfaf7;color:var(--f);font:16px/1.55 ui-sans-serif,system-ui,-apple-system,Segoe UI,sans-serif}")
                .append("@media(prefers-color-scheme:dark){body{background:#161517}}")
                .append("main{max-width:860px;margin:0 auto;padding:40px 16px 80px}")
                .append("h1{font-size:1.5rem;margin:0 0 4px}.sub{color:var(--s);font-size:.9rem;margin-bottom:28px}")
                .append(".tarea{background:var(--p);border:1px solid var(--b);border-radius:10px;padding:14px 16px;margin-bottom:20px;white-space:pre-wrap}")
                .append(".fin{font-weight:600;padding:12px 16px;border-radius:10px;border:1px solid var(--b);margin-bottom:8px;white-space:pre-wrap}")
                .append(".ok{color:var(--v)}.mal{color:var(--r)}")
                .append("dl{display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:12px;margin:20px 0 32px}")
                .append("dt{color:var(--s);font-size:.78rem;text-transform:uppercase;letter-spacing:.04em}dd{margin:2px 0 0;font-size:1.2rem;font-variant-numeric:tabular-nums}")
                .append("ol{list-style:none;padding:0;margin:0;border-left:2px solid var(--b)}")
                .append("li{padding:0 0 18px 18px;position:relative}")
                .append("li:before{content:'';position:absolute;left:-5px;top:8px;width:8px;height:8px;border-radius:50%;background:var(--b)}")
                .append(".h{color:var(--s);font-size:.78rem;font-variant-numeric:tabular-nums}")
                .append(".q{font-weight:600;word-break:break-word}.r{color:var(--s);font-size:.9rem;white-space:pre-wrap;word-break:break-word}")
                .append("img{max-width:100%;border:1px solid var(--b);border-radius:8px;margin-top:8px;display:block}")
                .append("</style></head><body><main>")
                .append("<h1>Lo que hizo el Asistente Altas</h1><p class=\"sub\">")
                .append(esc(carpeta.getFileName().toString().replace('_', ' '))).append("</p>")
                .append("<div class=\"tarea\"><strong>Tarea:</strong> ").append(esc(tarea)).append("</div>")
                .append("<div class=\"fin ").append(bien ? "ok" : "mal").append("\">").append(esc(desenlace)).append("</div>")
                .append("<dl><dt>Pasos</dt><dd>").append(pasos).append("</dd>")
                .append("<dt>Tiempo</dt><dd>").append(segundos / 60).append(" min ").append(segundos % 60).append(" s</dd>")
                .append("<dt>Capturas</dt><dd>").append(capturas).append("</dd>")
                .append("<dt>Tokens</dt><dd>").append(entrada + salida + cache).append("</dd></dl><ol>");
        for (String[] h : hitos) {
            b.append("<li><div class=\"h\">").append(esc(h[0])).append("</div><div class=\"q\">").append(esc(corto(h[1])))
                    .append("</div>");
            if (h[2] != null && !h[2].isBlank()) {
                b.append("<div class=\"r\">").append(esc(corto(h[2]))).append("</div>");
            }
            if (h[3] != null) {
                b.append("<img src=\"").append(esc(h[3])).append("\" alt=\"Captura de pantalla\">");
            }
            b.append("</li>");
        }
        return b.append("</ol></main></body></html>").toString();
    }

    private static String corto(String s) {
        return s == null ? "" : s.length() > 1200 ? s.substring(0, 1200) + "…" : s;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Para las pruebas: lee una captura guardada. */
    public static String comoTexto(byte[] png) {
        return Base64.getEncoder().encodeToString(png);
    }
}
