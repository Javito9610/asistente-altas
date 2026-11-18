package es.amagna.asistente.altas;

import java.io.IOException;

/** Corta páginas de un PDF. Es una interfaz para poder probar la lógica sin PDFs de verdad. */
public interface DivisorPdf {

    /** Cuántas páginas tiene ese PDF. */
    int paginas(byte[] pdf) throws IOException;

    /** Un PDF nuevo con solo esa página (la primera es la 1). */
    byte[] pagina(byte[] pdf, int numero) throws IOException;
}
