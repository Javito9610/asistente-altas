package es.amagna.asistente.estados;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/** Cuaderno de lo que el asistente ha tenido que arreglar solo (registro\recuperaciones.tsv): para ver qué se repite y convertirlo en regla. */
public final class Aprendizaje {
    private Aprendizaje() {
    }

    public static void anotar(String que) {
        try {
            Path f = Path.of("registro", "recuperaciones.tsv");
            Files.createDirectories(f.getParent());
            Files.writeString(f, LocalDateTime.now().withNano(0) + "\t" + que.replace('\t', ' ') + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // el cuaderno nunca debe romper una tarea
        }
    }
}
