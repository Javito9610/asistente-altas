package es.amagna.asistente.jarvis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * El canal más sencillo y a prueba de todo: se le deja un fichero de texto en datos\\buzon\\entrada\\ y la respuesta de Jarvis
 * se va añadiendo a datos\\buzon\\salida\\mismo-nombre.txt. Sirve para scripts, para otros programas (un puente de WhatsApp,
 * Power Automate…) y para probar Jarvis de punta a punta sin Telegram. Quien lo use es siempre el usuario «local».
 */
public final class CanalCarpeta {
    private final Path entrada;
    private final Path salida;

    public CanalCarpeta(Path raiz) {
        this.entrada = raiz.resolve("entrada");
        this.salida = raiz.resolve("salida");
    }

    /** Recoge los ficheros nuevos (los borra al leerlos). Devuelve cuántos. */
    public int sondear(BiConsumer<String, String> alRecibir) {
        try {
            Files.createDirectories(entrada);
            Files.createDirectories(salida);
            List<Path> ficheros;
            try (Stream<Path> s = Files.list(entrada)) {
                ficheros = s.filter(p -> p.getFileName().toString().endsWith(".txt")).sorted().toList();
            }
            int n = 0;
            for (Path p : ficheros) {
                String nombre = p.getFileName().toString().replaceAll("\\.txt$", "");
                String texto = Files.readString(p, StandardCharsets.UTF_8);
                Files.move(p, entrada.resolve(nombre + ".leido"), StandardCopyOption.REPLACE_EXISTING);
                alRecibir.accept(nombre, texto.strip());
                n++;
            }
            return n;
        } catch (IOException e) {
            return -1;
        }
    }

    /** La respuesta se añade al fichero de salida que lleva el nombre del de entrada. */
    public void enviar(String nombre, String texto) {
        try {
            Files.createDirectories(salida);
            Files.writeString(salida.resolve(nombre.replaceAll("[^0-9A-Za-z._-]", "_") + ".txt"), texto + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // sin sitio donde escribir: se queda en el registro
        }
    }
}
