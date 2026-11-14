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
                case "secreto" -> guardarSecreto(Arrays.asList(args).subList(1, args.length));
                case "sesion" -> sesion(Arrays.asList(args).subList(1, args.length));
                case "arranque" -> arranque(Arrays.asList(args).subList(1, args.length));
                case "pantalla" -> pantalla(Arrays.asList(args).subList(1, args.length));
                case "secretos" -> {
                    var n = es.amagna.asistente.manos.Secreto.nombres();
                    System.out.println(n.isEmpty() ? "No hay ningún secreto guardado." : "Secretos guardados (solo los nombres):");
                    for (String x : n) {
                        String u = es.amagna.asistente.manos.Secreto.usuario(x);
                        System.out.println("  " + x + (u == null ? "  (sin usuario guardado)" : "  ·  usuario: " + u));
                    }
                    System.out.println("Están en " + es.amagna.asistente.manos.Secreto.carpeta()
                            + ", cifrados con tu cuenta de Windows: nadie más puede leerlos, ni en otro ordenador.");
                }
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
                          asistente pantalla <mapa.json> --ruta "Bases de Datos > Clientes" [--luego "boton:Crear"]
                                         [--a fichero.txt] [--esperar 60]
                                                        (abre el programa si hace falta, navega hasta la pantalla y
                                                         enseña sus controles: sin tocar nada a mano)
                          asistente arranque --instalar <mapa.json> [--entrar <g>] [--debe-decir "..."] | --quitar | --ver
                                                        (que al encender el ordenador se abra solo y entre)
                          asistente sesion <mapa.json> [--entrar guion-entrada.json] [--debe-decir "Empresa de prueba"]
                                                        (comprueba que el programa está vivo y dentro; si no, lo
                                                         arregla: avisos, menús, pantallas sueltas, reiniciar y entrar)
                          asistente secreto <nombre> [--usuario <u>] [--comprobar] [--olvidar]
                                                        (guarda el usuario y su contraseña, cifrada con tu cuenta
                                                         de Windows)
                          asistente secretos            (los nombres de los secretos guardados, nunca su valor)
                          asistente explorar "<ventana>" <plano.json> [--hondo 8] [--sin-ia]
                                                        (el plano de TODO el programa, sin abrir nada; la IA dice
                                                         cuáles abren pantalla y cuáles ejecutan algo)
                          asistente explorar "<ventana>" <plano.json> --recorrer <carpeta> [--solo "Clientes,Expedientes"]
                                                        [--despacio 3] [--sin-aviso]
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
        if (!a.contains("--sin-aviso")) {
            es.amagna.asistente.manos.Aviso.avisar("Asistente Altas", r.mensaje(), !r.terminado());
        }
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
        if (!a.contains("--sin-aviso")) {
            es.amagna.asistente.manos.Aviso.avisar("Asistente Altas", parte.resumen(), !parte.bien());
        }
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

    /**
     * Llega él solo hasta una pantalla y la enseña: abre el programa si está cerrado, entra por el menú, y si hace
     * falta pulsa algo más (el botón de crear de un listado, para llegar a la ficha en blanco).
     */
    private static void pantalla(List<String> a) throws IOException {
        if (a.isEmpty() || !a.contains("--ruta")) {
            throw new IllegalArgumentException("Uso: asistente pantalla <mapa.json> --ruta \"Bases de Datos > Clientes\" [--luego \"boton:Crear\"] [--a fichero.txt]");
        }
        es.amagna.asistente.guion.Mapa mapa = es.amagna.asistente.guion.Mapa.leer(Path.of(a.get(0)));
        double espera = a.contains("--esperar") ? Double.parseDouble(a.get(a.indexOf("--esperar") + 1)) : 60;
        VentanasNativas ventanas = new VentanasNativas(false);
        Controles ctrl = new Controles(ventanas);

        // 1. Que el programa esté vivo y responda. Si está cerrado, lo abre.
        var sesion = new es.amagna.asistente.guion.Sesion(ctrl, ventanas, mapa, "", System.out::println).conEntradaDelMapa();
        // La IA de guardia: solo entra si el guion se atasca con algo que no estaba previsto.
        if (!a.contains("--sin-ia") && Clave.hay()) {
            sesion.conRescate(rescateConIa(mapa.ventana()));
            System.out.println("IA de guardia: solo entra si algo se atasca.");
        }
        var estado = sesion.asegurar();
        if (!estado.lista()) {
            throw new IllegalStateException("No se ha podido dejar el programa listo: " + estado.comoEsta());
        }
        if (!mapa.debeDecir().isBlank()) {
            System.out.println("Comprobado que estamos en «" + mapa.debeDecir() + "».");
        }
        System.out.println("Programa listo (" + estado.comoEsta() + ").");

        // 2. Entrar por el menú.
        List<String> ruta = Arrays.asList(a.get(a.indexOf("--ruta") + 1).split("\\s*>\\s*"));
        System.out.println("Entrando en: " + String.join(" > ", ruta) + "…");
        var antes = es.amagna.asistente.guion.Explorador.ventanasAhora();
        String r = ctrl.pulsarRuta(mapa.ventana(), ruta);
        if (!r.startsWith("Pulsado")) {
            throw new IllegalStateException("No se ha podido entrar: " + r);
        }
        var salio = es.amagna.asistente.guion.Explorador.esperarPantalla(antes, espera);
        if (salio.ventana() == null) {
            throw new IllegalStateException(salio.menu() ? "Eso es un submenú, no una pantalla: pon la ruta entera."
                    : salio.cargando() != null ? "Se ha quedado en «" + salio.cargando() + "» y no ha llegado a abrirse en " + espera + " s."
                    : "No se ha abierto ninguna pantalla en " + espera + " s.");
        }
        String pantalla = es.amagna.asistente.guion.Explorador.tituloDe(salio.ventana());
        System.out.println("Se ha abierto: «" + pantalla + "»");

        // 3. Y si hay que dar un paso más dentro (el botón de crear del listado → la ficha en blanco).
        if (a.contains("--luego")) {
            String boton = a.get(a.indexOf("--luego") + 1);
            System.out.println("Pulsando «" + boton + "» dentro…");
            var antes2 = es.amagna.asistente.guion.Explorador.ventanasAhora();
            String r2 = ctrl.pulsar(pantalla, boton);
            System.out.println("  " + r2);
            var salio2 = es.amagna.asistente.guion.Explorador.esperarPantalla(antes2, espera);
            if (salio2.ventana() != null) {
                pantalla = es.amagna.asistente.guion.Explorador.tituloDe(salio2.ventana());
                System.out.println("Se ha abierto: «" + pantalla + "»");
            } else {
                System.out.println("  (no se ha abierto otra ventana: la ficha debe de estar dentro de la misma)");
            }
        }

        // 4. Enseñarla.
        String arbol = ctrl.arbol(pantalla, 14, 3000);
        if (a.contains("--a")) {
            Path f = Path.of(a.get(a.indexOf("--a") + 1));
            Files.writeString(f, "Pantalla: " + String.join(" > ", ruta) + "\nVentana: " + pantalla + "\n\n" + arbol,
                    java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("Escrito en " + f.toAbsolutePath() + " · " + arbol.lines().count() + " líneas.");
            System.out.println("OJO: ahí va lo que hubiera en pantalla. Míralo antes de mandárselo a nadie.");
        } else {
            System.out.println(arbol);
        }
    }

    /** Que al iniciar sesión en Windows el asistente se ponga en marcha él solo. */
    private static void arranque(List<String> a) throws IOException {
        if (a.contains("--quitar")) {
            System.out.println(es.amagna.asistente.Arranque.quitar());
            return;
        }
        if (a.contains("--ver") || a.isEmpty()) {
            System.out.println(es.amagna.asistente.Arranque.instalado()
                    ? "Instalado:\n" + es.amagna.asistente.Arranque.ver()
                    : "No está instalado. Ponlo con: asistente arranque --instalar <mapa.json>");
            return;
        }
        if (!a.contains("--instalar")) {
            throw new IllegalArgumentException("Uso: asistente arranque --instalar <mapa.json> [--entrar <g>] [--debe-decir \"...\"] | --quitar | --ver");
        }
        String mapa = a.get(a.indexOf("--instalar") + 1);
        StringBuilder que = new StringBuilder("sesion \"" + Path.of(mapa).toAbsolutePath() + "\"");
        if (a.contains("--entrar")) {
            que.append(" --entrar \"").append(Path.of(a.get(a.indexOf("--entrar") + 1)).toAbsolutePath()).append('"');
        }
        if (a.contains("--debe-decir")) {
            que.append(" --debe-decir \"").append(a.get(a.indexOf("--debe-decir") + 1)).append('"');
        }
        que.append(" --sin-aviso");
        Path cmd = Path.of(System.getProperty("user.dir"), "asistente.cmd");
        if (!Files.isRegularFile(cmd)) {
            throw new IllegalStateException("No encuentro asistente.cmd en " + cmd + ". Lanza esto desde la carpeta del proyecto.");
        }
        String r = es.amagna.asistente.Arranque.instalar(cmd, que.toString());
        System.out.println(r);
        if (r.startsWith("ERROR")) {
            System.out.println("Si dice que no tienes permiso, abre la consola como administrador y repite.");
            System.exit(1);
        }
        System.out.println();
        System.out.println("Listo: al iniciar sesión en este Windows, el asistente abrirá el programa y entrará solo.");
        System.out.println("OJO, esto es importante para el PC dedicado:");
        System.out.println("  · Tiene que ser una tarea AL INICIAR SESIÓN, no un servicio de Windows. Un servicio no ve");
        System.out.println("    la pantalla y nuestro asistente trabaja como una persona: mirando y tecleando.");
        System.out.println("  · Ese ordenador debe iniciar sesión solo al encenderse, y NO bloquear la pantalla.");
        System.out.println("    Con la pantalla bloqueada se puede leer por dentro, pero no escribir.");
    }

    /** Deja el programa vivo, respondiendo y dentro. Es lo que se llama antes de cada trabajo de la noche. */
    private static void sesion(List<String> a) throws IOException {
        if (a.isEmpty()) {
            throw new IllegalArgumentException("Uso: asistente sesion <mapa.json> [--entrar <guion.json>] [--debe-decir \"Empresa de prueba\"]");
        }
        es.amagna.asistente.guion.Mapa mapa = es.amagna.asistente.guion.Mapa.leer(Path.of(a.get(0)));
        VentanasNativas ventanas = new VentanasNativas(false);
        String debeDecir = a.contains("--debe-decir") ? a.get(a.indexOf("--debe-decir") + 1) : "";
        var sesion = new es.amagna.asistente.guion.Sesion(new Controles(ventanas), ventanas, mapa, debeDecir, System.out::println)
                .conEntradaDelMapa();
        if (!a.contains("--sin-ia") && Clave.hay()) {
            sesion.conRescate(rescateConIa(mapa.ventana()));
            System.out.println("IA de guardia: solo entra si algo se atasca.");
        }
        if (a.contains("--entrar")) {
            sesion.conGuionDeEntrada(Path.of(a.get(a.indexOf("--entrar") + 1)));
        }
        var estado = sesion.asegurar();
        System.out.println(estado.lista() ? "SESIÓN LISTA: " + estado.comoEsta() : "SESIÓN NO LISTA: " + estado.comoEsta());
        if (!estado.lista() && !a.contains("--sin-aviso")) {
            es.amagna.asistente.manos.Aviso.avisar("Asistente Altas", "No he podido dejar el programa listo: " + estado.comoEsta(), true);
        }
        System.exit(estado.lista() ? 0 : 1);
    }

    /** Guarda una contraseña para que el asistente pueda volver a entrar él solo de noche. */
    private static void guardarSecreto(List<String> a) throws IOException {
        if (a.isEmpty()) {
            throw new IllegalArgumentException("Falta el nombre. Ejemplo: asistente secreto mn");
        }
        String nombre = a.get(0);
        if (a.contains("--olvidar")) {
            es.amagna.asistente.manos.Secreto.olvidar(nombre);
            System.out.println("Olvidado «" + nombre + "».");
            return;
        }
        // Comprobar que está bien guardada sin enseñarla: se descifra y se dice cómo es, no qué es.
        if (a.contains("--comprobar")) {
            String u = es.amagna.asistente.manos.Secreto.usuario(nombre);
            System.out.println("Usuario: " + (u == null ? "NO HAY" : u));
            if (!es.amagna.asistente.manos.Secreto.hay(nombre)) {
                System.out.println("Contraseña: NO HAY. Guárdala con «asistente secreto " + nombre + "».");
                System.exit(1);
            }
            char[] v = es.amagna.asistente.manos.Secreto.leer(nombre);
            if (v == null) {
                System.out.println("Contraseña: no se ha podido descifrar.");
                System.exit(1);
            }
            String pista = v.length < 3 ? "" : " · empieza por «" + v[0] + "» y acaba por «" + v[v.length - 1] + "»";
            System.out.println("Contraseña: guardada y se descifra bien · " + v.length + " caracteres" + pista);
            java.util.Arrays.fill(v, '\0');
            System.out.println("(No se enseña entera a propósito: el asistente solo la descifra para teclearla en el programa.)");
            return;
        }
        Console c = System.console();
        if (c == null) {
            throw new IllegalStateException("Hace falta una consola para escribir la contraseña sin que se vea.");
        }
        // El usuario no es secreto, pero sin él la contraseña no sirve: se piden los dos juntos.
        String yaHay = es.amagna.asistente.manos.Secreto.usuario(nombre);
        String usuario;
        if (a.contains("--usuario")) {
            usuario = a.get(a.indexOf("--usuario") + 1);
        } else {
            // La pregunta se escribe por la salida de siempre y se fuerza a que salga: así no depende de cómo trate
            // la consola los mensajes, que es justo lo que hizo que a Javi no le saliera.
            System.out.print("Usuario de «" + nombre + "»" + (yaHay == null ? "" : " [" + yaHay + ", Enter para dejarlo]") + ": ");
            System.out.flush();
            String leido = c.readLine();
            usuario = leido == null ? "" : leido.strip();
        }
        if (usuario.isBlank() && yaHay != null) {
            usuario = yaHay;
        }
        if (usuario.isBlank()) {
            throw new IllegalArgumentException("Hace falta el usuario. Si la consola no te deja escribirlo, ponlo en el "
                    + "propio comando: asistente secreto " + nombre + " --usuario tu.usuario@amagnalegal.com");
        }
        System.out.println("Usuario: " + usuario);
        System.out.print("Contraseña de «" + nombre + "» (no se ve al escribir): ");
        System.out.flush();
        char[] uno = c.readPassword();
        System.out.print("Otra vez, para asegurar: ");
        System.out.flush();
        char[] dos = c.readPassword();
        System.out.println();
        if (uno == null || uno.length == 0) {
            throw new IllegalArgumentException("No has escrito nada.");
        }
        if (!java.util.Arrays.equals(uno, dos)) {
            java.util.Arrays.fill(uno, '\0');
            java.util.Arrays.fill(dos, '\0');
            throw new IllegalArgumentException("No coinciden. No se ha guardado nada.");
        }
        java.util.Arrays.fill(dos, '\0');
        es.amagna.asistente.manos.Secreto.guardar(nombre, uno);
        es.amagna.asistente.manos.Secreto.guardarUsuario(nombre, usuario);
        System.out.println("Guardados el usuario «" + usuario + "» y su contraseña en " + es.amagna.asistente.manos.Secreto.carpeta() + ".");
        System.out.println("Cifrado con tu cuenta de Windows: solo tú, y solo en este ordenador. No está en el repositorio");
        System.out.println("ni se enseña en ningún sitio. En un guion se usan poniendo «usuario:" + nombre + "» y «secreto:"
                + nombre + "» como valor.");
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
            String parte = es.amagna.asistente.guion.Explorador.recorrer(ctrl, ventanas, ventana, plano,
                    Path.of(a.get(a.indexOf("--recorrer") + 1)), espera, l -> System.out.println("  " + l));
            System.out.println(parte);
            // Un recorrido largo acaba cuando la persona ya se ha ido a otra cosa: hay que avisarla.
            if (!a.contains("--sin-aviso")) {
                es.amagna.asistente.manos.Aviso.avisarYEsperar("Asistente Altas: recorrido terminado", parte, 120);
            }
            return;
        }
        int hondo = a.contains("--hondo") ? Integer.parseInt(a.get(a.indexOf("--hondo") + 1)) : 8;
        String titulo = ventanas.tituloDe(ventana);
        if (titulo == null) {
            throw new IllegalStateException("No hay ninguna ventana cuyo título contenga «" + ventana + "». Mira cuáles hay con «asistente ventanas».");
        }
        System.out.println("Mirando los menús de «" + titulo + "»…");
        if (ventanas.abiertas().contains("BLOQUEADA")) {
            System.out.println("AVISO: ese programa tiene un aviso o un menú abierto encima esperando respuesta. Mientras esté así "
                    + "no contesta a nada. Ciérralo y vuelve a lanzar esto.");
        }
        // Con tiempo máximo: si el programa está bloqueado, preguntarle por dentro no vuelve nunca, y quedarse mudo
        // para siempre es la peor forma de fallar.
        var plano = es.amagna.asistente.guion.Explorador.conTiempo(45,
                () -> es.amagna.asistente.guion.Explorador.plano(ctrl, ventana, hondo), null);
        if (plano == null) {
            throw new IllegalStateException("«" + titulo + "» no contesta (45 s). Casi seguro tiene un menú desplegado o un "
                    + "aviso esperando: míralo en pantalla, ciérralo (o cierra y vuelve a abrir el programa) y repite.");
        }
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
