package es.amagna.asistente;

import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Label;

/**
 * Programa de usar y tirar para las pruebas: abre una ventana y, encima, un diálogo modal (de los que bloquean su
 * ventana, como los de MN). Cuando se cancela el diálogo, espera un momento y abre otro, hasta tres veces.
 *
 * Sirve para probar de forma segura y siempre igual lo que de verdad importa: que el asistente ve el diálogo, que no
 * se queda colgado, que lo cancela bien y que el programa sigue sano después. Sin depender del diálogo de archivos de
 * Windows, que tarda lo que quiere (en un PC con OneDrive puede tardar medio minuto).
 *
 * Uso: java -cp … es.amagna.asistente.VentanaDePrueba <nombre>
 */
public final class VentanaDePrueba {
    public static void main(String[] args) throws Exception {
        String nombre = args.length > 0 ? args[0] : "ventana-de-prueba";
        Frame f = new Frame(nombre);
        f.add(new Label("Ventana de prueba del Asistente Altas"));
        f.setSize(420, 160);
        f.setLocation(60, 60);
        f.setVisible(true);
        Thread.sleep(800);
        for (int vuelta = 1; vuelta <= 3; vuelta++) {
            Dialog d = new Dialog(f, "Dialogo " + vuelta + " de " + nombre, true);
            d.add(new Label("Diálogo modal de prueba: bloquea su ventana."));
            d.setSize(360, 140);
            d.setLocation(120, 120);
            d.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    d.dispose();
                }
            });
            d.setVisible(true); // bloquea aquí hasta que se cierre
            Thread.sleep(700);
        }
        f.dispose();
        System.exit(0);
    }
}
