package es.amagna.asistente.jarvis;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * La cara de Jarvis: estado en vivo (escuchando / trabajando / en pausa), la conversación, la lista de trabajos y lo programado.
 * Se le escribe como por Telegram. Cerrar la ventana NO lo apaga: se esconde y queda el icono junto al reloj; para pararlo del
 * todo, «Salir».
 */
public final class Ventana {
    static final Color FONDO = new Color(15, 18, 25);
    static final Color PANEL = new Color(24, 29, 40);
    static final Color TEXTO = new Color(225, 232, 245);
    static final Color APAGADO = new Color(140, 152, 172);
    static final Color ACENTO = new Color(90, 170, 255);
    static final Color VERDE = new Color(70, 200, 120);
    static final Color AMBAR = new Color(240, 180, 60);

    private final JFrame marco = new JFrame("Jarvis");
    private final JTextPane charla = new JTextPane();
    private final JButton pausa = new JButton("Pausa");
    private final DefaultTableModel tabla = new DefaultTableModel(new String[] {"Hora", "Estado", "Petición", "Resultado"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final DefaultTableModel tablaFicheros = new DefaultTableModel(new String[] {"Fichero", "Tamaño", "Modificado"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private volatile Supplier<List<java.nio.file.Path>> ficheros;
    private volatile List<java.nio.file.Path> ficherosMostrados = List.of();
    private final JTextArea programadas = new JTextArea();
    private final JLabel estadoTexto = new JLabel("Arrancando…");
    private Color colorEstado = AMBAR;
    private JComponent luz;
    private volatile Supplier<String> estado;
    private volatile Supplier<List<String[]>> trabajos;
    private volatile Supplier<String> agenda;
    private TrayIcon bandeja;

    /** null si no hay pantalla (servidor sin escritorio). */
    public static Ventana abrir(Consumer<String> alEscribir, Runnable alPausar, Runnable alSalir) {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        Ventana v = new Ventana();
        SwingUtilities.invokeLater(() -> v.montar(alEscribir, alPausar, alSalir));
        return v;
    }

    /** Lo que se refresca cada par de segundos: el estado, la lista de trabajos (hora, estado, petición, resultado) y lo programado. */
    public Ventana conPaneles(Supplier<String> estado, Supplier<List<String[]>> trabajos, Supplier<String> agenda) {
        this.estado = estado;
        this.trabajos = trabajos;
        this.agenda = agenda;
        return this;
    }

    /** Los ficheros de trabajo de Jarvis (los últimos primero): se abren con doble clic. */
    public Ventana conFicheros(Supplier<List<java.nio.file.Path>> ficheros) {
        this.ficheros = ficheros;
        return this;
    }

    private static void oscuro(JComponent c) {
        c.setBackground(PANEL);
        c.setForeground(TEXTO);
        c.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
    }

    private void montar(Consumer<String> alEscribir, Runnable alPausar, Runnable alSalir) {
        marco.getContentPane().setBackground(FONDO);
        marco.setLayout(new BorderLayout());
        // Cabecera: luz de estado + nombre + qué está haciendo.
        luz = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(colorEstado.getRed(), colorEstado.getGreen(), colorEstado.getBlue(), 60));
                g2.fillOval(0, 0, 22, 22);
                g2.setColor(colorEstado);
                g2.fillOval(5, 5, 12, 12);
                g2.dispose();
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(22, 22);
            }
        };
        JLabel nombre = new JLabel("JARVIS");
        nombre.setForeground(TEXTO);
        nombre.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        estadoTexto.setForeground(APAGADO);
        estadoTexto.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        JPanel textos = new JPanel(new BorderLayout());
        textos.setOpaque(false);
        textos.add(nombre, BorderLayout.NORTH);
        textos.add(estadoTexto, BorderLayout.SOUTH);
        JPanel cabecera = new JPanel(new BorderLayout(12, 0));
        cabecera.setBackground(FONDO);
        cabecera.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));
        cabecera.add(luz, BorderLayout.WEST);
        cabecera.add(textos, BorderLayout.CENTER);
        // Pestañas.
        charla.setEditable(false);
        charla.setBackground(PANEL);
        charla.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JTable t = new JTable(tabla);
        oscuro(t);
        t.setGridColor(FONDO);
        t.setRowHeight(24);
        t.setFillsViewportHeight(true);
        t.getTableHeader().setBackground(FONDO);
        t.getTableHeader().setForeground(APAGADO);
        t.getColumnModel().getColumn(0).setPreferredWidth(70);
        t.getColumnModel().getColumn(1).setPreferredWidth(110);
        t.getColumnModel().getColumn(2).setPreferredWidth(260);
        t.getColumnModel().getColumn(3).setPreferredWidth(320);
        JTable tf = new JTable(tablaFicheros);
        oscuro(tf);
        tf.setGridColor(FONDO);
        tf.setRowHeight(24);
        tf.setFillsViewportHeight(true);
        tf.getTableHeader().setBackground(FONDO);
        tf.getTableHeader().setForeground(APAGADO);
        tf.getColumnModel().getColumn(0).setPreferredWidth(380);
        tf.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int fila = tf.getSelectedRow();
                if (e.getClickCount() == 2 && fila >= 0 && fila < ficherosMostrados.size()) {
                    try {
                        java.awt.Desktop.getDesktop().open(ficherosMostrados.get(fila).toFile());
                    } catch (Exception ex) {
                        decir("Jarvis: no he podido abrir el fichero (" + ex.getMessage() + ").");
                    }
                }
            }
        });
        programadas.setEditable(false);
        oscuro(programadas);
        programadas.setLineWrap(true);
        programadas.setWrapStyleWord(true);
        programadas.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JTabbedPane pestanas = new JTabbedPane();
        pestanas.setBackground(FONDO);
        pestanas.addTab("Conversación", marcoOscuro(new JScrollPane(charla)));
        pestanas.addTab("Trabajos", marcoOscuro(new JScrollPane(t)));
        pestanas.addTab("Ficheros", marcoOscuro(new JScrollPane(tf)));
        pestanas.addTab("Programadas", marcoOscuro(new JScrollPane(programadas)));
        pestanas.setForeground(TEXTO);
        for (int i = 0; i < pestanas.getTabCount(); i++) {
            pestanas.setForegroundAt(i, TEXTO);
            pestanas.setBackgroundAt(i, PANEL);
        }
        Runnable colorear = () -> {
            for (int i = 0; i < pestanas.getTabCount(); i++) {
                boolean sel = i == pestanas.getSelectedIndex();
                pestanas.setForegroundAt(i, sel ? FONDO : TEXTO);
                pestanas.setBackgroundAt(i, sel ? ACENTO : PANEL);
            }
        };
        pestanas.addChangeListener(e -> colorear.run());
        colorear.run();
        pestanas.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        // Entrada.
        JTextField entrada = new JTextField();
        oscuro(entrada);
        entrada.setCaretColor(TEXTO);
        entrada.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(50, 60, 80)),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        Runnable enviar = () -> {
            String x = entrada.getText().strip();
            if (!x.isEmpty()) {
                entrada.setText("");
                decir("Tú: " + x);
                new Thread(() -> alEscribir.accept(x), "ventana-jarvis").start();
            }
        };
        entrada.addActionListener(e -> enviar.run());
        JButton mandar = new JButton("Enviar");
        mandar.addActionListener(e -> enviar.run());
        pausa.addActionListener(e -> alPausar.run());
        JButton salir = new JButton("Salir");
        salir.addActionListener(e -> {
            quitarBandeja();
            alSalir.run();
        });
        JPanel botones = new JPanel();
        botones.setOpaque(false);
        botones.add(mandar);
        botones.add(pausa);
        botones.add(salir);
        JPanel abajo = new JPanel(new BorderLayout(8, 0));
        abajo.setBackground(FONDO);
        abajo.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
        abajo.add(entrada, BorderLayout.CENTER);
        abajo.add(botones, BorderLayout.EAST);
        marco.add(cabecera, BorderLayout.NORTH);
        marco.add(pestanas, BorderLayout.CENTER);
        marco.add(abajo, BorderLayout.SOUTH);
        marco.setSize(720, 680);
        marco.setLocationByPlatform(true);
        marco.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        ponerBandeja(alPausar, alSalir);
        marco.setVisible(true);
        entrada.requestFocusInWindow();
        new Timer(2000, e -> refrescar()).start();
        refrescar();
    }

    private static JComponent marcoOscuro(JScrollPane s) {
        s.setBorder(BorderFactory.createEmptyBorder());
        s.getViewport().setBackground(PANEL);
        return s;
    }

    private void refrescar() {
        try {
            Supplier<String> e = estado;
            if (e != null) {
                String s = e.get();
                estadoTexto.setText(s);
                colorEstado = s.startsWith("Pausado") ? AMBAR : s.startsWith("Trabajando") ? ACENTO : VERDE;
                luz.repaint();
                if (bandeja != null) {
                    bandeja.setToolTip("Jarvis — " + s);
                }
            }
            Supplier<List<String[]>> tr = trabajos;
            if (tr != null) {
                List<String[]> filas = tr.get();
                tabla.setRowCount(0);
                for (String[] f : filas) {
                    tabla.addRow(f);
                }
            }
            Supplier<List<java.nio.file.Path>> fs = ficheros;
            if (fs != null) {
                List<java.nio.file.Path> lista = fs.get();
                ficherosMostrados = lista;
                tablaFicheros.setRowCount(0);
                for (java.nio.file.Path f : lista) {
                    long t = 0;
                    long m = 0;
                    try {
                        t = java.nio.file.Files.size(f);
                        m = java.nio.file.Files.getLastModifiedTime(f).toMillis();
                    } catch (java.io.IOException ex) {
                        // desapareció entre medias
                    }
                    tablaFicheros.addRow(new String[] {f.getFileName().toString(), t < 1024 ? t + " B" : t < 1_048_576 ? t / 1024 + " KB" : t / 1_048_576 + " MB",
                            java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(m), java.time.ZoneId.systemDefault()).withNano(0).toString().replace('T', ' ')});
                }
            }
            Supplier<String> ag = agenda;
            if (ag != null) {
                String a = ag.get();
                if (!a.equals(programadas.getText())) {
                    programadas.setText(a);
                }
            }
        } catch (RuntimeException ex) {
            // la ventana nunca debe tumbar a Jarvis
        }
    }

    private void ponerBandeja(Runnable alPausar, Runnable alSalir) {
        try {
            if (!SystemTray.isSupported()) {
                return;
            }
            BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(FONDO);
            g.fillOval(0, 0, 32, 32);
            g.setColor(ACENTO);
            g.setStroke(new java.awt.BasicStroke(3));
            g.drawOval(4, 4, 24, 24);
            g.fillOval(11, 11, 10, 10);
            g.dispose();
            java.awt.PopupMenu menu = new java.awt.PopupMenu();
            java.awt.MenuItem abrir = new java.awt.MenuItem("Abrir Jarvis");
            abrir.addActionListener(e -> mostrar());
            java.awt.MenuItem pausar = new java.awt.MenuItem("Pausa / Seguir");
            pausar.addActionListener(e -> alPausar.run());
            java.awt.MenuItem fuera = new java.awt.MenuItem("Salir");
            fuera.addActionListener(e -> {
                quitarBandeja();
                alSalir.run();
            });
            menu.add(abrir);
            menu.add(pausar);
            menu.addSeparator();
            menu.add(fuera);
            bandeja = new TrayIcon(img, "Jarvis", menu);
            bandeja.setImageAutoSize(true);
            bandeja.addActionListener(e -> mostrar());
            SystemTray.getSystemTray().add(bandeja);
        } catch (Exception | Error e) {
            bandeja = null; // sin bandeja también funciona
        }
    }

    private void quitarBandeja() {
        try {
            if (bandeja != null) {
                SystemTray.getSystemTray().remove(bandeja);
            }
        } catch (RuntimeException e) {
            // nada
        }
    }

    public void decir(String texto) {
        SwingUtilities.invokeLater(() -> {
            try {
                StyledDocument d = charla.getStyledDocument();
                boolean tu = texto.startsWith("Tú: ");
                SimpleAttributeSet etiqueta = new SimpleAttributeSet();
                StyleConstants.setForeground(etiqueta, tu ? ACENTO : VERDE);
                StyleConstants.setBold(etiqueta, true);
                StyleConstants.setFontSize(etiqueta, 13);
                SimpleAttributeSet cuerpo = new SimpleAttributeSet();
                StyleConstants.setForeground(cuerpo, TEXTO);
                StyleConstants.setFontSize(cuerpo, 14);
                int i = texto.indexOf(": ");
                boolean conEtiqueta = i > 0 && i < 8;
                d.insertString(d.getLength(), (conEtiqueta ? texto.substring(0, i) : "Jarvis") + "\n", etiqueta);
                d.insertString(d.getLength(), (conEtiqueta ? texto.substring(i + 2) : texto) + "\n\n", cuerpo);
                charla.setCaretPosition(d.getLength());
            } catch (javax.swing.text.BadLocationException e) {
                // no pasa: se inserta al final
            }
        });
    }

    public void pausado(boolean si) {
        SwingUtilities.invokeLater(() -> pausa.setText(si ? "Seguir" : "Pausa"));
    }

    public void mostrar() {
        SwingUtilities.invokeLater(() -> {
            marco.setVisible(true);
            marco.toFront();
        });
    }

    /** Para pruebas de aspecto: hace una captura de la ventana. */
    public void capturar(java.nio.file.Path destino) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            BufferedImage img = new BufferedImage(marco.getWidth(), marco.getHeight(), BufferedImage.TYPE_INT_RGB);
            marco.paint(img.getGraphics());
            try {
                javax.imageio.ImageIO.write(img, "png", destino.toFile());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
