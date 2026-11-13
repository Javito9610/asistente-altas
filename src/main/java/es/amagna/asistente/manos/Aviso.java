package es.amagna.asistente.manos;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * El aviso: que el asistente dé señales de vida cuando termina algo largo.
 *
 * Un trabajo que tarda diez minutos y acaba en silencio en una consola no sirve de nada: la persona se ha ido a hacer
 * otra cosa. Esto es el primer trozo de la cara del Asistente Amagna — el globito al lado del reloj — y lo que usará
 * de noche para decir «he terminado 14 altas» o «esto no lo sé hacer, míralo tú».
 *
 * Nunca falla hacia arriba: si el ordenador no tiene escritorio (una prueba automática, un servidor), se calla y la
 * tarea sigue igual.
 *
 * @package Amagna\Altas
 */
public final class Aviso {
    private Aviso() {
    }

    /** Un globito al lado del reloj y un pitido. No espera a nadie. */
    public static void avisar(String titulo, String texto, boolean malo) {
        pitar(malo ? 3 : 1);
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return;
        }
        try {
            SystemTray bandeja = SystemTray.getSystemTray();
            TrayIcon icono = new TrayIcon(dibujo(malo), "Asistente Altas");
            icono.setImageAutoSize(true);
            bandeja.add(icono);
            icono.displayMessage(titulo, texto, malo ? TrayIcon.MessageType.WARNING : TrayIcon.MessageType.INFO);
            // El globito necesita que el icono siga ahí un rato; se quita después, sin bloquear la tarea.
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(20_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                bandeja.remove(icono);
            });
            t.setDaemon(false);
            t.start();
        } catch (AWTException | RuntimeException e) {
            // Sin bandeja de sistema: con el pitido basta.
        }
    }

    /** Además del globito, una ventanita que se queda hasta que la cierras (o hasta que pasan los segundos). */
    public static void avisarYEsperar(String titulo, String texto, int segundos) {
        avisar(titulo, texto, false);
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            javax.swing.JOptionPane panel = new javax.swing.JOptionPane(texto, javax.swing.JOptionPane.INFORMATION_MESSAGE);
            javax.swing.JDialog d = panel.createDialog(null, titulo);
            d.setAlwaysOnTop(true);
            d.setModal(false);
            javax.swing.Timer reloj = new javax.swing.Timer(Math.max(5, segundos) * 1000, e -> d.dispose());
            reloj.setRepeats(false);
            reloj.start();
            d.setVisible(true);
            while (d.isVisible()) {
                Thread.sleep(200);
            }
            d.dispose();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // Si no se puede enseñar la ventanita, ya ha sonado el pitido.
        }
    }

    private static void pitar(int veces) {
        for (int i = 0; i < veces; i++) {
            try {
                Toolkit.getDefaultToolkit().beep();
                Thread.sleep(180);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                return;
            }
        }
    }

    /** Un cuadradito de color, para no depender de ningún fichero de imagen. */
    private static Image dibujo(boolean malo) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(malo ? new java.awt.Color(0x9B2C2C) : new java.awt.Color(0x1D6B3F));
        g.fillRoundRect(0, 0, 16, 16, 5, 5);
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 11));
        g.drawString("A", 5, 12);
        g.dispose();
        return img;
    }
}
