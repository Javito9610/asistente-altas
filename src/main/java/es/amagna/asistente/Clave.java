package es.amagna.asistente;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dónde está la clave de la API de Claude (nunca en el código ni en el repositorio):
 *  1. la variable de entorno ANTHROPIC_API_KEY;
 *  2. el fichero %LOCALAPPDATA%\AsistenteAltas\clave.txt (solo lo lee tu usuario de Windows).
 * Más adelante (M7) irá al Administrador de credenciales de Windows, cifrada.
 */
public final class Clave {
    private Clave() {
    }

    public static Path fichero() {
        String base = System.getenv("LOCALAPPDATA");
        return Path.of(base != null ? base : System.getProperty("user.home"), "AsistenteAltas", "clave.txt");
    }

    /** ¿Hay clave guardada? (para decidir si se puede contar con la IA, sin reventar si no la hay). */
    public static boolean hay() {
        String env = System.getenv("ANTHROPIC_API_KEY");
        return env != null && !env.isBlank() || java.nio.file.Files.isRegularFile(fichero());
    }

    public static String leer() {
        String env = System.getenv("ANTHROPIC_API_KEY");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        Path f = fichero();
        try {
            if (Files.isRegularFile(f)) {
                String k = Files.readString(f).trim();
                if (!k.isEmpty()) {
                    return k;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("No se puede leer " + f + ": " + e.getMessage(), e);
        }
        throw new IllegalStateException("No hay clave de la API. Guárdala con:  asistente guardar-clave");
    }

    public static void guardar(String clave) throws IOException {
        Path f = fichero();
        Files.createDirectories(f.getParent());
        Files.writeString(f, clave.trim());
    }
}
