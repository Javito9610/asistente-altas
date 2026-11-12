package es.amagna.asistente;

import es.amagna.asistente.cerebro.Agente;
import es.amagna.asistente.manos.ManosRobot;
import es.amagna.asistente.manos.Ventanas;
import es.amagna.asistente.manos.VentanasNativas;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Pruebas en un Windows de verdad, sin IA (no gastan nada). Las pasa GitHub en Windows 2022 y 2025 en cada cambio, y
 * también se pueden pasar en cualquier PC: «asistente probar-windows». Lo que comprueban, con el Bloc de notas:
 *   1. abrir el programa y encontrar su ventana;      5. diálogos: abrir, detectar, cancelar y poder volver a abrir;
 *   2. ponerla delante;                               6. la captura de pantalla;
 *   3. escribir y leer el texto EXACTO;               7. la velocidad.
 *   4. teclas de aplicación (F5) con cada forma de mandar teclas, y se guarda la que funciona en este PC;
 *
 * Al terminar deja el Bloc de notas como estaba: vacía su pestaña, la guarda y la cierra (así no se acumulan ventanas
 * de pruebas anteriores, que es lo que bloqueaba los atajos).
 */
public final class PruebasWindows {
    private static int fallos = 0;
    private static String clave = "";
    private static Path fichero;

    public static void main(String[] args) throws Exception {
        ManosRobot manos = new ManosRobot(false);
        Ventanas v = new VentanasNativas(false);
        fichero = Files.createTempFile("prueba-asistente-", ".txt");
        clave = fichero.getFileName().toString().replace(".txt", "");

        System.out.println("1) Abrir el Bloc de notas con un fichero propio y vacío");
        v.abrir("notepad.exe", List.of(fichero.toString()));
        boolean aparece = esperar(() -> v.abiertas().contains(clave), 25_000);
        ok(aparece, "la ventana «" + clave + "» aparece en ventanas_abiertas");
        if (!aparece) {
            terminar(manos, v);
            return;
        }

        System.out.println("2) Ponerla delante");
        ok(v.activar(clave), "activar_ventana la pone delante");
        ok(v.activa().contains(clave), "ventana_activa dice que es la de delante: «" + v.activa() + "»");
        manos.esperar(0.5);

        System.out.println("3) Escribir y leer el campo, exacto");
        String texto = "Hola, soy el Asistente Altas de Amagna. Ñandú PÉREZ 00000000T";
        manos.escribir(texto);
        manos.esperar(0.5);
        Ventanas.Campo c = v.campoConFoco();
        System.out.println("   control: " + c.tipo() + " · ventana «" + c.ventana() + "»");
        ok(c.valor() != null, "leer_campo puede leer el control con el foco");
        ok(c.valor() != null && Agente.comparar(texto, c.valor()).startsWith("IGUAL"), "lo leído es IGUAL a lo escrito: «" + c.valor() + "»");
        ok(c.valor() != null && Agente.comparar(texto.replace(".", ""), c.valor()).startsWith("DISTINTO"), "y si falta un punto, DISTINTO");

        // Un atajo del propio programa (no del cuadro de texto) cuyo efecto se puede leer EXACTO: F5 escribe la fecha y
        // la hora. Sin diálogos ni esperas, así que dice sin lugar a dudas si las teclas de aplicación llegan.
        System.out.println("4) Teclas de aplicación: F5 escribe la fecha y la hora (se prueba cada forma de mandar teclas)");
        v.cancelarBloqueos(); // si una prueba anterior dejó un diálogo abierto, bloquea los atajos de todo el programa
        ManosRobot.Teclado funciona = null;
        for (ManosRobot.Teclado modo : List.of(ManosRobot.Teclado.VK, ManosRobot.Teclado.SCAN, ManosRobot.Teclado.ROBOT)) {
            v.activar(clave);
            manos.usarTeclado(modo);
            String antes = valor(v);
            manos.tecla("F5", 1);
            manos.esperar(0.8);
            String ahora = valor(v);
            String puesto = antes != null && ahora != null && ahora.startsWith(antes) ? ahora.substring(antes.length()) : null;
            boolean vale = puesto != null && puesto.matches(".*\\d.*:.*\\d.*"); // una fecha y una hora, en cualquier idioma
            System.out.println("   teclado " + modo + ": " + (vale ? "escribe «" + puesto.strip() + "»" : "no hace nada"));
            if (vale) {
                funciona = modo;
                break;
            }
        }
        ok(funciona != null, "las teclas de aplicación llegan al programa" + (funciona != null ? " (modo " + funciona + ")" : ""));
        if (funciona == null) {
            System.out.print(VentanasNativas.diagnostico());
            terminar(manos, v);
            return;
        }
        manos.usarTeclado(funciona);
        Files.createDirectories(ManosRobot.ficheroTeclado().getParent());
        Files.writeString(ManosRobot.ficheroTeclado(), funciona.name());
        System.out.println("   guardado: este PC usa el teclado " + funciona + " (" + ManosRobot.ficheroTeclado() + ")");

        // Diálogos modales, con una ventana de prueba nuestra: siempre igual en cualquier PC. (El diálogo de archivos
        // de Windows no sirve de prueba: tarda lo que quiere, y con OneDrive puede pasar de medio minuto.)
        System.out.println("5) Diálogos modales: se ven, no cuelgan, se cancelan y el programa sigue sano");
        Process ayudante = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                "es.amagna.asistente.VentanaDePrueba", "prueba-dialogos").inheritIO().start();
        try {
            ok(esperar(() -> v.abiertas().contains("prueba-dialogos"), 20_000), "la ventana de prueba aparece");
            for (int vuelta = 1; vuelta <= 2; vuelta++) {
                String buscado = "Dialogo " + vuelta + " de prueba-dialogos";
                ok(esperar(() -> VentanasNativas.instantanea().containsValue(buscado), 15_000), "vuelta " + vuelta + ": se ve el diálogo «" + buscado + "»");
                ok(esperar(() -> v.abiertas().contains("BLOQUEADA"), 5_000), "vuelta " + vuelta + ": la ventana sale marcada como BLOQUEADA");
                long t0 = System.currentTimeMillis();
                try {
                    v.campoConFoco();
                } catch (IllegalStateException e) {
                    System.out.println("   (aviso esperado) " + e.getMessage());
                }
                ok(System.currentTimeMillis() - t0 < 5_000, "vuelta " + vuelta + ": con el diálogo abierto, leer_campo no se cuelga (" + (System.currentTimeMillis() - t0) + " ms)");
                List<String> cancelados = v.cancelarBloqueos();
                System.out.println("   cancelado: " + cancelados);
                ok(esperar(() -> !VentanasNativas.instantanea().containsValue(buscado), 5_000), "vuelta " + vuelta + ": el diálogo se cierra");
                ok(esperar(() -> !v.abiertas().contains("BLOQUEADA"), 5_000), "vuelta " + vuelta + ": ya no queda ninguna ventana bloqueada");
            }
            // Que la vuelta 2 haya funcionado demuestra que cancelar no deja al programa creyendo que sigue abierto.
        } finally {
            ayudante.destroy();
            ayudante.waitFor();
        }

        // El diálogo de archivos de verdad: solo se informa de cuánto tarda (depende de Windows y de OneDrive, no de nosotros).
        System.out.println("5b) Diálogo de archivos de Windows (Ctrl+O), solo para saber cuánto tarda en este PC");
        v.activar(clave);
        manos.esperar(0.3);
        Map<Long, String> antesO = VentanasNativas.instantanea();
        long tO = System.currentTimeMillis();
        manos.tecla("ctrl+o", 1);
        String abrir = esperarNueva(antesO, 45_000);
        System.out.println(abrir != null
                ? "   tarda " + (System.currentTimeMillis() - tO) / 1000 + " s en abrir «" + abrir + "»"
                : "   no se ha abierto en 45 s (no cuenta como fallo: en este PC el diálogo de archivos es muy lento)");
        quitarEstorbos(manos, v);

        System.out.println("6) La captura");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(manos.captura()));
        ok(img.getWidth() == manos.escala().anchoImagen() && img.getHeight() == manos.escala().altoImagen(), "captura de " + img.getWidth() + "×" + img.getHeight());
        ok(variada(img), "la captura no está en blanco ni negra");
        Files.write(Path.of("captura-prueba.png"), manos.captura());

        System.out.println("7) Velocidad");
        long t1 = System.nanoTime();
        for (int k = 0; k < 10; k++) {
            v.activa();
        }
        long ms = (System.nanoTime() - t1) / 10_000_000;
        ok(ms < 200, "preguntar a Windows tarda " + ms + " ms (antes, con PowerShell, segundos)");

        terminar(manos, v);
    }

    /** Cancela cualquier diálogo que esté bloqueando una ventana (de esta prueba o de una anterior). */
    private static void quitarEstorbos(ManosRobot manos, Ventanas v) {
        manos.tecla("Escape", 1); // por si hay un diálogo con el foco: es lo que haría una persona
        manos.esperar(0.6);
        List<String> cancelados = v.cancelarBloqueos();
        if (!cancelados.isEmpty()) {
            System.out.println("   había diálogos abiertos bloqueando: " + cancelados);
            manos.esperar(0.5);
        }
    }

    /** Deja el Bloc de notas como estaba: vacía la pestaña de la prueba, la guarda y la cierra (sin preguntar nada). */
    private static void terminar(ManosRobot manos, Ventanas v) {
        try {
            v.cancelarBloqueos();
            if (!clave.isEmpty() && v.abiertas().contains(clave)) {
                v.activar(clave);
                manos.tecla("ctrl+a", 1);
                manos.tecla("Delete", 1);
                manos.tecla("ctrl+s", 1);
                manos.esperar(1);
                v.cerrarVentana(clave);
                manos.esperar(0.5);
                if (v.abiertas().contains(clave)) {
                    manos.tecla("Escape", 1); // si pregunta si guardar, no se cierra: se deja como está
                    System.out.println("   (no se ha podido cerrar la pestaña de la prueba: ciérrala tú, sin guardar)");
                }
            }
            Files.deleteIfExists(fichero);
        } catch (RuntimeException | java.io.IOException e) {
            System.out.println("   (limpieza: " + e.getMessage() + ")");
        }
        System.out.println(fallos == 0 ? "\nPRUEBAS EN WINDOWS: TODO BIEN" : "\nPRUEBAS EN WINDOWS: " + fallos + " FALLO(S)");
        System.exit(fallos == 0 ? 0 : 1);
    }

    /** El título de la primera ventana que no estaba antes (un diálogo recién abierto), o null. */
    private static String esperarNueva(Map<Long, String> antes, long msMax) throws InterruptedException {
        long fin = System.currentTimeMillis() + msMax;
        do {
            for (Map.Entry<Long, String> e : VentanasNativas.instantanea().entrySet()) {
                if (!antes.containsKey(e.getKey()) && !e.getValue().isBlank()) {
                    return e.getValue();
                }
            }
            Thread.sleep(250);
        } while (System.currentTimeMillis() < fin);
        return null;
    }

    private static String valor(Ventanas v) {
        try {
            return v.campoConFoco().valor();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean variada(BufferedImage img) {
        int primero = img.getRGB(0, 0);
        for (int y = 0; y < img.getHeight(); y += 7) {
            for (int x = 0; x < img.getWidth(); x += 7) {
                if (img.getRGB(x, y) != primero) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean esperar(java.util.function.BooleanSupplier cond, long msMax) throws InterruptedException {
        long fin = System.currentTimeMillis() + msMax;
        while (System.currentTimeMillis() < fin) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Thread.sleep(250);
        }
        return cond.getAsBoolean();
    }

    private static void ok(boolean c, String que) {
        System.out.println((c ? "  ok    " : "  FALLA ") + que);
        if (!c) {
            fallos++;
        }
    }
}
