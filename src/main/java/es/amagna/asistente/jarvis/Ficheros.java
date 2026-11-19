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
        return "Escrito «" + ruta + "» (" + texto.length() + " caracteres).";
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
        return "Creado «" + ruta + "» (" + tam(p) + ").";
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
