package es.amagna.asistente.estados;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;

/**
 * El diario de tareas hechas: una línea por tarea terminada, con su clave. Es lo que impide repetir una tarea ya hecha
 * (dos expedientes iguales) aunque el asistente se reinicie o alguien lance el mismo trabajo dos veces.
 *
 * La clave la elige quien manda la tarea (por ejemplo NIF + sociedad + descripción). Sin clave no hay protección.
 */
public final class Diario {
    private final Path fichero;

    public Diario(Path fichero) {
        this.fichero = fichero;
    }

    public boolean yaHecha(String clave) throws IOException {
        if (clave == null || clave.isBlank() || !Files.exists(fichero)) {
            return false;
        }
        List<String> lineas = Files.readAllLines(fichero, StandardCharsets.UTF_8);
        return lineas.stream().anyMatch(l -> l.split("\\t", -1).length > 1 && l.split("\\t", -1)[1].equals(clave));
    }

    /** Apunta la tarea como hecha: fecha, clave, tarea y lo que salió (el número del expediente). */
    public void hecha(String clave, String tarea, String resultado) throws IOException {
        if (clave == null || clave.isBlank()) {
            return;
        }
        Files.createDirectories(fichero.toAbsolutePath().getParent());
        String limpio = (LocalDateTime.now().withNano(0) + "\t" + clave + "\t" + tarea + "\t" + resultado)
                .replace("\r", " ").replace("\n", " ") + "\n";
        Files.writeString(fichero, limpio, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
