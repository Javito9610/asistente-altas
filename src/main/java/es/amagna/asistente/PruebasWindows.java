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
import javax.imageio.ImageIO;

/**
 * Pruebas en un Windows de verdad, sin IA (no gastan nada): las manos y las ventanas contra el Bloc de notas.
 * Las pasa GitHub en un Windows en cada cambio, y también se pueden pasar en tu PC: «asistente probar-windows».
 * En GitHub (variable CI) se cierra el Bloc de notas al terminar; en tu PC se deja abierto para que lo cierres tú
 * (el Bloc de notas de Windows 11 lleva todas tus pestañas en el mismo programa).
 */
public final class PruebasWindows {
    private static int fallos = 0;

    public static void main(String[] args) throws Exception {
        boolean ci = System.getenv("CI") != null;
        ManosRobot manos = new ManosRobot(false);
        Ventanas v = new VentanasNativas(false);
        String nombre = "prueba-asistente-" + System.currentTimeMillis();
        Path fichero = Files.createTempFile(nombre, ".txt");
        String clave = fichero.getFileName().toString().replace(".txt", "");

        System.out.println("1) Abrir el Bloc de notas con un fichero propio y vacío");
        v.abrir("notepad.exe", List.of(fichero.toString()));
        boolean aparece = esperar(() -> v.abiertas().contains(clave), 20_000);
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

        System.out.println("4) Un diálogo escondido: Ctrl+O abre un diálogo («Abrir» o «¿Guardar cambios?») y tiene que verse");
        manos.tecla("ctrl+o", 1);
        boolean dialogo = esperar(() -> lineaDe(v.abiertas(), clave).contains("diálogos abiertos dentro"), 10_000);
        ok(dialogo, "ventanas_abiertas enseña el diálogo dentro del Bloc de notas: " + lineaDe(v.abiertas(), clave));
        long t0 = System.currentTimeMillis();
        try {
            v.campoConFoco();
        } catch (IllegalStateException e) {
            System.out.println("   (aviso esperado posible) " + e.getMessage());
        }
        ok(System.currentTimeMillis() - t0 < 5_000, "con un diálogo abierto, leer_campo no se queda colgado (" + (System.currentTimeMillis() - t0) + " ms)");
        manos.tecla("Escape", 1);
        ok(esperar(() -> !lineaDe(v.abiertas(), clave).contains("diálogos abiertos dentro"), 10_000), "tras Escape, el diálogo ya no está");

        System.out.println("5) La captura");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(manos.captura()));
        ok(img.getWidth() == manos.escala().anchoImagen() && img.getHeight() == manos.escala().altoImagen(), "captura de " + img.getWidth() + "×" + img.getHeight());
        ok(variada(img), "la captura no está en blanco ni negra");
        Files.write(Path.of("captura-prueba.png"), manos.captura());

        System.out.println("6) Velocidad");
        long t1 = System.nanoTime();
        for (int k = 0; k < 10; k++) {
            v.activa();
        }
        long ms = (System.nanoTime() - t1) / 10_000_000;
        ok(ms < 200, "ventana_activa tarda " + ms + " ms (antes, con PowerShell, segundos)");

        terminar(manos, v);
        Files.deleteIfExists(fichero);
        if (!ci) {
            System.out.println("\nCierra la pestaña «" + clave + "» del Bloc de notas sin guardar.");
        }
    }

    private static void terminar(ManosRobot manos, Ventanas v) {
        if (System.getenv("CI") != null) {
            ProcessHandle.allProcesses().filter(p -> p.info().command().orElse("").toLowerCase().endsWith("notepad.exe")).forEach(ProcessHandle::destroy);
        }
        System.out.println(fallos == 0 ? "\nPRUEBAS EN WINDOWS: TODO BIEN" : "\nPRUEBAS EN WINDOWS: " + fallos + " FALLO(S)");
        System.exit(fallos == 0 ? 0 : 1);
    }

    private static String lineaDe(String abiertas, String clave) {
        for (String l : abiertas.split("\n")) {
            if (l.contains(clave)) {
                return l;
            }
        }
        return "";
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
