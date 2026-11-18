package es.amagna.asistente.altas;

/**
 * La autorización para actuar ante entidades financieras es la PÁGINA 2 de la hoja de encargo, que tiene 3 páginas
 * (lo dijo Miguel). Solo se separa si el documento es un PDF de exactamente 3 páginas: con cualquier otra cosa no se
 * adivina por dónde cortar, no se adjunta nada y se avisa para que lo haga una persona.
 */
public final class Autorizacion {
    public static final int PAGINAS_DE_LA_HOJA = 3;
    public static final int PAGINA_DE_LA_AUTORIZACION = 2;

    /** pdf == null: no se ha podido; entonces «aviso» dice por qué. */
    public record Resultado(byte[] pdf, String aviso) {
    }

    private Autorizacion() {
    }

    public static Resultado separar(byte[] hoja, DivisorPdf divisor) {
        if (hoja == null || hoja.length < 5 || hoja[0] != '%' || hoja[1] != 'P' || hoja[2] != 'D' || hoja[3] != 'F') {
            return new Resultado(null, "La hoja de encargo no es un PDF: no se puede separar la autorización (que la suba una persona).");
        }
        try {
            int n = divisor.paginas(hoja);
            if (n != PAGINAS_DE_LA_HOJA) {
                return new Resultado(null, "La hoja de encargo tiene " + n + " páginas y se esperaban " + PAGINAS_DE_LA_HOJA
                        + ": no adivino cuál es la autorización (que la separe y la suba una persona).");
            }
            return new Resultado(divisor.pagina(hoja, PAGINA_DE_LA_AUTORIZACION), "");
        } catch (Exception e) {
            return new Resultado(null, "No he podido separar la autorización de la hoja de encargo (" + e.getMessage()
                    + "): que la suba una persona.");
        }
    }
}
