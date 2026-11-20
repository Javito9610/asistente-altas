package es.amagna.asistente.jarvis;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Abrir cosas en el ordenador SIN IA y sin adivinar: una página web (solo http/https, sin usuario ni clave en la dirección),
 * un programa de una lista permitida, o una carpeta/documento de las carpetas de trabajo. Lo que no está en la lista no se
 * abre aquí: pasa a una tarea «libre», que pide confirmación. Nunca se abren consolas, instaladores ni scripts.
 * La lista se puede ampliar en datos\apps.txt con líneas «nombre=ruta completa del programa».
 */
public final class Abrir {
    /** Nombres que la gente dice → programa de Windows. */
    static final Map<String, String> PROGRAMAS = Map.ofEntries(
            Map.entry("calculadora", "calc.exe"), Map.entry("bloc de notas", "notepad.exe"), Map.entry("notas", "notepad.exe"),
            Map.entry("paint", "mspaint.exe"), Map.entry("explorador", "explorer.exe"), Map.entry("explorador de archivos", "explorer.exe"),
            Map.entry("chrome", "chrome.exe"), Map.entry("google chrome", "chrome.exe"), Map.entry("edge", "msedge.exe"),
            Map.entry("firefox", "firefox.exe"), Map.entry("word", "winword.exe"), Map.entry("excel", "excel.exe"),
            Map.entry("powerpoint", "powerpnt.exe"), Map.entry("outlook", "outlook.exe"), Map.entry("teams", "ms-teams.exe"),
            Map.entry("whatsapp", "whatsapp.exe"), Map.entry("recortes", "snippingtool.exe"));

    public interface Lanzador {
        void programa(String exe) throws IOException;

        void direccion(URI uri) throws IOException;

        void ruta(Path p) throws IOException;
    }

    private final Ficheros ficheros;
    private final Map<String, String> propios = new LinkedHashMap<>();
    private final Lanzador lanzador;
    private final java.util.function.Function<String, String> primerVideo;

    public Abrir(Ficheros ficheros, Path appsTxt, Lanzador lanzador) {
        this(ficheros, appsTxt, lanzador, Abrir::primerVideoDeYoutube);
    }

    /** {@code primerVideo}: dada una búsqueda, el identificador del primer vídeo de YouTube (o null). Se cambia en las pruebas. */
    public Abrir(Ficheros ficheros, Path appsTxt, Lanzador lanzador, java.util.function.Function<String, String> primerVideo) {
        this.ficheros = ficheros;
        this.lanzador = lanzador;
        this.primerVideo = primerVideo;
        try {
            if (appsTxt != null && Files.isRegularFile(appsTxt)) {
                for (String l : Files.readAllLines(appsTxt, StandardCharsets.UTF_8)) {
                    int i = l.indexOf('=');
                    if (!l.startsWith("#") && i > 0 && permitidoComoPrograma(l.substring(i + 1).strip())) {
                        propios.put(norm(l.substring(0, i)), l.substring(i + 1).strip());
                    }
                }
            }
        } catch (IOException e) {
            // sin lista propia
        }
    }

    /** En Windows de verdad: cmd start resuelve «chrome» o «winword» por el registro; Desktop abre páginas y documentos. */
    public static Lanzador deWindows() {
        return new Lanzador() {
            public void programa(String exe) throws IOException {
                new ProcessBuilder("cmd.exe", "/c", "start", "", exe).start();
            }

            public void direccion(URI uri) throws IOException {
                java.awt.Desktop.getDesktop().browse(uri);
            }

            public void ruta(Path p) throws IOException {
                java.awt.Desktop.getDesktop().open(p.toFile());
            }
        };
    }

    static boolean permitidoComoPrograma(String exe) {
        String bajo = exe.toLowerCase(Locale.ROOT);
        if (bajo.contains("\"") || bajo.contains("&") || bajo.contains("|") || bajo.contains("%") || bajo.contains("^")) {
            return false;
        }
        for (String mala : new String[]{"cmd.exe", "powershell", "pwsh", "wscript", "cscript", "mshta", "regedit", "msiexec", "rundll32", "bash", "wsl",
                "setup", "install", ".bat", ".cmd", ".ps1", ".vbs", ".msi", ".js", ".reg", ".scr", ".hta", ".lnk", ".jar"}) {
            if (bajo.contains(mala)) {
                return false;
            }
        }
        return bajo.endsWith(".exe");
    }

    /** ¿Sabe abrir esto él solo? (si no, la petición pasa a «libre»). */
    public boolean sabe(String que, String valor) {
        try {
            return switch (que) {
                case "web" -> uriSegura(valor) != null;
                case "google", "youtube", "maps" -> valor != null && !valor.isBlank() && valor.length() <= 200 && valor.chars().noneMatch(c -> c < 32);
                case "programa" -> exeDe(valor) != null;
                case "carpeta", "documento" -> ficheros.dentro(valor) != null;
                default -> false;
            };
        } catch (RuntimeException e) {
            return false;
        }
    }

    public String abrir(String que, String valor) {
        try {
            switch (que) {
                case "web" -> {
                    URI u = uriSegura(valor);
                    if (u == null) {
                        return "No abro esa dirección (solo páginas http/https, sin usuario ni clave).";
                    }
                    lanzador.direccion(u);
                    return "Abierta " + u.getHost() + " en el navegador.";
                }
                case "google" -> {
                    lanzador.direccion(URI.create("https://www.google.com/search?q=" + codificar(valor)));
                    return "Buscando «" + valor + "» en Google.";
                }
                case "maps" -> {
                    lanzador.direccion(URI.create("https://www.google.com/maps/search/" + codificar(valor)));
                    return "Buscando «" + valor + "» en Google Maps.";
                }
                case "youtube" -> {
                    String id = null;
                    try {
                        id = primerVideo.apply(valor);
                    } catch (RuntimeException e) {
                        // se abre la búsqueda
                    }
                    if (id != null && id.matches("[A-Za-z0-9_-]{11}")) {
                        lanzador.direccion(URI.create("https://www.youtube.com/watch?v=" + id));
                        return "Poniendo «" + valor + "» en YouTube. (Si el navegador no empieza solo, dale al play: a veces bloquea el sonido automático.)";
                    }
                    lanzador.direccion(URI.create("https://www.youtube.com/results?search_query=" + codificar(valor)));
                    return "No he podido elegir el vídeo; te he abierto los resultados de «" + valor + "» en YouTube.";
                }
                case "programa" -> {
                    String exe = exeDe(valor);
                    if (exe == null) {
                        return "No tengo «" + valor + "» en mi lista de programas que abro sin preguntar.";
                    }
                    lanzador.programa(exe);
                    return "Abierto " + valor + ".";
                }
                case "carpeta", "documento" -> {
                    Path p = ficheros.dentro(valor);
                    if (!Files.exists(p)) {
                        return "No existe «" + valor + "».";
                    }
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (Files.isRegularFile(p) && Ficheros.PROHIBIDAS.stream().anyMatch(n::endsWith)) {
                        return "NO PERMITIDO: no abro programas ni scripts desde aquí.";
                    }
                    lanzador.ruta(p);
                    return "Abierto «" + p.getFileName() + "».";
                }
                default -> {
                    return "No sé abrir eso.";
                }
            }
        } catch (IllegalArgumentException | IOException e) {
            return "No he podido abrirlo: " + e.getMessage();
        }
    }

    private static String codificar(String v) {
        return java.net.URLEncoder.encode(v.strip(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** El primer vídeo (no anuncio ni lista) de los resultados de YouTube para una búsqueda; null si no se puede saber. */
    static String primerVideoDeYoutube(String busqueda) {
        try {
            var http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
            var req = java.net.http.HttpRequest.newBuilder(URI.create("https://www.youtube.com/results?search_query=" + codificar(busqueda)))
                    .timeout(java.time.Duration.ofSeconds(15)).header("Accept-Language", "es-ES,es;q=0.9")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36").GET().build();
            String html = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
            var m = java.util.regex.Pattern.compile("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"").matcher(html);
            return m.find() ? m.group(1) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private String exeDe(String nombre) {
        String n = norm(nombre);
        String exe = propios.getOrDefault(n, PROGRAMAS.get(n));
        return exe != null && permitidoComoPrograma(exe) ? exe : null;
    }

    static URI uriSegura(String v) {
        if (v == null) {
            return null;
        }
        String s = v.strip();
        if (!s.matches("(?i)https?://.*") || s.chars().anyMatch(c -> c < 32 || c == ' ')) {
            return null;
        }
        try {
            URI u = new URI(s);
            if (u.getHost() == null || u.getUserInfo() != null) {
                return null;
            }
            return u;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    private static String norm(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).strip();
    }
}
