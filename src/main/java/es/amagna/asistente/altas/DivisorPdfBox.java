package es.amagna.asistente.altas;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

/** El divisor de verdad, con Apache PDFBox (el jar pdfbox-app que descarga asistente.cmd). */
public final class DivisorPdfBox implements DivisorPdf {

    @Override
    public int paginas(byte[] pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return d.getNumberOfPages();
        }
    }

    @Override
    public byte[] pagina(byte[] pdf, int numero) throws IOException {
        try (PDDocument origen = Loader.loadPDF(pdf); PDDocument nuevo = new PDDocument()) {
            if (numero < 1 || numero > origen.getNumberOfPages()) {
                throw new IOException("El PDF no tiene la página " + numero + " (tiene " + origen.getNumberOfPages() + ").");
            }
            nuevo.importPage(origen.getPage(numero - 1));
            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            nuevo.save(salida);
            return salida.toByteArray();
        }
    }
}
