package es.amagna.asistente;

import es.amagna.asistente.cerebro.Agente;
import es.amagna.asistente.cerebro.ClaudeApi;
import es.amagna.asistente.manos.Escala;
import es.amagna.asistente.manos.FrenoDeEmergencia;
import es.amagna.asistente.manos.ManosRobot;
import es.amagna.asistente.manos.VentanasWindows;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * Asistente Altas (fase de pruebas, M1–M2). Uso:
 *   asistente mirar                      captura de pantalla a captura.png y datos de escala (no toca nada)
 *   asistente probar-manos               prueba de precisión del ratón y de tecleo en el Bloc de notas (sin IA)
 *   asistente guardar-clave              guarda la clave de la API (no se ve al escribir)
 *   asistente agente "tarea" [--vista] [--pasos N] [--modelo M]
 *                                         el agente hace la tarea; --vista = mira pero no toca nada
 * Freno de emergencia: lleva el ratón a la esquina superior izquierda.
 */
public final class App {
    public static final String MODELO_POR_DEFECTO = "claude-sonnet-5-5";

    public static void main(String[] args) throws Exception {
        String orden = args.length > 0 ? args[0] : "ayuda";
        try {
            switch (orden) {
                case "mirar" -> mirar();
                case "probar-manos" -> probarManos();
                case "guardar-clave" -> guardarClave();
                case "ventanas" -> System.out.println(new VentanasWindows(false).abiertas());
                case "campo" -> campo();
                case "agente" -> agente(Arrays.asList(args).subList(1, args.length));
                default -> System.out.println("""
                        Asistente Altas (pruebas)
                          asistente mirar
                          asistente probar-manos
                          asistente guardar-clave
                          asistente ventanas            (lista las ventanas abiertas, sin IA)
                          asistente campo               (lee el campo donde hagas clic, sin IA)
                          asistente agente "tarea" [--vista] [--pasos 20] [--tope-tokens 200000] [--modelo claude-sonnet-5-5]
                        Freno de emergencia: ratón a la esquina superior izquierda.""");
            }
        } catch (FrenoDeEmergencia e) {
            System.out.println("\n" + e.getMessage());
            System.exit(3);
        } catch (IllegalStateException | IllegalArgumentException e) {
            System.out.println("\nERROR: " + e.getMessage());
            System.exit(2);
        }
    }

    private static void mirar() throws IOException {
        ManosRobot m = new ManosRobot(true);
        Escala e = m.escala();
        Files.write(Path.of("captura.png"), m.captura());
        System.out.printf("Pantalla (lógica): %d × %d · escala de Windows: %.0f %%%n", e.anchoLogico(), e.altoLogico(), m.escalaWindows() * 100);
        System.out.printf("Lo que verá Claude: %d × %d (factor %.3f)%n", e.anchoImagen(), e.altoImagen(), e.factor());
        System.out.println("Guardada en captura.png. Ábrela: tiene que verse nítida y entera.");
    }

    private static void probarManos() throws Exception {
        ManosRobot m = new ManosRobot(false);
        Escala e = m.escala();
        System.out.println("1) Precisión del ratón: se mueve a 9 puntos y se mide dónde queda (no hace clic).");
        int peor = 0;
        int[][] puntos = {{10, 10}, {e.anchoImagen() / 2, 10}, {e.anchoImagen() - 11, 10},
                {10, e.altoImagen() / 2}, {e.anchoImagen() / 2, e.altoImagen() / 2}, {e.anchoImagen() - 11, e.altoImagen() / 2},
                {10, e.altoImagen() - 11}, {e.anchoImagen() / 2, e.altoImagen() - 11}, {e.anchoImagen() - 11, e.altoImagen() - 11}};
        for (int[] p : puntos) {
            int[] r = m.moverYMedir(p[0], p[1]);
            int d = Math.max(Math.abs(r[0] - r[2]), Math.abs(r[1] - r[3]));
            peor = Math.max(peor, d);
            System.out.printf("   captura (%4d,%4d) → esperado (%4d,%4d) · real (%4d,%4d) · desvío %d px%n", p[0], p[1], r[0], r[1], r[2], r[3], d);
        }
        System.out.println(peor <= 1 ? "   BIEN: desvío máximo " + peor + " px." : "   OJO: desvío máximo " + peor + " px (revisar la escala de pantalla).");
        m.mover(e.anchoImagen() / 2, e.altoImagen() / 2);

        System.out.println("2) Tecleo: abre un fichero NUEVO en el Bloc de notas y escribe un texto con ñ y acentos.");
        Path prueba = Path.of("build", "prueba-tecleo.txt").toAbsolutePath(); // siempre un fichero propio y vacío, nunca otra pestaña
        Files.createDirectories(prueba.getParent());
        Files.writeString(prueba, "");
        new ProcessBuilder("notepad.exe", prueba.toString()).start();
        m.esperar(4); // que el Bloc de notas esté abierto y con el foco antes de escribir
        m.escribir("Prueba del Asistente Altas 123");
        m.tecla("Return", 1);
        m.escribir("Ñandú, camión, pingüino: áéíóú ÁÉÍÓÚ ¿¡ @ # € / \\ 00000000T");
        m.tecla("Return", 1);
        System.out.println("   Mira la pestaña «prueba-tecleo.txt»: tienen que salir las dos líneas exactas. Ciérrala sin guardar.");
    }

    /** Prueba de lectura sin IA: en 5 segundos, haz clic en un campo de texto; dice qué control es y su texto exacto. */
    private static void campo() throws InterruptedException {
        System.out.println("Tienes 5 segundos: haz clic dentro de un campo de texto (por ejemplo, en el Bloc de notas).");
        Thread.sleep(5000);
        var c = new VentanasWindows(false).campoConFoco();
        System.out.println("Control: «" + c.nombre() + "» · tipo " + c.tipo() + " · ventana «" + c.ventana() + "»");
        System.out.println(c.valor() == null ? "Este control no deja leer su texto." : "Texto exacto: «" + c.valor() + "» (" + c.valor().length() + " caracteres)");
    }

    private static void guardarClave() throws IOException {
        Console c = System.console();
        if (c == null) {
            throw new IllegalStateException("Hace falta una consola para escribir la clave sin que se vea.");
        }
        char[] k = c.readPassword("Clave de la API de Claude (no se ve al escribir): ");
        if (k == null || k.length == 0) {
            throw new IllegalStateException("Clave vacía.");
        }
        Clave.guardar(new String(k));
        Arrays.fill(k, ' ');
        System.out.println("Guardada en " + Clave.fichero() + " (solo para tu usuario).");
    }

    private static void agente(List<String> a) throws IOException {
        if (a.isEmpty()) {
            throw new IllegalArgumentException("Falta la tarea. Ejemplo: asistente agente \"Abre el Bloc de notas y escribe hola\" --vista");
        }
        String tarea = a.get(0);
        boolean vista = a.contains("--vista");
        int pasos = a.contains("--pasos") ? Integer.parseInt(a.get(a.indexOf("--pasos") + 1)) : 20;
        String modelo = a.contains("--modelo") ? a.get(a.indexOf("--modelo") + 1) : MODELO_POR_DEFECTO;
        long tope = a.contains("--tope-tokens") ? Long.parseLong(a.get(a.indexOf("--tope-tokens") + 1)) : Agente.TOPE_TOKENS_POR_DEFECTO;

        Path dir = Path.of("registros");
        Files.createDirectories(dir);
        Path log = dir.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
        var registro = (java.util.function.Consumer<String>) linea -> {
            System.out.println(linea);
            try {
                Files.writeString(log, linea + System.lineSeparator(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException ignorada) {
                // el registro no debe parar al agente
            }
        };
        registro.accept("Tarea: " + tarea + (vista ? "   [MODO VISTA: no toca nada]" : "") + "   · modelo " + modelo + " · tope " + pasos + " pasos y " + tope + " tokens");
        long t0 = System.currentTimeMillis();
        Agente ag = new Agente(new ClaudeApi(Clave.leer()), new ManosRobot(vista), new VentanasWindows(vista), modelo, instrucciones("base"), pasos, tope, registro);
        Agente.Resultado r = ag.ejecutar(tarea);
        registro.accept("");
        registro.accept(r.mensaje());
        registro.accept(String.format("Pasos: %d · %.0f s · tokens: %d de entrada (+%d leídos de caché), %d de salida", r.pasos(),
                (System.currentTimeMillis() - t0) / 1000.0, r.tokensEntrada(), r.tokensCache(), r.tokensSalida()));
        registro.accept("Registro: " + log.toAbsolutePath());
        System.exit(r.terminado() ? 0 : 1);
    }

    static String instrucciones(String nombre) throws IOException {
        try (InputStream in = App.class.getResourceAsStream("/instrucciones/" + nombre + ".md")) {
            if (in == null) {
                throw new IllegalStateException("Faltan las instrucciones «" + nombre + "».");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
