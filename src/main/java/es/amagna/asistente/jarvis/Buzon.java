package es.amagna.asistente.jarvis;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Un buzón de correo: da igual si por dentro es IMAP/SMTP (Gmail, etc.) o Microsoft 365/Outlook. */
public interface Buzon {
    /** La dirección desde la que se envía. */
    String direccion();

    /** Envía. Solo se llama tras la confirmación de la persona sobre este mismo texto. */
    void enviar(List<String> para, String asunto, String texto, List<Path> adjuntos) throws IOException;

    /** Los últimos correos sin leer (máximo 10), sin marcarlos como leídos. */
    List<Correo.Mensaje> noLeidos(int max) throws IOException;
}
