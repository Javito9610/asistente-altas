package es.amagna.asistente;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * La bandeja de ZIP de los clientes. En este ordenador es una carpeta de pruebas. En el PC de trabajo se cambia
 * una sola clave de {@code entrada.json} (la de Documentación Cloud); no hace falta tocar código.
 * {@code --carpeta} vale solo para esa ejecución.
 * <p>
 * {@link #ver} mira el bloque de documentación, sin leer el interior de los PDF: como mucho
 * cliente / [entidad /] fichero. Un solo ZIP interior, y su nombre es la entidad. Más hondo, otro ZIP dentro,
 * cifrado o una ruta que se sale: se rechaza el bloque entero.
 */
public final class Zips {
    /** Clave de {@code entrada.json} con la ruta de la bandeja. */
    public static final String CLAVE = "carpeta_zips";

    /** Si no hay fichero o no trae la clave, se usa esta carpeta de pruebas. */
    public static final String CARPETA_POR_DEFECTO = "C:\\Proyectos\\asistente-altas\\pruebas\\zips";

    /** cliente / entidad / fichero. Un tramo más y el ZIP entero no vale. */
    private static final int NIVELES = 3;

    private static final List<String> EXTENSIONES = List.of("pdf", "jpg", "jpeg", "png", "webp", "heic", "txt");
    private static final Set<String> CUENTAN = Set.copyOf(EXTENSIONES);

    /** El vacío queda el último: es «(sin carpeta)» o «(sin entidad)», no un nombre. */
    private static final Comparator<String> POR_NOMBRE = (a, b) -> {
        if (a.isEmpty() != b.isEmpty()) {
            return a.isEmpty() ? 1 : -1;
        }
        int c = String.CASE_INSENSITIVE_ORDER.compare(a, b);
        return c != 0 ? c : a.compareTo(b);
    };

    private Zips() {
    }

    /** La carpeta de esta ejecución: {@code --carpeta} manda; si no, {@code entrada.json}; si no, la de pruebas. */
    public static Path carpeta(List<String> args) {
        return carpeta(Path.of("entrada.json"), args);
    }

    static Path carpeta(Path config, List<String> args) {
        if (args.contains("--carpeta")) {
            int i = args.indexOf("--carpeta");
            if (i + 1 >= args.size() || args.get(i + 1).isBlank()) {
                throw new IllegalArgumentException("Falta la ruta después de --carpeta. Ejemplo: asistente zips --carpeta C:\\ruta");
            }
            return Path.of(args.get(i + 1).strip());
        }
        if (!Files.isRegularFile(config)) {
            return Path.of(CARPETA_POR_DEFECTO);
        }
        String texto;
        try {
            texto = Files.readString(config, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No se ha podido leer " + config.toAbsolutePath() + ": " + e.getMessage(), e);
        }
        Map<String, Object> m = Json.objeto(texto);
        Object v = m.get(CLAVE);
        if (v != null && !String.valueOf(v).isBlank()) {
            return Path.of(String.valueOf(v).strip());
        }
        return Path.of(CARPETA_POR_DEFECTO);
    }

    /**
     * La lista y, si toca, el árbol de uno. {@code --ver nombre.zip} abre ese.
     * Si no se pide ninguno y en la carpeta solo hay un .zip, se abre ese.
     * Con varios y sin {@code --ver}, solo la lista: no se abre ninguno.
     */
    public static String informe(Path carpeta, List<String> args) {
        String lista = listar(carpeta);
        if (args.contains("--ver")) {
            String nombre = nombreVer(args);
            Path z = buscar(carpeta, nombre);
            if (z == null) {
                return lista + "\n\nNo está «" + nombre + "» en " + carpeta.toAbsolutePath().normalize() + ".";
            }
            return lista + "\n\n" + ver(z);
        }
        List<Path> zs = rutasSeguras(carpeta);
        if (zs.size() == 1) {
            return lista + "\n\n" + ver(zs.get(0));
        }
        return lista;
    }

    /** Nombre y tamaño de cada .zip. No los abre. Si la carpeta no está o no hay ninguno, lo dice y no falla. */
    public static String listar(Path carpeta) {
        Path p = carpeta.toAbsolutePath().normalize();
        if (!Files.exists(p)) {
            return "No existe la carpeta de ZIPs: " + p;
        }
        if (!Files.isDirectory(p)) {
            return "La ruta de ZIPs no es una carpeta: " + p;
        }
        List<Path> zips;
        try {
            zips = rutas(p);
        } catch (IOException e) {
            return "No se ha podido leer la carpeta de ZIPs: " + p + " (" + e.getMessage() + ")";
        }
        if (zips.isEmpty()) {
            return "La carpeta de ZIPs está vacía: no hay ningún .zip en " + p + ".";
        }
        StringBuilder b = new StringBuilder();
        b.append("ZIPs en ").append(p).append(" (").append(zips.size()).append("):");
        for (Path z : zips) {
            b.append('\n').append("  ").append(z.getFileName()).append(" · ");
            try {
                b.append(Files.size(z)).append(" bytes");
            } catch (IOException e) {
                b.append("no se ha podido leer el tamaño (").append(e.getMessage()).append(')');
            }
        }
        return b.toString();
    }

    /**
     * El árbol del bloque: cliente, entidad (o «(sin entidad)») y ficheros.
     * Si no vale, dice por qué. No lanza por un ZIP mal formado.
     */
    public static String ver(Path zip) {
        String nombre = zip.getFileName() == null ? zip.toString() : zip.getFileName().toString();
        Path p = zip.toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) {
            return "No está «" + nombre + "».";
        }
        try (ZipFile z = new ZipFile(p.toFile(), StandardCharsets.UTF_8)) {
            return leer(nombre, z);
        } catch (ZipException e) {
            if (esCifrado(e)) {
                return noSeAbre(nombre, "está cifrado");
            }
            return noSeAbre(nombre, "no es un ZIP válido (" + mensaje(e) + ")");
        } catch (IOException e) {
            if (esCifrado(e)) {
                return noSeAbre(nombre, "está cifrado");
            }
            return noSeAbre(nombre, "no se ha podido abrir (" + mensaje(e) + ")");
        }
    }

    private static String leer(String nombreZip, ZipFile zip) {
        try {
            List<ZipEntry> utiles = new ArrayList<>();
            String marca = null;
            for (ZipEntry e : Collections.list(zip.entries())) {
                Ruta ruta = Ruta.de(e.getName());
                if (ruta.problema != null) {
                    return noSeAbre(nombreZip, ruta.problema);
                }
                if (ruta.segmentos.isEmpty() || basura(ruta.segmentos)) {
                    continue;
                }
                String m = marcaDeSalida(ruta.segmentos);
                if ("propuesta.json".equals(m)) {
                    marca = m;
                } else if (m != null && marca == null) {
                    marca = m;
                }
                utiles.add(e);
            }
            if (marca != null) {
                return "Dentro de «" + nombreZip + "»:\n  Esto es el paquete de alta que sale (lleva «" + marca
                        + "»), no un bloque de documentación.";
            }
            for (ZipEntry e : Collections.list(zip.entries())) {
                if (e.isDirectory()) {
                    continue;
                }
                String cifrado = asegurar(zip, e);
                if (cifrado != null) {
                    return noSeAbre(nombreZip, cifrado);
                }
            }
            Map<String, Map<String, List<String>>> arbol = new LinkedHashMap<>();
            List<ZipEntry> internos = new ArrayList<>();
            for (ZipEntry e : utiles) {
                String problema = clasificar(e, Ruta.de(e.getName()), arbol, internos);
                if (problema != null) {
                    return noSeAbre(nombreZip, problema);
                }
            }
            if (internos.size() > 1) {
                return noSeAbre(nombreZip, "solo se admite un ZIP dentro, y hay " + internos.size() + " (" + nombresDe(internos) + ")");
            }
            if (internos.size() == 1) {
                String problema = abrirInterior(zip, internos.get(0), arbol);
                if (problema != null) {
                    if (problema.startsWith("PAQUETE:")) {
                        return "Dentro de «" + nombreZip + "»:\n  Esto es el paquete de alta que sale (lleva «"
                                + problema.substring("PAQUETE:".length()) + "»), no un bloque de documentación.";
                    }
                    return noSeAbre(nombreZip, problema);
                }
            }
            if (arbol.isEmpty()) {
                return "Dentro de «" + nombreZip + "»:\n  No hay ficheros pdf, jpg, jpeg, png, webp, heic ni txt.";
            }
            return pintar(nombreZip, arbol);
        } catch (IOException e) {
            if (esCifrado(e)) {
                return noSeAbre(nombreZip, "está cifrado");
            }
            return noSeAbre(nombreZip, "no se ha podido leer (" + mensaje(e) + ")");
        }
    }

    /** Un fichero cuenta, se ignora, o tira el bloque. Los ZIP de entidad se apartan para abrirlos luego. */
    private static String clasificar(ZipEntry entry, Ruta ruta, Map<String, Map<String, List<String>>> arbol, List<ZipEntry> internos) {
        List<String> s = ruta.segmentos;
        boolean directorio = entry.isDirectory() || entry.getName().endsWith("/") || entry.getName().endsWith("\\");
        if (directorio) {
            if (s.size() > NIVELES - 1) {
                return "pasa de " + NIVELES + " niveles (" + entry.getName() + ")";
            }
            return null;
        }
        if (s.size() > NIVELES) {
            return "pasa de " + NIVELES + " niveles (" + entry.getName() + ")";
        }
        String ultimo = s.get(s.size() - 1);
        if (esZip(ultimo)) {
            // En la entidad (3.er tramo) el ZIP ya sería un 4.º nivel al abrirlo.
            if (s.size() > NIVELES - 1) {
                return "pasa de " + NIVELES + " niveles (" + entry.getName() + ")";
            }
            internos.add(entry);
            return null;
        }
        if (!cuenta(ultimo)) {
            return null;
        }
        if (s.size() == 1) {
            añadir(arbol, "", "", ultimo);
        } else if (s.size() == 2) {
            añadir(arbol, s.get(0), "", ultimo);
        } else {
            añadir(arbol, s.get(0), s.get(1), ultimo);
        }
        return null;
    }

    /**
     * El ZIP interior ocupa el hueco de la entidad: sus ficheros tienen que estar en la raíz.
     * Otro ZIP ahí dentro no se admite.
     */
    private static String abrirInterior(ZipFile zip, ZipEntry entry, Map<String, Map<String, List<String>>> arbol) throws IOException {
        Ruta exterior = Ruta.de(entry.getName());
        String cliente = exterior.segmentos.size() == 1 ? "" : exterior.segmentos.get(0);
        String entidad = sinZip(exterior.segmentos.get(exterior.segmentos.size() - 1));
        byte[] datos;
        try (InputStream in = zip.getInputStream(entry)) {
            datos = in.readAllBytes();
        } catch (ZipException e) {
            if (esCifrado(e)) {
                return "está cifrado";
            }
            return "el ZIP interior «" + entry.getName() + "» no se puede abrir (" + mensaje(e) + ")";
        }
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(datos), StandardCharsets.UTF_8)) {
            for (ZipEntry ie = zin.getNextEntry(); ie != null; ie = zin.getNextEntry()) {
                Ruta ruta = Ruta.de(ie.getName());
                if (ruta.problema != null) {
                    return ruta.problema;
                }
                if (ruta.segmentos.isEmpty() || basura(ruta.segmentos)) {
                    zin.closeEntry();
                    continue;
                }
                String marca = marcaDeSalida(ruta.segmentos);
                if (marca != null) {
                    return "PAQUETE:" + marca;
                }
                if (ruta.segmentos.stream().anyMatch(Zips::esZip)) {
                    return "hay un ZIP dentro del ZIP interior («" + entry.getName() + "» → " + ie.getName() + ")";
                }
                boolean directorio = ie.isDirectory() || ie.getName().endsWith("/") || ie.getName().endsWith("\\");
                if (directorio || ruta.segmentos.size() != 1) {
                    return "pasa de " + NIVELES + " niveles (" + entry.getName() + " → " + ie.getName() + ")";
                }
                if (cuenta(ruta.segmentos.get(0))) {
                    añadir(arbol, cliente, entidad, ruta.segmentos.get(0));
                }
                zin.closeEntry();
            }
        } catch (ZipException e) {
            if (esCifrado(e)) {
                return "está cifrado";
            }
            return "el ZIP interior «" + entry.getName() + "» no se puede abrir (" + mensaje(e) + ")";
        }
        return null;
    }

    private static String pintar(String nombreZip, Map<String, Map<String, List<String>>> arbol) {
        StringBuilder b = new StringBuilder();
        b.append("Dentro de «").append(nombreZip).append("»:");
        List<String> clientes = new ArrayList<>(arbol.keySet());
        clientes.sort(POR_NOMBRE);
        for (String cliente : clientes) {
            b.append("\n  cliente «").append(cliente.isEmpty() ? "(sin carpeta)" : cliente).append('»');
            List<String> entidades = new ArrayList<>(arbol.get(cliente).keySet());
            entidades.sort(POR_NOMBRE);
            for (String entidad : entidades) {
                b.append("\n    entidad «").append(entidad.isEmpty() ? "(sin entidad)" : entidad).append('»');
                List<String> ficheros = new ArrayList<>(new LinkedHashSet<>(arbol.get(cliente).get(entidad)));
                ficheros.sort(String.CASE_INSENSITIVE_ORDER);
                for (String fichero : ficheros) {
                    b.append("\n      ").append(fichero);
                }
            }
        }
        return b.toString();
    }

    private static void añadir(Map<String, Map<String, List<String>>> arbol, String cliente, String entidad, String fichero) {
        arbol.computeIfAbsent(cliente, k -> new LinkedHashMap<>())
                .computeIfAbsent(entidad, k -> new ArrayList<>())
                .add(fichero);
    }

    /** Abre la entrada. Si está cifrada, el JDK lo dice aquí, antes de soltar el documento. */
    private static String asegurar(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return null;
        } catch (IOException e) {
            if (esCifrado(e)) {
                return "está cifrado";
            }
            throw e;
        }
    }

    private static boolean esCifrado(IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return m.contains("encrypt") || m.contains("cifr");
    }

    private static boolean esZip(String nombre) {
        return extension(nombre).equals("zip");
    }

    private static boolean cuenta(String nombre) {
        return CUENTAN.contains(extension(nombre));
    }

    private static String extension(String nombre) {
        int i = nombre.lastIndexOf('.');
        if (i <= 0 || i == nombre.length() - 1) {
            return "";
        }
        return nombre.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static String sinZip(String nombre) {
        return esZip(nombre) ? nombre.substring(0, nombre.length() - 4) : nombre;
    }

    /** Basura de macOS y de Windows: no es documentación y no cuenta para el fondo. */
    private static boolean basura(List<String> segmentos) {
        for (String s : segmentos) {
            if (s.equalsIgnoreCase("__MACOSX") || s.equalsIgnoreCase(".DS_Store")
                    || s.equalsIgnoreCase("Thumbs.db") || s.equalsIgnoreCase("desktop.ini")
                    || s.startsWith("._")) {
                return true;
            }
        }
        return false;
    }

    /**
     * El paquete que el asistente escribe al terminar (propuesta, ficha, E1) no es un bloque de entrada.
     * El nombre del fichero da igual: no hace falta que diga DNI ni USURA.
     */
    private static String marcaDeSalida(List<String> segmentos) {
        for (String s : segmentos) {
            if (s.equalsIgnoreCase("propuesta.json")) {
                return "propuesta.json";
            }
            String l = s.toLowerCase(Locale.ROOT);
            if (l.equals("00 ficha cliente") || l.startsWith("00 ficha cliente")) {
                return "00 Ficha cliente";
            }
            if (s.matches("(?i)E\\d+\\s+-.*")) {
                return s;
            }
        }
        return null;
    }

    private static String nombresDe(List<ZipEntry> entradas) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < entradas.size(); i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(entradas.get(i).getName());
        }
        return b.toString();
    }

    private static String noSeAbre(String nombreZip, String motivo) {
        String m = motivo.strip();
        if (!m.endsWith(".")) {
            m = m + ".";
        }
        return "Dentro de «" + nombreZip + "»:\n  No se abre: " + m;
    }

    private static String mensaje(Throwable e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        m = m.replace('\r', ' ').replace('\n', ' ').strip();
        return m.length() > 180 ? m.substring(0, 180) : m;
    }

    private static String nombreVer(List<String> args) {
        int i = args.indexOf("--ver");
        if (i < 0 || i + 1 >= args.size() || args.get(i + 1).isBlank() || args.get(i + 1).startsWith("--")) {
            throw new IllegalArgumentException("Falta el nombre después de --ver. Ejemplo: asistente zips --ver cliente.zip");
        }
        return args.get(i + 1).strip();
    }

    /** Solo un .zip de la propia bandeja, por su nombre. Una ruta que se sale no se abre. */
    private static Path buscar(Path carpeta, String nombre) {
        Path base = carpeta.toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            return null;
        }
        Path destino = base.resolve(nombre).normalize();
        if (!destino.startsWith(base) || destino.getParent() == null || !destino.getParent().equals(base)) {
            return null;
        }
        return Files.isRegularFile(destino) ? destino : null;
    }

    private static List<Path> rutasSeguras(Path carpeta) {
        try {
            Path p = carpeta.toAbsolutePath().normalize();
            if (!Files.isDirectory(p)) {
                return List.of();
            }
            return rutas(p);
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<Path> rutas(Path carpeta) throws IOException {
        try (var stream = Files.list(carpeta)) {
            return stream
                    .filter(f -> Files.isRegularFile(f) && f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                    .sorted(Comparator.comparing(f -> f.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    /** Trozo de ruta ya comprobado, o el motivo por el que el ZIP entero no vale. */
    private static final class Ruta {
        final List<String> segmentos;
        final String problema;

        private Ruta(List<String> segmentos, String problema) {
            this.segmentos = segmentos;
            this.problema = problema;
        }

        static Ruta de(String nombre) {
            String n = nombre.replace('\\', '/');
            if (n.startsWith("/") || unidad(n)) {
                return new Ruta(List.of(), "la ruta se sale de la carpeta (" + nombre + ")");
            }
            List<String> segs = new ArrayList<>();
            for (String t : n.split("/", -1)) {
                if (t.isEmpty() || t.equals(".")) {
                    continue;
                }
                if (t.equals("..") || t.indexOf('\0') >= 0) {
                    return new Ruta(List.of(), "la ruta se sale de la carpeta (" + nombre + ")");
                }
                segs.add(t);
            }
            return new Ruta(segs, null);
        }

        private static boolean unidad(String n) {
            return n.length() >= 2 && n.charAt(1) == ':' && Character.isLetter(n.charAt(0));
        }
    }
}
