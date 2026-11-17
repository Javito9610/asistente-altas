package es.amagna.asistente.manos;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * La carpeta de recortes que el asistente va aprendiendo de este ordenador (tema, escala, versión de MN).
 *
 * <p>Se aprende sola: la primera vez que un paso hace clic en un sitio, se guarda un recorte como <i>pendiente</i>;
 * solo cuando la tarea entera termina bien (con sus verificaciones) los pendientes pasan a valer. Así nunca se aprende
 * el dibujo de un clic que cayó donde no debía. Los recortes son de esta máquina, no se suben a git.
 */
public final class Anclas {
    private final Path carpeta;
    private final List<Path> pendientes = new ArrayList<>();

    public Anclas(Path carpeta) {
        this.carpeta = carpeta;
    }

    private static Anclas actual;

    /** Null = anclas desactivadas (los tests, o quien no quiera usarlas). */
    public static Anclas actual() {
        return actual;
    }

    public static void activar(Path carpeta) {
        actual = new Anclas(carpeta);
    }

    public static void desactivar() {
        actual = null;
    }

    static String nombre(String ventana, int relX, int relY) {
        String v = ventana == null ? "" : ventana.replaceAll("[^A-Za-z0-9]+", "_");
        return v + "@" + relX + "_" + relY + ".png";
    }

    public BufferedImage leer(String ventana, int relX, int relY) {
        Path p = carpeta.resolve(nombre(ventana, relX, relY));
        try {
            return Files.isRegularFile(p) ? ImageIO.read(p.toFile()) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Guarda como pendiente (no vale hasta {@link #confirmar()}). No hace nada si ya hay uno confirmado. */
    public synchronized void aprender(String ventana, int relX, int relY, BufferedImage recorte) {
        if (recorte == null || !Ancla.hayContraste(recorte)) {
            return;
        }
        try {
            Files.createDirectories(carpeta);
            Path definitivo = carpeta.resolve(nombre(ventana, relX, relY));
            if (Files.exists(definitivo)) {
                return;
            }
            Path pendiente = carpeta.resolve(definitivo.getFileName() + ".pendiente");
            ImageIO.write(recorte, "png", pendiente.toFile());
            if (!pendientes.contains(pendiente)) {
                pendientes.add(pendiente);
            }
        } catch (IOException ignorada) {
            // no se aprende, no pasa nada: se seguirá con la posición de siempre
        }
    }

    /** La tarea terminó bien y verificada: lo aprendido vale. Devuelve cuántos recortes nuevos. */
    public synchronized int confirmar() {
        int n = 0;
        for (Path p : pendientes) {
            try {
                String f = p.getFileName().toString();
                Files.move(p, p.resolveSibling(f.substring(0, f.length() - ".pendiente".length())),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                n++;
            } catch (IOException ignorada) {
                // se queda pendiente
            }
        }
        pendientes.clear();
        return n;
    }

    /** La tarea no terminó bien: lo pendiente se olvida. */
    public synchronized void descartar() {
        for (Path p : pendientes) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignorada) {
                // un .pendiente suelto no se lee nunca
            }
        }
        pendientes.clear();
    }

    /** Para cuando un recorte confirmado ya no casa (MN cambió): se borra y se vuelve a aprender. */
    public void olvidar(String ventana, int relX, int relY) {
        try {
            Files.deleteIfExists(carpeta.resolve(nombre(ventana, relX, relY)));
        } catch (IOException ignorada) {
            // sin más
        }
    }
}
