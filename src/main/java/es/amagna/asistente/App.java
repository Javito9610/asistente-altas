package es.amagna.asistente;

import es.amagna.asistente.altas.Alta;
import es.amagna.asistente.altas.Lectura;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Asistente Altas (fase de pruebas, M1–M2). Uso:
 *   asistente mirar                      captura de pantalla a captura.png y datos de escala (no toca nada)
 *   asistente zips [--carpeta ruta] [--ver nombre.zip]
 *                                        lista los .zip; --ver enseña el interior de uno
 *                                        (si solo hay uno, se enseña también)
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

    /** Segundos máximos de cada orden (0 = sin límite). Se pisa con --tope-segundos N. Nada espera «mil años». */
    static int topeSegundos(String orden, List<String> args) {
        int i = args.indexOf("--tope-segundos");
        if (i >= 0 && i + 1 < args.size()) {
            return Integer.parseInt(args.get(i + 1));
        }
        return switch (orden) {
            case "pantalla" -> 240;
            case "guion", "sesion", "explorar", "aprender" -> 420;
            case "agente", "expediente-prueba" -> 900;
            case "receta" -> 600;
            case "tarea" -> 900;
            case "expedientes" -> args.contains("--hacer") ? 3600 : 120;
            case "alta" -> 1200;
            case "probar-windows", "arranque", "secretos", "secreto", "guardar-clave", "ayuda" -> 0;
            default -> 300;
        };
    }

    /** El vigilante: si la orden no ha terminado a tiempo, escribe qué ventanas hay y sale con el código 3. */
    private static void vigilar(String orden, String[] args) {
        int tope = topeSegundos(orden, Arrays.asList(args));
        if (tope <= 0) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(tope * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            System.out.println();
            System.out.println("TIEMPO AGOTADO: «" + orden + "» no ha terminado en " + tope + " s. Se corta. Ventanas ahora:");
            Thread v = new Thread(() -> System.out.println(new VentanasNativas(true).abiertas()));
            v.setDaemon(true);
            v.start();
            try {
                v.join(5000);
            } catch (InterruptedException ignorada) {
                // da igual
            }
            System.out.flush();
            Runtime.getRuntime().halt(3);
        }, "vigilante");
        t.setDaemon(true);
        t.start();
    }

    public static void main(String[] args) throws Exception {
        String orden = args.length > 0 ? args[0] : "ayuda";
        // Engancharse a los avisos de Windows cuesta milisegundos y ahorra segundos en cada paso. Si no se puede,
        // no pasa nada: todo sigue funcionando preguntando, como antes.
        es.amagna.asistente.manos.Percepcion.arrancar();
        vigilar(orden, args);
        try {
            switch (orden) {
                case "mirar" -> mirar();
                case "probar-manos" -> probarManos();
                case "guardar-clave" -> guardarClave();
                case "ventanas" -> System.out.println(new VentanasNativas(false).abiertas());
                case "expedientes" -> expedientes(Arrays.asList(args).subList(1, args.length));
                case "zips" -> zips(Arrays.asList(args).subList(1, args.length));
                case "leer" -> leer(Arrays.asList(args).subList(1, args.length));
                case "alta" -> alta(Arrays.asList(args).subList(1, args.length));
                case "campo" -> campo();
                case "entorno" -> System.out.println(es.amagna.asistente.manos.Entorno.informe());
                case "controles" -> controles(Arrays.asList(args).subList(1, args.length));
                case "probar-windows" -> PruebasWindows.main(new String[0]);
                case "expediente-prueba" -> expedientePrueba(Arrays.asList(args).subList(1, args.length));
                case "tarea" -> tarea(Arrays.asList(args).subList(1, args.length));
                case "receta" -> receta(Arrays.asList(args).subList(1, args.length));
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
                          asistente zips [--carpeta <ruta>] [--ver <nombre.zip>]
                                                        (los .zip de la bandeja; --ver enseña uno por dentro.
                                                         Si solo hay un .zip, también. La ruta está en entrada.json)
                          asistente leer [<nombre.zip>] [--carpeta <ruta>] [--a fichero.txt] [--json]
                                                        (la IA abre los papeles de un bloque y saca los datos del
                                                         alta: cliente, expedientes y qué es cada fichero. Si falta
                                                         algo o hay dudas, no sigue: lo dice)
                          asistente alta <lectura.json> [--mapa mapas\\mn.json] [--a ficha-cliente.txt]
                                                        (da de alta en Clientes solo si la lectura está LISTO,
                                                         con nombre, apellidos y NIF. Si está PARADO o falta un
                                                         dato, no abre MN. No guarda si la ficha no cuadra)
                          asistente entorno             (qué puede hacer esta máquina ahora mismo: si hay
                                                         pantalla, si la sesión está conectada, si el ratón sirve)
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

    /**
     * De un bloque leído (lectura.json) a sus expedientes en MN, uno por sociedad prestamista. Sin --hacer solo cuenta
     * lo que haría (no toca MN, no gasta IA). Con --hacer los crea en la Empresa de prueba y da el informe.
     *   asistente expedientes <lectura.json> <bloque.zip> [--carpeta ruta] [--hacer] [--sin-ia] [--repetir]
     */
    private static void expedientes(List<String> a) throws IOException {
        if (a.size() < 2 || a.get(0).startsWith("--") || a.get(1).startsWith("--")) {
            throw new IllegalArgumentException("Uso: asistente expedientes <lectura.json> <bloque.zip> [--hacer]");
        }
        Alta.Decision d = Alta.deTexto(Files.readString(Path.of(a.get(0)), StandardCharsets.UTF_8));
        if (!d.seguir()) {
            System.out.println(d.mensaje());
            System.exit(1);
        }
        Path zip = Path.of(a.get(1));
        if (!Files.isRegularFile(zip)) {
            zip = Zips.carpeta(a).resolve(a.get(1));
        }
        Zips.Bloque bloque = Zips.abrir(zip);
        if (!bloque.vale()) {
            throw new IllegalStateException("Ese bloque no se puede abrir. " + bloque.problema());
        }
        String nombreZip = zip.getFileName().toString();
        var plan = es.amagna.asistente.altas.Plan.de(d.lectura(), nombreZip);
        var res = es.amagna.asistente.altas.Pedido.de(d.lectura(), plan);
        Path trabajo = Path.of("trabajo", nombreZip.replaceAll("(?i)\\.zip$", "").replaceAll("[^A-Za-z0-9._-]+", "_")).toAbsolutePath();
        var informe = new StringBuilder();
        for (String n : res.noSeCrean()) {
            informe.append("  NO SE CREA: ").append(n).append('\n');
        }
        var corredor = a.contains("--hacer") ? corredorDeTareas(a) : null;
        int hechos = 0;
        int fallos = 0;
        for (var p : res.pedidos()) {
            var rutas = new ArrayList<String>();
            var faltan = new ArrayList<String>();
            var docs = es.amagna.asistente.altas.Pedido.buscar(p.ficherosDelBloque(), bloque);
            int n = 0;
            for (String ruta : p.ficherosDelBloque()) {
                var doc = docs.get(ruta);
                if (doc == null) {
                    faltan.add(ruta);
                    continue;
                }
                Path f = trabajo.resolve(p.clave().hashCode() + "-" + (++n) + "-" + es.amagna.asistente.altas.Pedido.nombreSeguro(doc.nombre()));
                if (corredor != null) {
                    Files.createDirectories(trabajo);
                    Files.write(f, doc.datos());
                }
                rutas.add(f.toString());
            }
            var params = es.amagna.asistente.altas.Pedido.conAdjuntos(p, rutas);
            informe.append("  EXPEDIENTE ").append(p.entidad()).append(" · ").append(params.get("descripcion"))
                    .append(" · contrario ").append(params.getOrDefault("contrario", "(ninguno)"))
                    .append(" · ").append(rutas.size()).append(" adjuntos\n");
            for (String av : p.avisos()) {
                informe.append("      aviso: ").append(av).append('\n');
            }
            for (String f : faltan) {
                informe.append("      aviso: el documento «").append(f).append("» no está en el bloque.\n");
            }
            if (corredor != null) {
                var r = corredor.correr("crear-expediente", params, p.clave(), a.contains("--repetir"));
                informe.append("      → ").append(r.mensaje()).append('\n');
                if (r.bien()) {
                    hechos++;
                } else {
                    fallos++;
                }
            }
        }
        System.out.println("\n---------- INFORME " + nombreZip + " ----------");
        System.out.print(informe);
        if (corredor == null) {
            System.out.println("(Solo informe: no se ha tocado MN. Para crearlos: --hacer)");
        } else {
            System.out.println("Hechos: " + hechos + " · parados: " + fallos + " · no se crean: " + res.noSeCrean().size());
        }
        System.exit(fallos == 0 ? 0 : 1);
    }

    /** Los .zip de la bandeja. --carpeta cambia la ruta solo esta vez. --ver enseña uno; si solo hay uno, también. */
    private static void zips(List<String> a) {
        System.out.println(Zips.informe(Zips.carpeta(a), a));
    }

    /** Leer los papeles de un bloque y sacar los datos del alta. */
    private static void leer(List<String> a) throws IOException {
        Path carpeta = Zips.carpeta(a);
        Path zip = zipQueToca(carpeta, a);
        System.out.println("Bloque: " + zip.toAbsolutePath());
        Zips.Bloque bloque = Zips.abrir(zip);
        if (!bloque.vale()) {
            throw new IllegalStateException("Ese bloque no se puede abrir. " + bloque.problema());
        }
        System.out.println("Documentos: " + bloque.documentos().size() + " · leyendo…");
        var esp = es.amagna.asistente.cerebro.Especialista.cargar("altas", MODELO_POR_DEFECTO, Agente.TOPE_TOKENS_POR_DEFECTO);
        long t0 = System.currentTimeMillis();
        Lectura.Resultado r = Lectura.leer(new ClaudeApi(Clave.leer()), esp.modelo(), esp.instrucciones(), bloque);
        String informe = Lectura.comoSeCuenta(bloque, r)
                + String.format("%n(%.0f s · modelo %s)%n", (System.currentTimeMillis() - t0) / 1000.0, esp.modelo());
        System.out.println();
        System.out.println(a.contains("--json") ? r.crudo() : informe);
        if (a.contains("--a")) {
            Path destino = Path.of(a.get(a.indexOf("--a") + 1));
            Files.writeString(destino, a.contains("--json") ? r.crudo() : informe, StandardCharsets.UTF_8);
            System.out.println("Guardado en " + destino.toAbsolutePath());
        }
        System.exit(r.completo() ? 0 : 1);
    }

    /**
     * Da de alta al cliente de una lectura ya hecha. Si la lectura no está LISTO, sale antes de tocar MN.
     */
    private static void alta(List<String> a) throws IOException {
        if (a.isEmpty() || a.get(0).startsWith("--")) {
            throw new IllegalArgumentException("Uso: asistente alta <lectura.json> [--mapa mapas\\mn.json] [--a ficha-cliente.txt]");
        }
        Path fichero = Path.of(a.get(0));
        if (!Files.isRegularFile(fichero)) {
            System.out.println("PARADO: no está el fichero «" + a.get(0) + "». Ejemplo: pruebas\\lectura-garcia.json");
            System.exit(1);
        }
        final String texto;
        try {
            texto = Files.readString(fichero, StandardCharsets.UTF_8);
        } catch (java.nio.file.NoSuchFileException e) {
            System.out.println("PARADO: no está el fichero «" + a.get(0) + "». Ejemplo: pruebas\\lectura-garcia.json");
            System.exit(1);
            return;
        }
        Alta.Decision d = Alta.deTexto(texto);
        System.out.println(d.mensaje());
        if (!d.seguir()) {
            System.exit(1);
        }
        Path mapa = Path.of(a.contains("--mapa") ? a.get(a.indexOf("--mapa") + 1) : "mapas\\mn.json");
        Path informe = Path.of(a.contains("--a") ? a.get(a.indexOf("--a") + 1) : "ficha-cliente.txt");
        int codigo = Alta.crearEnMn(d.lectura(), mapa, informe, System.out::println);
        System.exit(codigo);
    }

    /** El .zip que toca: el que te digan, o el único que haya. */
    private static Path zipQueToca(Path carpeta, List<String> a) {
        for (String t : a) {
            if (t.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
                Path suelto = Path.of(t);
                return Files.isRegularFile(suelto) ? suelto : carpeta.resolve(t);
            }
        }
        try (var hijos = Files.list(carpeta)) {
            List<Path> zips = hijos.filter(h -> h.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip"))
                    .sorted().toList();
            if (zips.size() == 1) {
                return zips.get(0);
            }
            throw new IllegalArgumentException(zips.isEmpty()
                    ? "No hay ningún .zip en " + carpeta.toAbsolutePath()
                    : "Hay " + zips.size() + " bloques en " + carpeta.toAbsolutePath() + ". Di cuál: "
                            + zips.stream().map(x -> x.getFileName().toString()).collect(java.util.stream.Collectors.joining(", ")));
        } catch (IOException e) {
            throw new IllegalStateException("No se puede mirar la carpeta " + carpeta.toAbsolutePath() + ": " + e.getMessage());
        }
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
        // Lo aprendido en pruebas anteriores (dónde está cada cosa en MN) pasa al agente como conocimiento propio.
        Path aprendido = Path.of("conocimiento", "aprendido.md");
        if (esp != null && Files.exists(aprendido)) {
            quienes += "\n\n# Lo que ya hemos aprendido de MN en pruebas anteriores (fíate de esto antes que de lo de arriba)\n"
                    + Files.readString(aprendido, StandardCharsets.UTF_8);
        }
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
        var grabadora = a.contains("--grabar") ? new es.amagna.asistente.cerebro.Receta.Grabadora() : null;
        Agente ag = new Agente(new ClaudeApi(Clave.leer()), new ManosRobot(vista), ventanas, modelo, quienes, pasos, tope, registro)
                .conControles(new Controles(ventanas))
                .conExpediente(!a.contains("--sin-expediente"))
                .grabando(grabadora);
        if (esp != null && !esp.puede().isEmpty()) {
            ag.soloPuede(esp.puede());
        }
        Agente.Resultado r = ag.ejecutar(tarea);
        registro.accept("");
        registro.accept(r.mensaje());
        // Cada línea «APRENDIDO: …» del agente se guarda: la próxima vez ya lo sabe sin volver a buscarlo.
        var nuevo = new StringBuilder();
        for (String linea : r.mensaje().split("\\R")) {
            if (linea.strip().startsWith("APRENDIDO:")) {
                nuevo.append("- ").append(linea.strip().substring("APRENDIDO:".length()).strip()).append('\n');
            }
        }
        if (grabadora != null) {
            String nombreReceta = a.get(a.indexOf("--grabar") + 1);
            if (r.terminado() && grabadora.cuantos() > 0) {
                Path f = Path.of("recetas", nombreReceta + ".json");
                var recetaNueva = grabadora.aReceta(nombreReceta, tarea, parametros(a));
                if (a.contains("--empalmar")) {
                    // «nombre:N»: se queda con los N primeros pasos de la receta vieja y se le pega lo que acaba de hacer la IA.
                    String[] e = a.get(a.indexOf("--empalmar") + 1).split(":");
                    Path vieja = Path.of("recetas", e[0] + ".json");
                    var v = es.amagna.asistente.cerebro.Receta.leer(vieja);
                    var unidos = new java.util.ArrayList<>(v.primeros(Integer.parseInt(e[1])));
                    unidos.addAll(recetaNueva.pasos());
                    Files.copy(vieja, Path.of("recetas", e[0] + ".json.bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    recetaNueva = new es.amagna.asistente.cerebro.Receta(e[0], v.tarea().isBlank() ? tarea : v.tarea(), unidos);
                    nombreReceta = e[0];
                    f = Path.of("recetas", e[0] + ".json");
                }
                recetaNueva.guardar(f);
                registro.accept("RECETA «" + nombreReceta + "» grabada: " + grabadora.cuantos() + " pasos en " + f.toAbsolutePath()
                        + ". Para repetirla sin IA: asistente receta " + nombreReceta + " " + parametros(a).keySet().stream()
                        .map(k -> "--param " + k + "=...").collect(java.util.stream.Collectors.joining(" ")));
            } else {
                registro.accept("RECETA «" + nombreReceta + "» NO grabada: la tarea no terminó bien.");
            }
        }
        if (nuevo.length() > 0 && esp != null) {
            Files.createDirectories(aprendido.getParent());
            Files.writeString(aprendido, nuevo.toString(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            registro.accept("Apuntado en " + aprendido.toAbsolutePath() + " (" + nuevo.toString().lines().count() + " cosas).");
        }
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

    /**
     * Crea UN expediente de prueba en la «Empresa de prueba» con el especialista de expedientes (ojos y manos + UIA).
     *   asistente expediente-prueba --cliente "texto" [--contrario "texto"] [--descripcion MICRO] [--ref "PRUEBA-1 (AND)"]
     */
    private static void expedientePrueba(List<String> a) throws IOException {
        // MN puede estar cerrado: se abre y se entra solo (en la empresa y el ejercicio de prueba, con la IA de guardia).
        var mapaMn = es.amagna.asistente.guion.Mapa.leer(Path.of("mapas", "mn.json"));
        var vn = new VentanasNativas(false);
        dejarMnListo(mapaMn, vn, new Controles(vn), a);
        String ventanasAhora = new VentanasNativas(true).abiertas();
        if (!ventanasAhora.contains("Empresa de prueba")) {
            System.out.println("PARADO: MN no está en «Empresa de prueba». No se toca nada.\n" + ventanasAhora);
            System.exit(2);
        }
        String cliente = a.contains("--cliente") ? a.get(a.indexOf("--cliente") + 1) : "";
        if (cliente.isBlank() && !a.contains("--solo-explorar") && !a.contains("--solo-contrario")) {
            throw new IllegalArgumentException("Falta --cliente \"texto con el que buscar al cliente de prueba\"");
        }
        String contrario = a.contains("--contrario") ? a.get(a.indexOf("--contrario") + 1) : "";
        String desc = a.contains("--descripcion") ? a.get(a.indexOf("--descripcion") + 1) : "MICRO";
        desc = es.amagna.asistente.altas.Plan.descripcionValida(desc).isEmpty() ? desc : es.amagna.asistente.altas.Plan.descripcionValida(desc);
        String ref = a.contains("--ref") ? a.get(a.indexOf("--ref") + 1) : "PRUEBA-1 (AND)";
        if (es.amagna.asistente.altas.Plan.descripcionValida(desc).isEmpty()) {
            throw new IllegalArgumentException("Descripción no válida: " + desc);
        }
        String tarea = a.contains("--solo-contrario")
                ? """
                Estás en la Empresa de prueba. NO crees expedientes nuevos. Abre desde la lista «Expedientes» el expediente
                de prueba MÁS RECIENTE (la última fila; doble clic) y explora, en ESE expediente, dos cosas:
                1) CONTRARIO: cómo se asigna uno. Mira la pestaña «Contrarios» y el campo/botón de contrario del formulario.
                   Se abre una ventana selectora (probablemente «Contrarios») con caja de búsqueda y lista. Describe sus
                   controles y cómo se maneja (qué lee UIA y qué no, dónde pulsar). Busca sin escribir nada y mira qué
                   contrarios hay; elige UNO existente (nunca «Crear…» ni cambiar CIF o denominación de ninguno), asígnalo,
                   comprueba con UIA o captura que queda en el expediente y guarda.
                2) ADJUNTOS: la pestaña «Adjuntos». Mira cómo se añade un fichero («Añadir Adjunto» / «Importar Adjuntos»).
                   El fichero de prueba es C:\\Proyectos\\asistente-altas\\pruebas\\adjunto-prueba.txt (ficticio). Añádelo
                   tecleando su ruta en el diálogo de Windows y comprueba que aparece en la lista de adjuntos. Guarda.
                Para cada cosa anota el orden exacto de pasos, los nombres exactos de los controles, los diálogos que salen
                y cualquier aviso. Cierra todo lo que abras (Cancelar/Cerrar). Si algo no está claro en 2 intentos, para y
                dilo. Termina con líneas «APRENDIDO: …», una por cosa.
                """
                : a.contains("--solo-explorar")
                ? """
                Estás en la Empresa de prueba. Hay un expediente de prueba ya guardado (el último de la lista «Expedientes»,
                «Expediente 34/2026» o el más reciente). NO crees ninguno nuevo. Ábrelo desde la lista (doble clic en su
                fila) y MIRA SIN CAMBIAR NADA dónde se asigna el USUARIO responsable (Lia Silva) y dónde va la REF. PROPIA:
                pestañas, «Alerta Ficha», «Resumen», columnas de la lista (desplázala a la derecha), menús de la lista,
                clic derecho en la fila. Ya sabemos que «Configuracion > Gestionar Campos» pide credenciales de
                supervisor: no insistas ahí. Si encuentras el sitio, rellénalo en ESTE expediente de prueba y comprueba que
                queda. Cierra todo lo que abras (Cancelar/Cerrar). Termina con líneas «APRENDIDO: …», una por cosa, con el
                nombre exacto del control y cómo se llega.
                """
                : "Crea UN expediente de prueba en la Empresa de prueba (solo datos ficticios).\n"
                + "- Entra en Bases de Datos > Expedientes > Expedientes (si no está abierta la lista) y pulsa Alta.\n"
                + "- Descripción Expediente: " + desc + "\n"
                + "- Ref. Propia (si existe un campo para ella; si no, sigue sin ella): " + ref + "\n"
                + "- Tipo de Expediente: Expediente Extrajudicial · Estado: Abierto · Etapa: PEND. ENVIAR · Usuario: Lia Silva (si no hay campo, sigue sin él)\n"
                + "- Cliente (busca por): " + cliente + "\n"
                + (contrario.isBlank() ? "- Contrario: no hay, déjalo vacío.\n" : "- Contrario (busca por): " + contrario + "\n")
                + (a.contains("--adjunto")
                        ? "- Adjunto: pestaña «Adjuntos» > «Añadir Adjunto» (despliega un menú que solo se ve en captura) > «Importar» > en el diálogo de Windows «Abrir» "
                        + "teclea la ruta completa " + a.get(a.indexOf("--adjunto") + 1) + " (compruébala con leer_campo) e Intro > en el diálogo «Documento» pulsa Aceptar. "
                        + "Comprueba que aparece en la lista de adjuntos ANTES de Guardar. Si MN no deja adjuntar antes de guardar, dilo (PARADO) y no sigas.\n"
                        + "- Contrario: pulsa el campo «Contrario» con el ratón; en la ventana «Contrarios» escribe el criterio en la caja, Intro "
                        + "(si sale el aviso «Seleccione una ficha», Aceptar), clic en la fila y «Seleccionar». UIA lee el contrario en el campo interior, no en el exterior.\n"
                        : "- Sin adjuntos en esta prueba.\n")
                + "Comprueba todo con UIA antes de Guardar, guarda, y cierra la ventana del expediente."
                + (a.contains("--explorar") ? """

                EXPLORACIÓN (solo porque esto es la Empresa de prueba): una vez guardado, reabre ese expediente
                desde la lista (doble clic en su fila, la última) y MIRA SIN CAMBIAR NADA dónde se asigna el
                USUARIO responsable (Lia Silva) y dónde está la REF. PROPIA: pestañas, «Alerta Ficha», «Resumen»,
                columnas de la lista, botón «Configuracion» (solo abrir, leer y cerrar con Cancelar/Cerrar), y el
                menú de la lista. Si encuentras dónde, ponlo y comprueba que queda; solo en este expediente de prueba.
                Termina con líneas «APRENDIDO: …» (una por cosa, con el nombre exacto del control y cómo se llega).
                """ : "\nSi aprendes algo útil de la pantalla (dónde está algo, un aviso, un truco), añade al final una línea «APRENDIDO: …» por cosa.");
        List<String> b = new java.util.ArrayList<>(List.of(tarea, "--como", "expedientes"));
        if ((a.contains("--solo-explorar") || a.contains("--solo-contrario")) && !a.contains("--tope-tokens")) {
            b.add("--tope-tokens");
            b.add("900000");
        }
        if ((a.contains("--explorar") || a.contains("--solo-explorar") || a.contains("--solo-contrario")) && !a.contains("--pasos")) {
            b.add("--pasos");
            b.add("100");
        }
        if (a.contains("--grabar")) {
            b.add("--grabar");
            b.add(a.get(a.indexOf("--grabar") + 1));
            for (var e : java.util.Map.of("cliente", cliente, "contrario", contrario, "descripcion", desc, "ref", ref).entrySet()) {
                if (!e.getValue().isBlank()) {
                    b.add("--param");
                    b.add(e.getKey() + "=" + e.getValue());
                }
            }
        }
        for (String x : List.of("--pasos", "--modelo", "--tope-tokens", "--sin-aviso", "--tope-segundos")) {
            int i = a.indexOf(x);
            if (i >= 0) {
                b.add(x);
                if (!x.equals("--sin-aviso")) {
                    b.add(a.get(i + 1));
                }
            }
        }
        agente(b);
    }

    /**
     * Hace una tarea del mapa de estados (estados/mn.json): reconoce dónde está MN, va hasta donde toca, comprueba cada
     * paso y, si algo se pierde, la IA da el empujón.
     *   asistente tarea crear-expediente --param descripcion=MICRO --param cliente=NIF [--param cliente_nombre=…] [--sin-ia]
     */
    private static void tarea(List<String> a) throws IOException {
        if (a.isEmpty()) {
            throw new IllegalArgumentException("Uso: asistente tarea <nombre> [--param k=v ...] [--mapa estados/mn.json] [--sin-ia]");
        }
        var corredor = corredorDeTareas(a);
        String clave = a.contains("--clave") ? a.get(a.indexOf("--clave") + 1) : "";
        long t0 = System.currentTimeMillis();
        var r = corredor.correr(a.get(0), parametros(a), clave, a.contains("--repetir"));
        System.out.println(r.mensaje() + String.format(" · %.0f s · %d empujones de la IA", (System.currentTimeMillis() - t0) / 1000.0, r.rescates()));
        System.exit(r.bien() ? 0 : 1);
    }

    private static boolean yaHecha(es.amagna.asistente.estados.Diario d, String clave) {
        try {
            return d.yaHecha(clave);
        } catch (IOException e) {
            throw new IllegalStateException("No se puede leer el diario de tareas: " + e.getMessage(), e);
        }
    }

    /** Lo que hace falta para correr tareas del mapa una detrás de otra con las mismas opciones. */
    interface CorredorDeTareas {
        es.amagna.asistente.estados.Ejecutor.Resultado correr(String tarea, java.util.Map<String, String> params, String clave, boolean repetir);
    }

    private static CorredorDeTareas corredorDeTareas(List<String> a) throws IOException {
        var mapa = es.amagna.asistente.estados.Mapa.leer(Path.of(a.contains("--mapa") ? a.get(a.indexOf("--mapa") + 1) : "estados/mn.json"));
        var mapaMn = es.amagna.asistente.guion.Mapa.leer(Path.of("mapas", "mn.json"));
        var ventanas = new VentanasNativas(false);
        var ctrl = new Controles(ventanas);
        var motor = new Agente(new ClaudeApi("sin-uso"), new ManosRobot(false), ventanas, MODELO_POR_DEFECTO, "", 1, 1, System.out::println)
                .conControles(ctrl);
        es.amagna.asistente.estados.Ejecutor.Hace hace = (paso, ps) -> {
            if ("abrir_mn".equals(paso.accion())) {
                try {
                    dejarMnListo(mapaMn, ventanas, ctrl, a);
                    return null;
                } catch (Exception e) {
                    return "no se ha podido abrir MN: " + e.getMessage();
                }
            }
            if ("auditar".equals(paso.accion())) {
                return auditarPantalla(paso, ps, ventanas, ctrl, a);
            }
            // El cerrojo: nada se toca si MN no está en la empresa de prueba.
            if (!ventanas.abiertas().contains("Empresa de prueba")) {
                return "PARADO: MN no está en «Empresa de prueba»; no se toca nada.";
            }
            return es.amagna.asistente.cerebro.Receta.paso(motor, paso, ps, 25);
        };
        es.amagna.asistente.estados.Ejecutor.Rescate rescate = null;
        if (!a.contains("--sin-ia") && Clave.hay()) {
            var esp = es.amagna.asistente.cerebro.Especialista.cargar("expedientes", MODELO_POR_DEFECTO, 400_000);
            rescate = (nombre, situacion) -> {
                Agente ag = new Agente(new ClaudeApi(Clave.leer()), new ManosRobot(false), ventanas, esp.modelo(), esp.instrucciones(),
                        25, 300_000, System.out::println).conControles(ctrl).conExpediente(true);
                var r = ag.ejecutar("El asistente está haciendo la tarea «" + nombre + "» en la Empresa de prueba y se ha atascado:\n"
                        + situacion + "\n\nTU TRABAJO NO ES TERMINAR LA TAREA. Es dejar MN en un punto limpio para que el asistente "
                        + "vuelva a empezar él solo: sin diálogos «Atención» o de aviso abiertos, sin formularios de expediente "
                        + "abiertos (Cancelar y «Sí» para salir sin guardar) y sin selectores abiertos. Si el formulario ya estaba "
                        + "GUARDADO, no lo toques y dilo. Termina con TERMINADO: y cómo ha quedado la pantalla.");
                System.out.println("  IA: " + r.mensaje());
            };
            System.out.println("IA de guardia: solo entra si algo se atasca (máximo 2 empujones).");
        }
        es.amagna.asistente.estados.Ejecutor.Observa observa = new es.amagna.asistente.estados.Ejecutor.Observa() {
            public String ventanas() {
                return ventanas.abiertas();
            }

            public String texto(String ventana) {
                try {
                    return ctrl.arbol(ventana, 6, 600);
                } catch (RuntimeException e) {
                    return "";
                }
            }
        };
        var ej = new es.amagna.asistente.estados.Ejecutor(mapa, observa, hace, rescate, null, System.out::println);
        var diario = new es.amagna.asistente.estados.Diario(Path.of("registro", "tareas-hechas.tsv"));
        return (tarea, params, clave, repetir) -> {
            // El diario: una tarea con clave que ya se hizo NO se repite (dos expedientes iguales es el peor error posible).
            if (yaHecha(diario, clave) && !repetir) {
                return new es.amagna.asistente.estados.Ejecutor.Resultado(true, "YA HECHA: la tarea con clave «" + clave
                        + "» está en el diario (registro\\tareas-hechas.tsv). No se repite. Si de verdad hay que repetirla: --repetir.", "", 0);
            }
            // Los botones se reconocen por su dibujo (se aprende solo, y solo vale si la tarea acaba bien y verificada).
            if (!a.contains("--sin-anclas")) {
                es.amagna.asistente.manos.Anclas.activar(Path.of("anclas"));
            }
            var r = ej.ejecutar(tarea, params);
            var anclas = es.amagna.asistente.manos.Anclas.actual();
            if (anclas != null) {
                if (r.bien()) {
                    int nuevas = anclas.confirmar();
                    if (nuevas > 0) {
                        System.out.println("  Anclas aprendidas: " + nuevas + " (carpeta anclas\\).");
                    }
                } else {
                    anclas.descartar();
                }
            }
            if (r.bien()) {
                try {
                    diario.hecha(clave, tarea, r.numero());
                } catch (IOException e) {
                    System.out.println("  OJO: no se ha podido apuntar en el diario: " + e.getMessage());
                }
            }
            return r;
        };
    }

    /** El auditor mira la ventana con los ojos y la compara con lo esperado. null = bien; si no, el motivo. */
    @SuppressWarnings("unchecked")
    private static String auditarPantalla(es.amagna.asistente.cerebro.Receta.Paso paso, java.util.Map<String, String> ps,
            VentanasNativas ventanas, Controles ctrl, List<String> a) {
        if (a.contains("--sin-auditor")) {
            System.out.println("  AUDITORÍA SALTADA (--sin-auditor): este paso queda sin segunda opinión.");
            return null;
        }
        if (!Clave.hay()) {
            System.out.println("  AUDITORÍA SALTADA: no hay clave de Claude. Este paso queda sin segunda opinión.");
            return null;
        }
        var esperado = new java.util.LinkedHashMap<String, String>();
        for (var e : ((java.util.Map<String, Object>) paso.in().get("esperado")).entrySet()) {
            String v = es.amagna.asistente.cerebro.Receta.sustituir(String.valueOf(e.getValue()), ps);
            if (v.matches("(?s).*\\{[a-z_]+}.*")) {
                continue; // dato opcional que esta vez no se ha dado
            }
            esperado.put(e.getKey(), v);
        }
        String titulo = es.amagna.asistente.estados.Ejecutor.tituloReal(ventanas.abiertas(), String.valueOf(paso.in().get("ventana")));
        if (titulo == null) {
            return "el auditor no encuentra la ventana «" + paso.in().get("ventana") + "»";
        }
        var d = ctrl.dibujar(titulo);
        if (d.imagen() == null || d.enBlanco()) {
            return "el auditor no ha podido ver la ventana: " + d.problema();
        }
        String modelo = a.contains("--modelo-auditor") ? a.get(a.indexOf("--modelo-auditor") + 1)
                : es.amagna.asistente.cerebro.Auditor.MODELO_POR_DEFECTO;
        var v = es.amagna.asistente.cerebro.Auditor.auditar(new ClaudeApi(Clave.leer()), modelo,
                d.pngQueCabe(es.amagna.asistente.manos.Escala.ANCHO_MAX, es.amagna.asistente.manos.Escala.ALTO_MAX), esperado,
                "la ventana «" + titulo + "» del programa");
        System.out.println("  " + v.resumen());
        return v.ok() ? null : v.resumen();
    }

    /** Los «--param nombre=valor» de la línea de órdenes. */
    static java.util.Map<String, String> parametros(List<String> a) {
        var m = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < a.size(); i++) {
            if (a.get(i).equals("--param-fichero")) {
                // Los datos con tildes van en un fichero UTF-8 (una línea clave=valor): por la línea de comandos de Windows se rompen.
                try {
                    for (String l : Files.readAllLines(Path.of(a.get(i + 1)), java.nio.charset.StandardCharsets.UTF_8)) {
                        int ig = l.indexOf('=');
                        if (ig > 0 && !l.stripLeading().startsWith("#")) {
                            m.put(l.substring(0, ig).strip(), l.substring(ig + 1).strip());
                        }
                    }
                } catch (IOException e) {
                    throw new IllegalArgumentException("No se puede leer el fichero de parámetros " + a.get(i + 1) + ": " + e.getMessage());
                }
            }
            if (a.get(i).equals("--param")) {
                String kv = a.get(i + 1);
                int ig = kv.indexOf('=');
                if (ig > 0) {
                    m.put(kv.substring(0, ig), kv.substring(ig + 1));
                }
            }
        }
        return m;
    }

    /** Reproduce una receta grabada, sin IA: asistente receta <nombre> --param k=v … [--segundos-por-paso N] */
    private static void receta(List<String> a) throws IOException {
        if (a.isEmpty()) {
            throw new IllegalArgumentException("Uso: asistente receta <nombre> [--param k=v ...] [--segundos-por-paso N]");
        }
        var rec = es.amagna.asistente.cerebro.Receta.leer(Path.of("recetas", a.get(0) + ".json"));
        var ventanas = new VentanasNativas(false);
        dejarMnListo(es.amagna.asistente.guion.Mapa.leer(Path.of("mapas", "mn.json")), ventanas, new Controles(ventanas), a);
        var ag = new Agente(new ClaudeApi("sin-uso"), new ManosRobot(false), ventanas, MODELO_POR_DEFECTO, "", 1, 1, System.out::println)
                .conControles(new Controles(ventanas));
        int seg = a.contains("--segundos-por-paso") ? Integer.parseInt(a.get(a.indexOf("--segundos-por-paso") + 1)) : 25;
        System.out.println("Receta «" + rec.nombre() + "»: " + rec.pasos().size() + " pasos, sin IA.");
        long t0 = System.currentTimeMillis();
        var r = rec.reproducir(ag, parametros(a), seg, System.out::println);
        System.out.println((r.bien() ? "HECHO: " : "FALLÓ: ") + r.mensaje() + String.format(" (%d/%d pasos, %.0f s)", r.pasosHechos(), r.pasosTotal(),
                (System.currentTimeMillis() - t0) / 1000.0));
        if (!r.bien() && !a.contains("--sin-ia") && Clave.hay() && !rec.tarea().isBlank()) {
            // El empujón: la receta se ha perdido (otra pantalla, otro sitio, algo nuevo). Entra la IA, mira dónde está
            // y remata la tarea; lo que haga se pega a lo que la receta sí había hecho y la receta queda reparada.
            String tarea = es.amagna.asistente.cerebro.Receta.sustituir(rec.tarea(), parametros(a))
                    + "\n\nUNA RECETA GRABADA SE HA PERDIDO. Hizo " + r.pasosHechos() + " de " + r.pasosTotal() + " pasos y falló en: "
                    + r.mensaje() + "\nLa pantalla puede estar en cualquier punto (otra ventana abierta, un aviso, MN en otra pantalla). "
                    + "Mira primero con ventanas_abiertas dónde estás, deja la pantalla limpia si hace falta (Cancelar sin guardar lo que "
                    + "sobre) y completa la tarea desde ahí hasta el final, sin repetir lo que ya esté hecho.";
            System.out.println("La IA da el empujón…");
            List<String> b = new java.util.ArrayList<>(List.of(tarea, "--como", "expedientes", "--sin-aviso",
                    "--grabar", rec.nombre() + "-remate", "--empalmar", rec.nombre() + ":" + r.pasosHechos()));
            for (var e : parametros(a).entrySet()) {
                b.add("--param");
                b.add(e.getKey() + "=" + e.getValue());
            }
            agente(b);
            return;
        }
        System.exit(r.bien() ? 0 : 1);
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
            boolean entrada = Controles.esDialogoDeEjercicio(vent) || Controles.esDialogoDeEmpresa(vent)
                    || (vent != null && vent.toLowerCase(java.util.Locale.ROOT).contains("control de acceso"));
            Agente ag = new Agente(new ClaudeApi(Clave.leer()), new ManosRobot(false), v, esp.modelo(),
                    esp.instrucciones(), esp.pasos(), esp.topeTokens(), System.out::println)
                    .conControles(new Controles(v))
                    .enEntrada(entrada)
                    .conExpediente(true);
            String extra = entrada ? """

                    ESTO ES LA ENTRADA DE MN. Normas estrictas:
                    - PRIMERO ventanas_abiertas y ver_controles del diálogo que haya (el título real es
                      «Selección de Ejercicio Contable», no un trozo «Ejercicio Contable»). NUNCA empieces
                      por captura ni por el menú de MNprogram.
                    - Si está «Selección de Ejercicio Contable»: última fila (2026) y boton:Seleccionar
                      (o doble clic). Que se vea «MNprogram - Abogados» detrás NO es haberlo pasado.
                      «Ya estaba pasada» no cuenta. Si el diálogo sigue ahí, púlsalo.
                    - NUNCA abras Contabilidad, Fichaje, Mi Empresa, Empresa 3/4, Alta, Baja ni Modificar.
                    - Avisos «Información»: Aceptar. No te quedes congelado.
                    """ : "";
            return ag.ejecutar("""
                    Una tarea automática se ha atascado en la ventana «%s» y tienes que desatascarla, nada más.

                    Lo que había que hacer: %s
                    Lo que ha contestado el ordenador: %s
                    %s
                    Mira qué está pasando de verdad con UIA (ventanas_abiertas, ver_controles). NO hagas el
                    resto de la tarea: de eso se encarga el guion después. No inventes datos. Si lo que pasa no es
                    cosa tuya o hay que decidir algo que no te corresponde, termina con PARADO explicándolo.
                    """.formatted(vent, queSeQueria, porQue, extra)).mensaje();
        };
    }

    /** Que MN esté abierto, dentro de la empresa y el ejercicio de prueba, y sin diálogos de entrada. Si no, para. */
    private static void dejarMnListo(es.amagna.asistente.guion.Mapa mapa, VentanasNativas ventanas, Controles ctrl, List<String> a)
            throws IOException {
        // Si el programa está cerrado, lo abre y entra.
        var sesion = new es.amagna.asistente.guion.Sesion(ctrl, ventanas, mapa, "", System.out::println).conEntradaDelMapa();
        boolean ejercicioAbierto = ventanas.handleDe(Controles.DIALOGO_EJERCICIO) != null;
        // La IA de guardia: solo entra si el guion se atasca con algo que no estaba previsto.
        // Con el diálogo de ejercicio abierto no: o se pulsa Seleccionar o se para. No se pasea por los menús.
        if (!ejercicioAbierto && !a.contains("--sin-ia") && Clave.hay()) {
            sesion.conRescate(rescateConIa(mapa.ventana()));
            System.out.println("IA de guardia: solo entra si algo se atasca.");
        } else if (ejercicioAbierto) {
            System.out.println("Diálogo de ejercicio abierto: solo Seleccionar, sin IA.");
        }
        var estado = sesion.asegurar();
        boolean dialogoSigue = ventanas.handleDe(Controles.DIALOGO_EJERCICIO) != null;
        boolean falloEjercicio = !estado.lista() && estado.comoEsta() != null
                && estado.comoEsta().contains("Ejercicio Contable");
        if (dialogoSigue || falloEjercicio) {
            String crudo = estado.comoEsta() == null ? "" : estado.comoEsta();
            int nl = crudo.indexOf('\n');
            String linea = nl < 0 ? crudo : crudo.substring(0, nl);
            if (!linea.startsWith("PARADO")) {
                linea = "PARADO: «Selección de Ejercicio Contable» sigue abierto. No se abre Clientes.";
            }
            System.out.println(linea);
            es.amagna.asistente.manos.Aviso.avisar("Asistente Altas",
                    linea.length() > 180 ? linea.substring(0, 180) : linea, true);
            System.exit(2);
        }
        if (!estado.lista()) {
            throw new IllegalStateException("No se ha podido dejar el programa listo: " + estado.comoEsta());
        }
        if (!mapa.debeDecir().isBlank()) {
            System.out.println("Comprobado que estamos en «" + mapa.debeDecir() + "».");
        }
        System.out.println("Programa listo (" + estado.comoEsta() + ").");
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

        dejarMnListo(mapa, ventanas, ctrl, a);

        // 2. Entrar por el menú.
        List<String> ruta = Arrays.asList(a.get(a.indexOf("--ruta") + 1).split("\\s*>\\s*"));
        System.out.println("Entrando en: " + String.join(" > ", ruta) + "…");
        var antes = es.amagna.asistente.guion.Explorador.ventanasAhora();
        String destino = ruta.get(ruta.size() - 1);
        String yaAbierta = ventanas.tituloDe(destino);
        boolean yaSirve = yaAbierta != null && !yaAbierta.contains(mapa.ventana())
                && !VentanasNativas.esRuidoDeBusqueda(yaAbierta);
        String r = ctrl.pulsarRuta(mapa.ventana(), ruta);
        if (!r.startsWith("Pulsado")) {
            throw new IllegalStateException("No se ha podido entrar: " + r);
        }
        // Si Clientes ya estaba abierto, no sale una ventana nueva: se usa esa.
        double esperaMenu = yaSirve ? Math.min(espera, 8) : espera;
        var salio = es.amagna.asistente.guion.Explorador.esperarPantalla(antes, esperaMenu);
        // Si el menú se pulsó pero no salió nada (MN recién reiniciado, menú sin desplegar), se vuelve a pulsar la ruta.
        for (int intento = 2; intento <= 4 && salio.ventana() == null && ventanas.tituloDe(destino) == null; intento++) {
            System.out.println("  La pantalla no ha salido: vuelvo a pulsar la ruta (intento " + intento + " de 4)…");
            var antes2 = es.amagna.asistente.guion.Explorador.ventanasAhora();
            String rr = ctrl.pulsarRuta(mapa.ventana(), ruta);
            System.out.println("  " + rr);
            salio = es.amagna.asistente.guion.Explorador.esperarPantalla(antes2, Math.min(espera, 20));
        }
        yaSirve = yaSirve && ventanas.tituloDe(destino) != null;
        String pantalla;
        if (salio.ventana() != null) {
            pantalla = es.amagna.asistente.guion.Explorador.tituloDe(salio.ventana());
            System.out.println("Se ha abierto: «" + pantalla + "»");
        } else if (yaSirve) {
            pantalla = yaAbierta;
            System.out.println("Ya estaba abierta: «" + pantalla + "»");
        } else {
            throw new IllegalStateException(salio.menu() ? "Eso es un submenú, no una pantalla: pon la ruta entera."
                    : salio.cargando() != null ? "Se ha quedado en «" + salio.cargando() + "» y no ha llegado a abrirse en " + espera + " s."
                    : "No se ha abierto ninguna pantalla en " + espera + " s.");
        }

        // 3. Y si hay que dar un paso más dentro (el botón de crear del listado → la ficha en blanco).
        if (a.contains("--luego")) {
            String boton = a.get(a.indexOf("--luego") + 1);
            System.out.println("Pulsando «" + boton + "» dentro…");
            // Pulsar insistiendo: por dentro, con el ratón y con Intro, hasta que salga la ventana nueva. Hay botones
            // (Alta de Expedientes) que por dentro no se dejan, y quedarse esperando sin hacer nada no vale.
                        java.util.regex.Pattern quiero = java.util.regex.Pattern.compile(
                    a.contains("--hasta") ? a.get(a.indexOf("--hasta") + 1) : "«Expediente \\d+/\\d+");
            // Solo vale la ventana que se espera (por defecto «Expediente nn/aaaa»): un tooltip o una captura de
            // pantalla del usuario no cuentan como «se ha abierto». Cada intento espera 20 s porque MN tarda.
            String r2 = ctrl.pulsarInsistiendo(pantalla, boton, () -> quiero.matcher(ventanas.abiertas()).find(), 20);
            if (!r2.startsWith("Pulsado")) {
                System.out.println("  VENTANAS ABIERTAS AHORA:\n" + ventanas.abiertas());
            }
            System.out.println("  " + r2);
            var m2 = quiero.matcher(ventanas.abiertas());
            if (m2.find()) {
                String t = ventanas.abiertas().substring(m2.start() + 1);
                pantalla = t.substring(0, t.indexOf('»'));
                System.out.println("Se ha abierto: «" + pantalla + "»");
            } else {
                throw new IllegalStateException("No se ha abierto «" + quiero.pattern() + "» tras pulsar " + boton + ". " + r2);
            }
        }

        // 3b. Y si hay un guion de exploración (pulsar pestañas, abrir selectores, volcar…), se hace aquí, en la
        // pantalla a la que se ha llegado. El guion decide sus ventanas por su cuenta.
        if (a.contains("--guion")) {
            var g = new es.amagna.asistente.guion.Guion(ctrl, ventanas, mapa, System.out::println);
            // «{ventana}» en el guion = el título real de la pantalla a la que se ha llegado (cambia: «Expediente 31/2026»).
            String texto = Files.readString(Path.of(a.get(a.indexOf("--guion") + 1)), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("{ventana}", pantalla.replace("\\", "\\\\").replace("\"", "\\\""));
            Path tmp = Files.createTempFile("guion-", ".json");
            Files.writeString(tmp, texto, java.nio.charset.StandardCharsets.UTF_8);
            var parte = g.ejecutar(tmp, false);
            Files.deleteIfExists(tmp);
            System.out.println(parte.resumen());
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
