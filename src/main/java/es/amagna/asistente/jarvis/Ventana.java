package es.amagna.asistente.jarvis;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * La cara de Jarvis: una ventana pequeña donde se ve lo que hace, se le escribe como por Telegram, y se le puede pausar.
 * Cerrarla NO lo apaga: se esconde (sigue trabajando); para pararlo del todo, el botón «Salir».
 */
public final class Ventana {
    private final JFrame marco = new JFrame("Jarvis");
    private final JTextArea charla = new JTextArea();
    private final JButton pausa = new JButton("Pausa");

    /** null si no hay pantalla (servidor sin escritorio). */
    public static Ventana abrir(Consumer<String> alEscribir, Runnable alPausar, Runnable alSalir) {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        Ventana v = new Ventana();
        SwingUtilities.invokeLater(() -> v.montar(alEscribir, alPausar, alSalir));
        return v;
    }

    private void montar(Consumer<String> alEscribir, Runnable alPausar, Runnable alSalir) {
        charla.setEditable(false);
        charla.setLineWrap(true);
        charla.setWrapStyleWord(true);
        charla.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        charla.setBackground(new Color(18, 22, 30));
        charla.setForeground(new Color(220, 228, 240));
        charla.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JTextField entrada = new JTextField();
        entrada.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        entrada.addActionListener(e -> {
            String t = entrada.getText().strip();
            if (!t.isEmpty()) {
                entrada.setText("");
                decir("Tú: " + t);
                new Thread(() -> alEscribir.accept(t), "ventana-jarvis").start();
            }
        });
        pausa.addActionListener(e -> alPausar.run());
        JButton salir = new JButton("Salir");
        salir.addActionListener(e -> alSalir.run());
        JPanel abajo = new JPanel(new BorderLayout(6, 0));
        abajo.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        JPanel botones = new JPanel();
        botones.add(pausa);
        botones.add(salir);
        abajo.add(entrada, BorderLayout.CENTER);
        abajo.add(botones, BorderLayout.EAST);
        marco.add(new JScrollPane(charla), BorderLayout.CENTER);
        marco.add(abajo, BorderLayout.SOUTH);
        marco.setSize(560, 640);
        marco.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        marco.setVisible(true);
        entrada.requestFocusInWindow();
    }

    public void decir(String texto) {
        SwingUtilities.invokeLater(() -> {
            charla.append(texto + "\n\n");
            charla.setCaretPosition(charla.getDocument().getLength());
        });
    }

    public void pausado(boolean si) {
        SwingUtilities.invokeLater(() -> pausa.setText(si ? "Seguir" : "Pausa"));
    }

    public void mostrar() {
        SwingUtilities.invokeLater(() -> marco.setVisible(true));
    }
}
