package es.amagna.asistente.jarvis;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.imageio.ImageIO;

/** Una captura de toda la pantalla, guardada en la carpeta de capturas (para mandarla por el chat). */
public final class Captura {
    private final Path carpeta;

    public Captura(Path carpeta) {
        this.carpeta = carpeta;
    }

    public Path hacer() {
        try {
            Files.createDirectories(carpeta);
            // Todas las pantallas juntas (si hay más de un monitor), no solo la principal.
            Rectangle pantalla = new Rectangle();
            for (var d : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                pantalla = pantalla.union(d.getDefaultConfiguration().getBounds());
            }
            if (pantalla.isEmpty()) {
                pantalla = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            }
            BufferedImage img = new Robot().createScreenCapture(pantalla);
            Path f = carpeta.resolve("captura-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png");
            ImageIO.write(img, "png", f.toFile());
            return f;
        } catch (IOException | java.awt.AWTException | RuntimeException e) {
            throw new IllegalStateException(String.valueOf(e.getMessage()), e);
        }
    }
}
