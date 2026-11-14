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
 * Con el argumento «formulario» abre otra cosa: una ficha con etiquetas y sus cuadros al lado («NIF:», «Importe:»),
 * como las de MN. Sirve para probar que el asistente encuentra un cuadro POR LA ETIQUETA que tiene al lado, que es como
 * hay que buscarlos en los programas de gestión, donde los cuadros no tienen nombre propio.
 *
 * Uso: java -cp … es.amagna.asistente.VentanaDePrueba <nombre> [formulario]
 */
public final class VentanaDePrueba {
    public static void main(String[] args) throws Exception {
        String nombre = args.length > 0 ? args[0] : "ventana-de-prueba";
        if (args.length > 1 && args[1].equals("formulario")) {
            formulario(nombre);
            return;
        }
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
            Thread.sleep(1500);
        }
        f.dispose();
        System.exit(0);
    }

    /** Una ficha con etiquetas y cuadros al lado, como las de un programa de gestión. Se cierra sola a los 90 s. */
    private static void formulario(String nombre) throws Exception {
        final Frame f = new Frame(nombre);
        f.setLayout(null);
        Label eNif = new Label("NIF:");
        eNif.setBounds(20, 50, 70, 24);
        java.awt.TextField cNif = new java.awt.TextField("");
        cNif.setBounds(100, 50, 220, 24);
        Label eImporte = new Label("Importe:");
        eImporte.setBounds(20, 90, 70, 24);
        java.awt.TextField cImporte = new java.awt.TextField("");
        cImporte.setBounds(100, 90, 220, 24);
        java.awt.Button guardar = new java.awt.Button("Guardar");
        guardar.setBounds(100, 130, 100, 28);
        // Un botón que SÍ hace algo visible: sirve para comprobar que «pulsado» significa que el programa reaccionó.
        java.awt.Button cerrar = new java.awt.Button("Cerrar ventana");
        cerrar.setBounds(210, 130, 120, 28);
        cerrar.addActionListener(e -> {
            f.dispose();
            System.exit(0);
        });
        for (java.awt.Component c : new java.awt.Component[] {eNif, cNif, eImporte, cImporte, guardar, cerrar}) {
            f.add(c);
        }
        cNif.setName("NIF");  // como MN: el propio cuadro se llama igual que su etiqueta
        // Un menú de los de toda la vida (los que usa MN, que es WinForms): al desplegarse crea una ventana
        // «#32768» de Windows. El Bloc de notas moderno no lo hace, así que para probar eso hace falta este.
        java.awt.MenuBar barra = new java.awt.MenuBar();
        java.awt.Menu datos = new java.awt.Menu("Datos");
        datos.add(new java.awt.MenuItem("Clientes"));
        datos.add(new java.awt.MenuItem("Expedientes"));
        barra.add(datos);
        f.setMenuBar(barra);
        f.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                f.dispose();
                System.exit(0);
            }
        });
        f.setSize(380, 200);
        f.setLocation(80, 80);
        f.setVisible(true);
        Thread.sleep(90_000);
        f.dispose();
        System.exit(0);
    }
}
