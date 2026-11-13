package es.amagna.asistente;

import es.amagna.asistente.cerebro.Agente;
import es.amagna.asistente.cerebro.ClaudeApi;
import es.amagna.asistente.manos.Escala;
import es.amagna.asistente.manos.FrenoDeEmergencia;
import es.amagna.asistente.manos.ManosRobot;
import es.amagna.asistente.manos.Controles;
import es.amagna.asistente.manos.VentanasNativas;
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
 *   asistente agente "tarea" [--vista] [--pasos N] [--modelo M] [--sin-expediente]
 *                                         el agente hace la tarea; --vista = mira pero no toca nada.
 *                                         Deja un expediente con todo lo que hizo (pasos, capturas e informe.html)
 *                                         en %LOCALAPPDATA%\AsistenteAltas\expedientes
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
                case "ventanas" -> System.out.println(new VentanasNativas(false).abiertas());
                case "campo" -> campo();
                case "controles" -> controles(Arrays.asList(args).subList(1, args.length));
                case "probar-windows" -> PruebasWindows.main(new String[0]);
                case "agente" -> agente(Arrays.asList(args).subList(1, args.length));
                case "guion" -> guion(Arrays.asList(args).subList(1, args.length));
                case "aprender" -> aprender(Arrays.asList(args).subList(1, args.length));
                case "explorar" -> explorar(Arrays.asList(args).subList(1, args.length));
                case "agentes" -> es.amagna.asistente.cerebro.Especialista.NOMBRES.forEach(n -> {
                    try {
                        var e = es.amagna.asistente.cerebro.Especialista.cargar(n, MODELO_POR_DEFECTO, Agente.TOPE_TOKENS_POR_DEFECTO);
                        System.out.println("  " + n + "  ·  " + e.pasos() + " pasos, " + e.topeTokens() + " tokens"
                                + (e.puede().isEmpty() ? ", puede usarlo todo" : ", SOLO mira (" + e.puede().size() + " herramientas)"));
                    } catch (IOException ex) {
                        System.out.println("  " + n + "  ·  no se puede leer: " + ex.getMessage());
                    }
                });
                default -> System.out.println("""
                        Asistente Altas (pruebas)
                          asistente mirar
                          asistente probar-manos
                          asistente guardar-clave
                          asistente ventanas            (lista las ventanas abiertas, sin IA)
                          asistente campo               (lee el campo donde hagas clic, sin IA)
                          asistente controles "<ventana>" [--hondo N] [--a arbol.txt]
                                                        (los controles por dentro, sin IA)
                          asistente probar-windows      (pruebas de manos y ventanas en el Bloc de notas, sin IA)
                          asistente agentes             (los especialistas que hay)
                          asistente agente "tarea" [--como cartografo] [--vista] [--pasos 20] [--tope-tokens 200000]
                                                   [--modelo claude-sonnet-5-5] [--sin-expediente]
                          asistente explorar "<ventana>" <plano.json> [--hondo 8] [--sin-ia]
                                                        (el plano de TODO el programa, sin abrir nada; la IA dice
                                                         cuáles abren pantalla y cuáles ejecutan algo)
                          asistente explorar "<ventana>" <plano.json> --recorrer <carpeta> [--solo "Clientes,Expedientes"] [--despacio 3]
                                                        (entra en las pantallas aprobadas y guarda sus controles;
                                                         solo en la empresa de prueba)
                          asistente aprender "<ventana>" <mapa.json> [--programa "<exe>"]
                                                        (la IA mira el programa y escribe su mapa; el programa
                                                         comprueba una a una que todas sus formas funcionan)
                          asistente guion <mapa.json> <guion.json> [--ensayo] [--abrir] [--con-ia]
                                                        (hace la tarea sin IA, siempre igual y comprobándola;
                                                         --ensayo = lo recorre entero sin tocar nada)
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
        var c = new VentanasNativas(false).campoConFoco();
        System.out.println("Control: «" + c.nombre() + "» · tipo " + c.tipo() + " · ventana «" + c.ventana() + "»");
        System.out.println(c.valor() == null ? "Este control no deja leer su texto." : "Texto exacto: «" + c.valor() + "» (" + c.valor().length() + " caracteres)");
    }

    /** Los controles de una ventana, leídos del propio programa (sin IA, no gasta nada). */
    private static void controles(List<String> a) {
        if (a.isEmpty()) {
            throw new IllegalArgumentException("Falta la ventana. Ejemplo: asistente controles \"Bloc de notas\"");
        }
        int hondo = a.contains("--hondo") ? Integer.parseInt(a.get(a.indexOf("--hondo") + 1)) : 4;
        // Al volcarlo a un fichero no hay motivo para quedarse corto: una ficha de gestión tiene cientos de controles.
        int tope = a.contains("--tope") ? Integer.parseInt(a.get(a.indexOf("--tope") + 1)) : a.contains("--a") ? 3000 : 300;
        String arbol = new Controles(new VentanasNativas(false)).arbol(a.get(0), hondo, tope);
        if (!a.contains("--a")) {
            System.out.println(arbol);
            return;
        }
        Path f = Path.of(a.get(a.indexOf("--a") + 1));
        try {
            Files.writeString(f, arbol, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No se ha podido escribir en " + f + ": " + e.getMessage(), e);
        }
        System.out.println("Escrito en " + f.toAbsolutePath() + " · " + arbol.lines().count() + " líneas.");
        System.out.println("OJO: ahí va lo que hubiera en pantalla. Míralo antes de mandárselo a nadie.");
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
        // Un especialista trae sus propias instrucciones, sus límites y sus parámetros; --pasos y compañía los pisan.
        es.amagna.asistente.cerebro.Especialista esp = a.contains("--como")
                ? es.amagna.asistente.cerebro.Especialista.cargar(a.get(a.indexOf("--como") + 1), MODELO_POR_DEFECTO, Agente.TOPE_TOKENS_POR_DEFECTO)
                : null;
        String quienes = esp == null ? instrucciones("base") : esp.instrucciones();
        int pasos = a.contains("--pasos") ? Integer.parseInt(a.get(a.indexOf("--pasos") + 1)) : esp == null ? 20 : esp.pasos();
        String modelo = a.contains("--modelo") ? a.get(a.indexOf("--modelo") + 1) : esp == null ? MODELO_POR_DEFECTO : esp.modelo();
        long tope = a.contains("--tope-tokens") ? Long.parseLong(a.get(a.indexOf("--tope-tokens") + 1))
                : esp == null ? Agente.TOPE_TOKENS_POR_DEFECTO : esp.topeTokens();

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
        registro.accept("Tarea: " + tarea + (vista ? "   [MODO VISTA: no toca nada]" : "")
                + (esp == null ? "" : "   · especialista: " + esp.nombre() + (esp.puede().isEmpty() ? "" : " [SOLO MIRA]"))
                + "   · modelo " + modelo + " · tope " + pasos + " pasos y " + tope + " tokens");
        long t0 = System.currentTimeMillis();
        VentanasNativas ventanas = new VentanasNativas(vista);
        Agente ag = new Agente(new ClaudeApi(Clave.leer()), new ManosRobot(vista), ventanas, modelo, quienes, pasos, tope, registro)
                .conControles(new Controles(ventanas))
                .conExpediente(!a.contains("--sin-expediente"));
        if (esp != null && !esp.puede().isEmpty()) {
            ag.soloPuede(esp.puede());
        }
        Agente.Resultado r = ag.ejecutar(tarea);
        registro.accept("");
        registro.accept(r.mensaje());
        registro.accept(String.format("Pasos: %d · %.0f s · tokens: %d de entrada (+%d leídos de caché), %d de salida", r.pasos(),
                (System.currentTimeMillis() - t0) / 1000.0, r.tokensEntrada(), r.tokensCache(), r.tokensSalida()));
        registro.accept("Registro: " + log.toAbsolutePath());
        if (ag.expediente() != null && ag.expediente().sirve()) {
            registro.accept("Expediente (lo que hizo, con capturas): " + ag.expediente().carpeta().resolve("informe.html"));
        }
        System.exit(r.terminado() ? 0 : 1);
    }

    /** El guion: hacer una tarea de forma determinista con el mapa del programa, sin gastar un token. */
    private static void guion(List<String> a) throws IOException {
        if (a.size() < 2) {
            throw new IllegalArgumentException("Uso: asistente guion <mapa.json> <guion.json> [--ensayo] [--abrir]");
        }
        boolean ensayo = a.contains("--ensayo");
        es.amagna.asistente.guion.Mapa mapa = es.amagna.asistente.guion.Mapa.leer(Path.of(a.get(0)));
        VentanasNativas ventanas = new VentanasNativas(false);
        var guion = new es.amagna.asistente.guion.Guion(new Controles(ventanas), ventanas, mapa, System.out::println);
        System.out.println("Mapa: " + mapa.nombre() + " · ventana «" + mapa.ventana() + "»" + (ensayo ? "   [ENSAYO: no se toca nada]" : ""));
        if (a.contains("--abrir")) {
            System.out.println(guion.abrirSiHaceFalta());
        }
        if (a.contains("--con-ia") && !ensayo) {
            guion.conRescate(rescateConIa(mapa.ventana()));
            System.out.println("IA de guardia: solo entra si el guion se atasca.");
        }
        var parte = guion.ejecutar(Path.of(a.get(1)), ensayo);
        System.exit(parte.bien() ? 0 : 1);
    }

    /** La IA de guardia: cuando el guion se atasca, le pasa el problema con todo el contexto para que lo resuelva. */
    private static es.amagna.asistente.guion.Guion.Rescate rescateConIa(String ventana) throws IOException {
        var esp = es.amagna.asistente.cerebro.Especialista.cargar("desatascador", MODELO_POR_DEFECTO, 60_000);
        return (vent, control, queSeQueria, porQue) -> {
            VentanasNativas v = new VentanasNativas(false);
            Agente ag = new Agente(new ClaudeApi(Clave.leer()), new ManosRobot(false), v, esp.modelo(),
                    esp.instrucciones(), esp.pasos(), esp.topeTokens(), System.out::println)
                    .conControles(new Controles(v))
                    .conExpediente(true);
            return ag.ejecutar("""
                    Una tarea automática se ha atascado en la ventana «%s» y tienes que desatascarla, nada más.

                    Lo que había que hacer: %s
                    Lo que ha contestado el ordenador: %s

                    Mira qué está pasando de verdad (un aviso encima, otra pantalla, un campo obligatorio sin
                    rellenar, el programa aún cargando…) y déjalo de forma que ese paso se pueda hacer. NO hagas el
                    resto de la tarea: de eso se encarga el guion después. No inventes datos. Si lo que pasa no es
                    cosa tuya o hay que decidir algo que no te corresponde, termina con PARADO explicándolo.
                    """.formatted(vent, queSeQueria, porQue)).mensaje();
        };
    }

    /** El plano de todo un programa: primero mirando (gratis y sin riesgo), después entrando en lo aprobado. */
    private static void explorar(List<String> a) throws IOException {
        if (a.size() < 2) {
            throw new IllegalArgumentException("Uso: asistente explorar \"<ventana>\" <plano.json> [--hondo 8] [--recorrer <carpeta>]");
        }
        String ventana = a.get(0);
        Path fichero = Path.of(a.get(1));
        VentanasNativas ventanas = new VentanasNativas(false);
        Controles ctrl = new Controles(ventanas);
        if (a.contains("--recorrer")) {
            var plano = es.amagna.asistente.guion.Explorador.leerPlano(fichero);
            if (a.contains("--solo")) {
                List<String> quiero = Arrays.asList(a.get(a.indexOf("--solo") + 1).split(","));
                plano = es.amagna.asistente.guion.Explorador.soloEstas(plano, quiero);
                System.out.println("Solo se entra en lo que contenga: " + String.join(" · ", quiero));
            }
            long aprobadas = plano.stream().filter(es.amagna.asistente.guion.Explorador.Pantalla::seguro).count();
            System.out.println("Plano: " + plano.size() + " pantallas, " + aprobadas + " aprobadas para entrar.");
            double espera = a.contains("--despacio") ? Double.parseDouble(a.get(a.indexOf("--despacio") + 1)) : 1.5;
            System.out.println(es.amagna.asistente.guion.Explorador.recorrer(ctrl, ventanas, ventana, plano,
                    Path.of(a.get(a.indexOf("--recorrer") + 1)), espera, l -> System.out.println("  " + l)));
            return;
        }
        int hondo = a.contains("--hondo") ? Integer.parseInt(a.get(a.indexOf("--hondo") + 1)) : 8;
        var plano = es.amagna.asistente.guion.Explorador.plano(ctrl, ventana, hondo);
        if (plano.isEmpty()) {
            throw new IllegalStateException("No se ve ningún menú en «" + ventana + "». ¿Es la ventana principal del programa?");
        }
        if (!a.contains("--sin-ia")) {
            System.out.println("Leyendo el menú con la IA (" + plano.size() + " opciones), para distinguir las que abren una "
                    + "pantalla de las que ejecutan algo…");
            var juicios = es.amagna.asistente.guion.Explorador.clasificar(new ClaudeApi(Clave.leer()), MODELO_POR_DEFECTO, plano);
            plano = es.amagna.asistente.guion.Explorador.juntar(plano, juicios);
            System.out.println("La IA ha clasificado " + juicios.size() + " de " + plano.size() + ".");
        }
        es.amagna.asistente.guion.Explorador.guardarPlano(fichero, ventana, plano);
        long seguras = plano.stream().filter(es.amagna.asistente.guion.Explorador.Pantalla::seguro).count();
        System.out.println("Plano de «" + ventana + "»: " + plano.size() + " opciones de menú.");
        System.out.println("  " + seguras + " parecen pantallas (abrir: true)");
        System.out.println("  " + (plano.size() - seguras) + " NO se tocan (hacen algo, no abren nada):");
        plano.stream().filter(p -> !p.seguro()).limit(25).forEach(p -> System.out.println("     · " + p.comoTexto() + " — " + p.motivo()));
        System.out.println("Escrito en " + fichero.toAbsolutePath() + ".");
        System.out.println("REPÁSALO: pon «abrir»: false en lo que no quieras que toque, y luego --recorrer.");
    }

    /** Cartografiar un programa: lo mira la IA, lo verifica el programa. */
    private static void aprender(List<String> a) throws IOException {
        if (a.size() < 2) {
            throw new IllegalArgumentException("Uso: asistente aprender \"<ventana>\" <mapa.json> [--programa \"<exe>\"]");
        }
        String ventana = a.get(0);
        Path destino = Path.of(a.get(1));
        String exe = a.contains("--programa") ? a.get(a.indexOf("--programa") + 1) : "";
        VentanasNativas ventanas = new VentanasNativas(false);
        Controles ctrl = new Controles(ventanas);
        String arbol = ctrl.arbol(ventana, 6);
        if (arbol.startsWith("No ")) {
            throw new IllegalStateException(arbol);
        }
        System.out.println("Mirando «" + ventana + "»… (" + arbol.lines().count() + " controles)");
        var propuesta = es.amagna.asistente.guion.Aprendiz.proponer(new ClaudeApi(Clave.leer()), MODELO_POR_DEFECTO, arbol);
        System.out.println("La IA propone " + propuesta.controles().size() + " controles. Comprobándolos uno a uno…");
        var buena = es.amagna.asistente.guion.Aprendiz.verificar(ctrl, ventana, propuesta, l -> System.out.println("  " + l));
        es.amagna.asistente.guion.Aprendiz.guardar(destino, ventana, ventana, exe, List.of(), buena);
        System.out.println("Mapa escrito en " + destino.toAbsolutePath() + ". Repásalo y corrige los nombres que no te cuadren.");
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
