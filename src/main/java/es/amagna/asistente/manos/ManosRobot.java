package es.amagna.asistente.manos;

import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Manos de verdad: el robot de Java sobre la pantalla principal de Windows.
 *
 * Precisión:
 *  - Las capturas se hacen a la resolución REAL de la pantalla (aunque Windows esté al 125 % o 150 %) y se reducen con
 *    buena calidad al tamaño que ve Claude, para que lea bien la letra pequeña.
 *  - El ratón se mueve en coordenadas lógicas de Windows (las que usa Java), traducidas con Escala.
 *  - Texto con solo letras, números y espacios: tecla a tecla. Cualquier otro (ñ, acentos, @, /): por el portapapeles,
 *    que no depende de la distribución del teclado.
 *  - modoVista = true: hace capturas de verdad pero NO toca nada (solo dice lo que haría). Para probar sin riesgo.
 *
 * Freno de emergencia: si el ratón está en la esquina superior izquierda antes de una acción, se para todo.
 */
public final class ManosRobot implements Manos {
    private static final int PAUSA_TRAS_ACCION_MS = 150;

    private final Robot robot;
    private final Rectangle pantalla;
    private final Escala escala;
    private final boolean modoVista;

    /**
     * Cómo se mandan las teclas y atajos: con el robot de Java o con SendInput de Windows (VK = tecla virtual + código de
     * exploración; SCAN = solo código de exploración). «asistente probar-windows» mira cuál funciona en este PC y lo guarda.
     */
    public enum Teclado { ROBOT, VK, SCAN }

    private Teclado teclado = tecladoGuardado();

    public static java.nio.file.Path ficheroTeclado() {
        String base = System.getenv("LOCALAPPDATA");
        return java.nio.file.Path.of(base != null ? base : System.getProperty("user.home"), "AsistenteAltas", "teclado.txt");
    }

    /** El modo guardado por la calibración; si no hay, VK en Windows (lo que manda un teclado físico) y ROBOT en lo demás. */
    public static Teclado tecladoGuardado() {
        try {
            return Teclado.valueOf(java.nio.file.Files.readString(ficheroTeclado()).trim());
        } catch (Exception e) {
            return System.getProperty("os.name", "").toLowerCase().contains("windows") ? Teclado.VK : Teclado.ROBOT;
        }
    }

    public void usarTeclado(Teclado t) {
        this.teclado = t;
    }

    public Teclado teclado() {
        return teclado;
    }

    public ManosRobot(boolean modoVista) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("No hay pantalla (entorno sin escritorio).");
        }
        try {
            this.robot = new Robot();
        } catch (AWTException e) {
            throw new IllegalStateException("No se puede controlar la pantalla: " + e.getMessage(), e);
        }
        robot.setAutoDelay(20);
        GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        this.pantalla = gd.getDefaultConfiguration().getBounds();
        this.escala = Escala.para(pantalla.width, pantalla.height);
        this.modoVista = modoVista;
    }

    @Override
    public Escala escala() {
        return escala;
    }

    /** Factor de escala de Windows (1.0 = 100 %, 1.25 = 125 %…). */
    public double escalaWindows() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getDefaultTransform().getScaleX();
    }

    // ---------- ver ----------

    @Override
    public byte[] captura() {
        BufferedImage nativa = capturaNativa(pantalla);
        return png(reducir(nativa, escala.anchoImagen(), escala.altoImagen()));
    }

    @Override
    public byte[] zoom(int x0, int y0, int x1, int y1) {
        int[] a = escala.aPantalla(Math.min(x0, x1), Math.min(y0, y1));
        int[] b = escala.aPantalla(Math.max(x0, x1), Math.max(y0, y1));
        Rectangle r = new Rectangle(pantalla.x + a[0], pantalla.y + a[1], Math.max(1, b[0] - a[0] + 1), Math.max(1, b[1] - a[1] + 1));
        BufferedImage nativa = capturaNativa(r);
        // Se devuelve a resolución nativa (sin reducir) salvo que pase del máximo de la captura.
        double f = Math.min(1.0, Math.min((double) Escala.ANCHO_MAX / nativa.getWidth(), (double) Escala.ALTO_MAX / nativa.getHeight()));
        return png(f < 1.0 ? reducir(nativa, (int) Math.round(nativa.getWidth() * f), (int) Math.round(nativa.getHeight() * f)) : nativa);
    }

    private BufferedImage capturaNativa(Rectangle r) {
        MultiResolutionImage mri = robot.createMultiResolutionScreenCapture(r);
        List<Image> variantes = mri.getResolutionVariants();
        Image mayor = variantes.get(variantes.size() - 1);
        for (Image v : variantes) {
            if (v.getWidth(null) > mayor.getWidth(null)) {
                mayor = v;
            }
        }
        if (mayor instanceof BufferedImage bi) {
            return bi;
        }
        BufferedImage bi = new BufferedImage(mayor.getWidth(null), mayor.getHeight(null), BufferedImage.TYPE_INT_RGB);
        bi.getGraphics().drawImage(mayor, 0, 0, null);
        return bi;
    }

    /** Reducción de calidad: a la mitad en varios pasos y el último con bicúbica (la letra sale nítida). */
    static BufferedImage reducir(BufferedImage src, int w, int h) {
        BufferedImage actual = src;
        int cw = src.getWidth();
        int ch = src.getHeight();
        do {
            cw = Math.max(w, cw / 2);
            ch = Math.max(h, ch / 2);
            if (actual.getWidth() / 2 < w || actual.getHeight() / 2 < h) {
                cw = w;
                ch = h;
            }
            BufferedImage siguiente = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
            var g = siguiente.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(actual, 0, 0, cw, ch, null);
            g.dispose();
            actual = siguiente;
        } while (cw != w || ch != h);
        return actual;
    }

    static byte[] png(BufferedImage img) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- actuar ----------

    private void freno() {
        Point p = MouseInfo.getPointerInfo().getLocation();
        if (Math.abs(p.x - pantalla.x) <= 2 && Math.abs(p.y - pantalla.y) <= 2) {
            throw new FrenoDeEmergencia();
        }
    }

    private boolean vista(String que) {
        if (modoVista) {
            System.out.println("    [modo vista, no se hace] " + que);
        }
        return modoVista;
    }

    /**
     * Un clic en un punto REAL de la pantalla (los que da Windows cuando le preguntas dónde está un control), no en
     * coordenadas de la captura. Hace falta cuando un botón no se deja pulsar por dentro y hay que ir al ratón.
     */
    public void clicEnPantalla(int px, int py) {
        freno();
        if (vista("clic real en " + px + "," + py)) {
            return;
        }
        robot.mouseMove(px, py);
        robot.delay(80);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(60);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        pausa();
    }

    private void moverReal(int x, int y) {
        int[] p = escala.aPantalla(x, y);
        robot.mouseMove(pantalla.x + p[0], pantalla.y + p[1]);
    }

    private void pausa() {
        robot.waitForIdle();
        robot.delay(PAUSA_TRAS_ACCION_MS);
    }

    @Override
    public void mover(int x, int y) {
        freno();
        if (vista("mover a " + x + "," + y)) {
            return;
        }
        moverReal(x, y);
        pausa();
    }

    @Override
    public void clic(Integer x, Integer y, int boton, int veces, String modificadores) {
        freno();
        if (vista("clic " + boton + "×" + veces + " en " + x + "," + y + (modificadores != null ? " con " + modificadores : ""))) {
            return;
        }
        if (x != null && y != null) {
            moverReal(x, y);
            robot.delay(60);
        }
        List<Integer> mods = modificadores == null || modificadores.isBlank() ? List.of() : Teclas.combinacion(modificadores);
        mods.forEach(robot::keyPress);
        int mascara = boton == 3 ? InputEvent.BUTTON3_DOWN_MASK : boton == 2 ? InputEvent.BUTTON2_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;
        for (int k = 0; k < veces; k++) {
            robot.mousePress(mascara);
            robot.delay(40);
            robot.mouseRelease(mascara);
            robot.delay(70);
        }
        for (int k = mods.size() - 1; k >= 0; k--) {
            robot.keyRelease(mods.get(k));
        }
        pausa();
    }

    @Override
    public void arrastrar(int x0, int y0, int x1, int y1, String modificadores) {
        freno();
        if (vista("arrastrar " + x0 + "," + y0 + " → " + x1 + "," + y1)) {
            return;
        }
        List<Integer> mods = modificadores == null || modificadores.isBlank() ? List.of() : Teclas.combinacion(modificadores);
        moverReal(x0, y0);
        mods.forEach(robot::keyPress);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        for (int k = 1; k <= 10; k++) { // en pasos: algunas aplicaciones no ven un salto de golpe
            moverReal(x0 + (x1 - x0) * k / 10, y0 + (y1 - y0) * k / 10);
            robot.delay(15);
        }
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        for (int k = mods.size() - 1; k >= 0; k--) {
            robot.keyRelease(mods.get(k));
        }
        pausa();
    }

    @Override
    public void bajarBotonIzquierdo() {
        freno();
        if (!vista("bajar botón izquierdo")) {
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        }
    }

    @Override
    public void subirBotonIzquierdo() {
        if (!vista("soltar botón izquierdo")) {
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            pausa();
        }
    }

    @Override
    public void escribir(String texto) {
        freno();
        if (vista("escribir «" + texto + "»")) {
            return;
        }
        if (texto.matches("[A-Za-z0-9 ]{1,80}")) {
            for (char c : texto.toCharArray()) {
                boolean mayus = Character.isUpperCase(c);
                int code = c == ' ' ? KeyEvent.VK_SPACE : Teclas.codigo(String.valueOf(c));
                if (mayus) {
                    robot.keyPress(KeyEvent.VK_SHIFT);
                }
                robot.keyPress(code);
                robot.keyRelease(code);
                if (mayus) {
                    robot.keyRelease(KeyEvent.VK_SHIFT);
                }
            }
        } else {
            pegar(texto);
        }
        pausa();
    }

    @Override
    public String leerCampo() {
        freno();
        if (modoVista) {
            System.out.println("    [modo vista, no se hace] leer el campo activo");
            return "";
        }
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable antes = null;
        try {
            antes = cb.isDataFlavorAvailable(DataFlavor.stringFlavor) ? cb.getContents(null) : null;
        } catch (IllegalStateException ignorada) {
            // ocupado
        }
        String marca = "\u0000asistente-altas-" + System.nanoTime();
        cb.setContents(new StringSelection(marca), null); // si después sigue la marca, es que no se ha copiado nada
        for (int[] combo : new int[][] {{KeyEvent.VK_CONTROL, KeyEvent.VK_A}, {KeyEvent.VK_CONTROL, KeyEvent.VK_C}}) {
            robot.keyPress(combo[0]);
            robot.keyPress(combo[1]);
            robot.keyRelease(combo[1]);
            robot.keyRelease(combo[0]);
            robot.delay(120);
        }
        String texto = null;
        for (int intento = 0; intento < 10 && texto == null; intento++) {
            robot.delay(80);
            try {
                String t = (String) cb.getData(DataFlavor.stringFlavor);
                texto = marca.equals(t) ? null : t;
            } catch (Exception ignorada) {
                // el portapapeles aún está ocupado: se reintenta
            }
        }
        robot.keyPress(KeyEvent.VK_END); // quitar la selección sin borrar nada
        robot.keyRelease(KeyEvent.VK_END);
        if (antes != null) {
            try {
                cb.setContents(antes, null);
            } catch (IllegalStateException ignorada) {
                // no pasa nada
            }
        }
        pausa();
        if (texto == null) {
            throw new IllegalStateException("No se ha podido copiar el texto del campo activo (¿tiene el foco un campo de texto?)");
        }
        return texto;
    }

    /** Pega el texto con Ctrl+V y deja el portapapeles como estaba. */
    private void pegar(String texto) {
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable antes = null;
        try {
            antes = cb.isDataFlavorAvailable(DataFlavor.stringFlavor) ? cb.getContents(null) : null;
        } catch (IllegalStateException ignorada) {
            // portapapeles ocupado: no se restaura
        }
        cb.setContents(new StringSelection(texto), null);
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        robot.delay(300); // que la aplicación lea el portapapeles antes de restaurarlo
        if (antes != null) {
            try {
                cb.setContents(antes, null);
            } catch (IllegalStateException ignorada) {
                // no pasa nada
            }
        }
    }

    @Override
    public void tecla(String combinacion, int repeticiones) {
        freno();
        if (vista("tecla " + combinacion + (repeticiones > 1 ? " ×" + repeticiones : ""))) {
            return;
        }
        if (teclado != Teclado.ROBOT) {
            List<Integer> vks = TecladoNativo.combinacion(combinacion);
            TecladoNativo.Modo modo = teclado == Teclado.SCAN ? TecladoNativo.Modo.SCAN : TecladoNativo.Modo.VK;
            for (int r = 0; r < Math.max(1, repeticiones); r++) {
                TecladoNativo.pulsar(vks, modo);
                robot.delay(30);
            }
            pausa();
            return;
        }
        List<Integer> teclas = Teclas.combinacion(combinacion);
        for (int r = 0; r < Math.max(1, repeticiones); r++) {
            teclas.forEach(robot::keyPress);
            robot.delay(TecladoNativo.MS_MANTENER); // mantener la combinación, como una persona (ver TecladoNativo)
            for (int k = teclas.size() - 1; k >= 0; k--) {
                robot.keyRelease(teclas.get(k));
            }
            robot.delay(30);
        }
        pausa();
    }

    @Override
    public void mantenerTecla(String combinacion, double segundos) {
        freno();
        if (vista("mantener " + combinacion + " " + segundos + " s")) {
            return;
        }
        List<Integer> teclas = Teclas.combinacion(combinacion);
        teclas.forEach(robot::keyPress);
        robot.delay((int) Math.min(300_000, segundos * 1000));
        for (int k = teclas.size() - 1; k >= 0; k--) {
            robot.keyRelease(teclas.get(k));
        }
        pausa();
    }

    @Override
    public void rueda(String direccion, int cantidad, Integer x, Integer y, String modificadores) {
        freno();
        if (vista("rueda " + direccion + " " + cantidad)) {
            return;
        }
        if (x != null && y != null) {
            moverReal(x, y);
        }
        boolean horizontal = "left".equals(direccion) || "right".equals(direccion);
        List<Integer> mods = new java.util.ArrayList<>(modificadores == null || modificadores.isBlank() ? List.of() : Teclas.combinacion(modificadores));
        if (horizontal) {
            mods.add(KeyEvent.VK_SHIFT); // en Windows, Mayús + rueda = desplazamiento horizontal
        }
        mods.forEach(robot::keyPress);
        int signo = "up".equals(direccion) || "left".equals(direccion) ? -1 : 1;
        for (int k = 0; k < Math.max(1, cantidad); k++) {
            robot.mouseWheel(signo);
            robot.delay(30);
        }
        for (int k = mods.size() - 1; k >= 0; k--) {
            robot.keyRelease(mods.get(k));
        }
        pausa();
    }

    @Override
    public void esperar(double segundos) {
        try {
            Thread.sleep((long) Math.min(300_000, segundos * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public int[] posicion() {
        Point p = MouseInfo.getPointerInfo().getLocation();
        return escala.aImagen(p.x - pantalla.x, p.y - pantalla.y);
    }

    /** Para la prueba de precisión: mueve el ratón y dice dónde ha quedado de verdad (en píxeles lógicos). */
    public int[] moverYMedir(int x, int y) {
        moverReal(x, y);
        robot.waitForIdle();
        robot.delay(80);
        Point p = MouseInfo.getPointerInfo().getLocation();
        int[] esperado = escala.aPantalla(x, y);
        return new int[] {esperado[0], esperado[1], p.x - pantalla.x, p.y - pantalla.y};
    }
}
