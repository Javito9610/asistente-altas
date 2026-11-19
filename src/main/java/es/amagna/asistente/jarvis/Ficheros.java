package es.amagna.asistente.jarvis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Las manos de Jarvis para ficheros, sin pantalla: listar, leer, buscar, escribir, copiar y mover, SOLO dentro de las
 * carpetas permitidas (datos\\carpetas.txt; por defecto, trabajo\\jarvis). Nada se borra (lo que sobra se manda a _papelera
 * dentro de la misma carpeta), nada se sobrescribe sin decirlo, y no se escriben ejecutables ni scripts.
 */
public final class Ficheros {
    static final long MAX_LEER = 200_000;
    static final List<String> PROHIBIDAS = List.of(".exe", ".dll", ".bat", ".cmd", ".ps1", ".vbs", ".js", ".msi", ".scr", ".jar", ".lnk", ".hta", ".reg", ".sys");

    private final List<Path> raices;
    /** Ficheros que hay que entregar a la persona al acabar el encargo (los recién creados y los que pida «enviar_fichero»). */
    private final List<Path> aEntregar = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final long MAX_ENTREGA = 45_000_000;

    public Ficheros(List<Path> raices) {
        this.raices = raices.stream().map(r -> r.toAbsolutePath().normalize()).toList();
    }

    public List<Path> raices() {
        return raices;
    }

    /** La ruta ya normalizada si cae dentro de una carpeta permitida; si no, lanza con el motivo. */
    Path dentro(String ruta) {
        if (ruta == null || ruta.isBlank()) {
            throw new IllegalArgumentException("Falta la ruta.");
        }
        Path p = Path.of(ruta).toAbsolutePath().normalize();
        for (Path r : raices) {
            if (p.startsWith(r)) {
                // Que un enlace simbólico no saque de la carpeta.
                try {
                    Path real = Files.exists(p) ? p.toRealPath() : p.getParent() != null && Files.exists(p.getParent()) ? p.getParent().toRealPath().resolve(p.getFileName()) : p;
                    if (!real.startsWith(r.toRealPath())) {
                        break;
                    }
                } catch (IOException e) {
                    break;
                }
                return p;
            }
        }
        throw new IllegalArgumentException("NO PERMITIDO: «" + ruta + "» está fuera de las carpetas con las que puedo trabajar ("
                + raices.stream().map(Path::toString).collect(Collectors.joining(", ")) + ").");
    }

    private static void nombreSeguro(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : PROHIBIDAS) {
            if (n.endsWith(ext)) {
                throw new IllegalArgumentException("NO PERMITIDO: no se crean ejecutables ni scripts («" + n + "»).");
            }
        }
    }

    public String listar(String ruta) throws IOException {
        Path p = dentro(ruta);
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException("No es una carpeta: " + ruta);
        }
        try (Stream<Path> s = Files.list(p)) {
            var l = s.sorted().limit(300).map(x -> (Files.isDirectory(x) ? "[carpeta] " : "") + x.getFileName()
                    + (Files.isRegularFile(x) ? "  (" + tam(x) + ")" : "")).toList();
            return l.isEmpty() ? "(vacía)" : String.join("\n", l);
        }
    }

    public String leer(String ruta) throws IOException {
        Path p = dentro(ruta);
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("No es un fichero: " + ruta);
        }
        long t = Files.size(p);
        byte[] b = Files.readAllBytes(p);
        String txt = new String(b, 0, (int) Math.min(t, MAX_LEER), StandardCharsets.UTF_8);
        return txt + (t > MAX_LEER ? "\n[… cortado: el fichero tiene " + t + " bytes y solo se leen " + MAX_LEER + "]" : "");
    }

    public String buscar(String carpeta, String trozoDelNombre) throws IOException {
        Path p = dentro(carpeta);
        String q = trozoDelNombre.toLowerCase(Locale.ROOT);
        try (Stream<Path> s = Files.walk(p, 6)) {
            var l = s.filter(x -> x.getFileName() != null && x.getFileName().toString().toLowerCase(Locale.ROOT).contains(q)).limit(100)
                    .map(x -> p.relativize(x).toString()).toList();
            return l.isEmpty() ? "(no hay nada con ese nombre)" : String.join("\n", l);
        }
    }

    public String escribir(String ruta, String texto, boolean sobrescribir) throws IOException {
        Path p = dentro(ruta);
        nombreSeguro(p);
        if (Files.exists(p) && !sobrescribir) {
            throw new IllegalArgumentException("Ya existe «" + ruta + "». Si quieres reemplazarlo, dilo (sobrescribir=true).");
        }
        if (Files.exists(p)) {
            papelera(p); // lo anterior no se pierde
        }
        Files.createDirectories(p.getParent());
        Files.writeString(p, texto, StandardCharsets.UTF_8);
        aEntregar.add(p);
        return "Escrito «" + ruta + "» (" + texto.length() + " caracteres).";
    }

    /** Guarda un fichero que ha mandado la persona, en «recibidos» de la primera carpeta (con la hora delante; no se le devuelve). Devuelve la ruta. */
    public String recibir(String nombre, byte[] datos) throws IOException {
        Path carpeta = raices.get(0).resolve("recibidos");
        Path p = carpeta.resolve(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + nombre);
        dentro(p.toString());
        nombreSeguro(p);
        Files.createDirectories(carpeta);
        Files.write(p, datos);
        return p.toString();
    }

    /** Igual que {@link #escribir} pero para ficheros binarios (PDF, Word, Excel…). */
    public String escribirBytes(String ruta, byte[] datos, boolean sobrescribir) throws IOException {
        Path p = dentro(ruta);
        nombreSeguro(p);
        if (Files.exists(p) && !sobrescribir) {
            throw new IllegalArgumentException("Ya existe «" + ruta + "». Si quieres reemplazarlo, dilo (sobrescribir=true).");
        }
        if (Files.exists(p)) {
            papelera(p);
        }
        Files.createDirectories(p.getParent());
        Files.write(p, datos);
        aEntregar.add(p);
        return "Creado «" + ruta + "» (" + tam(p) + ").";
    }

    /** Pide que un fichero (existente, dentro de las carpetas permitidas) se le entregue a la persona al terminar. */
    public String entregar(String ruta) throws IOException {
        Path p = dentro(ruta);
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("No es un fichero: " + ruta);
        }
        if (Files.size(p) > MAX_ENTREGA) {
            throw new IllegalArgumentException("Pesa demasiado para mandarlo (máximo 45 MB).");
        }
        nombreSeguro(p);
        String bajo = p.toString().toLowerCase(Locale.ROOT).replace('\\', '/');
        String nom = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (bajo.contains("/secretos/") || bajo.contains("/.ssh/") || bajo.contains("/.git/") || List.of("clave.txt", "mn.dat", "autorizados.txt").contains(nom)
                || List.of(".dat", ".key", ".pem", ".pfx", ".p12", ".kdbx", ".env").stream().anyMatch(nom::endsWith)) {
            throw new IllegalArgumentException("NO PERMITIDO: ese fichero guarda claves o secretos y no se manda por el chat.");
        }
        if (!aEntregar.contains(p)) {
            aEntregar.add(p);
        }
        return "Te lo mando al terminar: «" + p.getFileName() + "».";
    }

    /** Los ficheros pendientes de entregar; los quita de la lista. */
    public List<Path> drenarEntregas() {
        List<Path> r = new java.util.ArrayList<>(aEntregar);
        aEntregar.removeAll(r);
        return r;
    }

    /** La ruta, ya comprobada, para quien necesite leer un fichero suyo (documentos de oficina). */
    public Path rutaPermitida(String ruta) {
        return dentro(ruta);
    }

    public String copiar(String origen, String destino) throws IOException {
        Path o = dentro(origen);
        Path d = dentro(destino);
        nombreSeguro(d);
        if (Files.exists(d)) {
            throw new IllegalArgumentException("Ya existe el destino: no se sobrescribe.");
        }
        Files.createDirectories(d.getParent());
        Files.copy(o, d);
        return "Copiado.";
    }

    public String mover(String origen, String destino) throws IOException {
        Path o = dentro(origen);
        Path d = dentro(destino);
        nombreSeguro(d);
        if (Files.exists(d)) {
            throw new IllegalArgumentException("Ya existe el destino: no se sobrescribe.");
        }
        Files.createDirectories(d.getParent());
        Files.move(o, d);
        return "Movido.";
    }

    /** «Borrar» = mandar a _papelera (en la carpeta permitida), con un número si el nombre ya está. */
    public String aLaPapelera(String ruta) throws IOException {
        Path p = dentro(ruta);
        if (!Files.exists(p)) {
            throw new IllegalArgumentException("No existe: " + ruta);
        }
        papelera(p);
        return "Mandado a la papelera.";
    }

    private void papelera(Path p) throws IOException {
        Path raiz = raices.stream().filter(p::startsWith).findFirst().orElseThrow();
        Path pap = raiz.resolve("_papelera");
        Files.createDirectories(pap);
        Path d = pap.resolve(p.getFileName());
        for (int i = 2; Files.exists(d); i++) {
            d = pap.resolve(i + "-" + p.getFileName());
        }
        Files.move(p, d, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String tam(Path p) {
        try {
            long t = Files.size(p);
            return t < 1024 ? t + " B" : t < 1_048_576 ? t / 1024 + " KB" : t / 1_048_576 + " MB";
        } catch (IOException e) {
            return "?";
        }
    }
}
