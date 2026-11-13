package es.amagna.asistente;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * El arranque: que el asistente se ponga en marcha solo cuando se enciende el ordenador.
 *
 * <h2>Por qué una tarea programada y NO un servicio de Windows</h2>
 * La tentación es hacer un servicio de Windows, que suena más serio. Pero un servicio corre en la «sesión 0», que no
 * tiene escritorio: no ve ventanas, no puede mover el ratón ni escribir con el teclado. Nuestro asistente trabaja
 * EXACTAMENTE como lo haría una persona delante de la pantalla, así que un servicio no le serviría de nada.
 *
 * Lo que necesita es una sesión de Windows abierta con su escritorio. Por eso se instala como tarea programada que
 * arranca AL INICIAR SESIÓN. Es también como funcionan las herramientas profesionales de automatización de escritorio
 * cuando trabajan sin nadie delante.
 *
 * Consecuencia práctica para el PC dedicado: ese ordenador tiene que iniciar sesión solo al encenderse, y la sesión
 * debe quedarse abierta (sin bloquear la pantalla). Si la pantalla se bloquea, el teclado y el ratón dejan de llegar
 * a los programas; leer por dentro sigue funcionando, pero escribir no.
 *
 * @package Amagna\Altas
 */
public final class Arranque {
    public static final String NOMBRE = "AsistenteAltas";

    private Arranque() {
    }

    /** Deja escrito que al iniciar sesión se ejecute esto. */
    public static String instalar(Path asistenteCmd, String queHacer) throws IOException {
        String orden = "cmd /c \"\"" + asistenteCmd.toAbsolutePath() + "\" " + queHacer + "\"";
        List<String> cmd = new ArrayList<>(List.of("schtasks.exe", "/Create", "/TN", NOMBRE, "/SC", "ONLOGON",
                "/TR", orden, "/RL", "HIGHEST", "/F"));
        return ejecutar(cmd);
    }

    public static String quitar() throws IOException {
        return ejecutar(List.of("schtasks.exe", "/Delete", "/TN", NOMBRE, "/F"));
    }

    public static String ver() throws IOException {
        return ejecutar(List.of("schtasks.exe", "/Query", "/TN", NOMBRE, "/V", "/FO", "LIST"));
    }

    public static boolean instalado() {
        try {
            return !ver().startsWith("ERROR");
        } catch (IOException e) {
            return false;
        }
    }

    private static String ejecutar(List<String> cmd) throws IOException {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String salida = new String(p.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset()).strip();
            int codigo = p.waitFor();
            return codigo == 0 ? salida : "ERROR (" + codigo + "): " + salida;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: interrumpido";
        }
    }
}
