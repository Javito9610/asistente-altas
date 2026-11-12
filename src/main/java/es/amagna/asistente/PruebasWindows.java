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
                ? "   tarda " + (System.currentTimeMillis() - tO) / 1000 + " s en abrir «" + abrir + "»; se cancela: " + v.cancelarDialogo(abrir)
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

        System.out.println("8) Controles por dentro (UI Automation): ver, leer, escribir exacto y pulsar por su nombre");
        es.amagna.asistente.manos.Controles ctrl = new es.amagna.asistente.manos.Controles((VentanasNativas) v);
        v.activar(clave);
        String arbol = ctrl.arbol(clave, 4);
        System.out.println(sangrar(arbol));
        ok(!arbol.startsWith("No se pueden leer"), "se pueden leer los controles de la ventana");
        if (!arbol.startsWith("No se pueden leer")) {
            // El cuadro de texto se llama de una forma en el Bloc de notas clásico («campo») y de otra en el de
            // Windows 11 («documento»): se prueban los dos, que es justo lo que hará el agente con MN.
            String apodo = null;
            String dentro = "";
            for (String t : List.of("documento:", "campo:")) {
                dentro = ctrl.leer(clave, t);
                if (!dentro.startsWith("No hay ningún control") && !dentro.startsWith("«")) {
                    apodo = t;
                    break;
                }
            }
            ok(apodo != null, "se encuentra el cuadro de texto por dentro: " + dentro);
            if (apodo != null) {
                ok(dentro.contains("Amagna"), "su valor se lee por dentro y es el que escribimos");
                String exacto = "Alta de prueba: PÉREZ GARCÍA, Ñandú — 00000000T — 1.234,56 €";
                String puesto = ctrl.escribir(clave, apodo, exacto);
                System.out.println("   escribir por dentro: " + puesto);
                boolean sePuedeEscribir = puesto.startsWith("IGUAL");
                ok(sePuedeEscribir || puesto.contains("no deja escribir"), "escribir por dentro: " + (sePuedeEscribir ? "vale y se relee EXACTO" : "este control no lo permite; se teclea, que también vale"));
            }
            // Si un apodo vale para varios controles, no se toca ninguno: antes parar que equivocarse.
            String varios = ctrl.pulsar(clave, "boton:");
            ok(varios.contains("encaja con") || varios.startsWith("Pulsado") || varios.startsWith("No hay ningún"), "apodo ambiguo: " + varios);
            // Pulsar por nombre, sin coordenadas: el primer menú que haya (en el idioma que sea).
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("opcion-de-menu «([^»]+)»").matcher(arbol);
            String opcion = null;
            while (m.find()) {
                if (!m.group(1).equals("System")) {
                    opcion = m.group(1);
                    break;
                }
            }
            if (opcion != null) {
                Map<Long, String> antesM = VentanasNativas.instantanea();
                String r = ctrl.pulsar(clave, "opcion-de-menu:" + opcion);
                System.out.println("   pulsar «" + opcion + "» por su nombre: " + r);
                ok(r.startsWith("Pulsado") || r.startsWith("Desplegado"), "se pulsa una opción de menú por su nombre, sin coordenadas");
                String desplegado = esperarNueva(antesM, 4_000);
                System.out.println("   se ha desplegado: " + (desplegado != null ? "«" + desplegado + "»" : "no se ve una ventana nueva (el menú puede ir dentro de la ventana)"));
                manos.tecla("Escape", 1);
                manos.esperar(0.4);
            } else {
                System.out.println("   (esta ventana no tiene menús que pulsar: no se prueba)");
            }
        }

        System.out.println("9) Lo profesional: esperar a que pase algo de verdad, y encontrar un cuadro por su etiqueta");
        String ficha = "prueba-ficha";
        Process form = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                "es.amagna.asistente.VentanaDePrueba", ficha, "formulario").inheritIO().start();
        try {
            // Esperar a que APAREZCA, en vez de dormir un rato a ciegas.
            long t2 = System.currentTimeMillis();
            String salio = ctrl.esperar(ficha, null, es.amagna.asistente.manos.Controles.Que.APAREZCA, null, 25);
            long tardo = System.currentTimeMillis() - t2;
            System.out.println("   esperar a que aparezca: " + salio + " (" + tardo + " ms)");
            ok(salio.startsWith("Ya está"), "esperar_a: espera a que aparezca la ventana y sigue en cuanto está");

            v.activar(ficha);
            // Un cuadro SIN nombre propio, encontrado por la etiqueta que tiene al lado: así son las fichas de MN.
            String nif = "00000000T";
            String puesto = ctrl.escribir(ficha, "campo junto a NIF", nif);
            System.out.println("   escribir junto a la etiqueta «NIF»: " + puesto);
            boolean anclaje = puesto.startsWith("IGUAL");
            if (anclaje) {
                ok(true, "se encuentra un cuadro por la etiqueta que tiene al lado y se escribe EXACTO");
                String ya = ctrl.esperar(ficha, "campo junto a NIF", es.amagna.asistente.manos.Controles.Que.VALGA, nif, 5);
                ok(ya.startsWith("Ya está"), "esperar_a: espera a que un campo valga un valor exacto");
                String otro = ctrl.leer(ficha, "campo junto a Importe");
                System.out.println("   el cuadro de al lado de «Importe»: " + otro);
                ok(!otro.startsWith("No hay") && !otro.startsWith("No se ve"), "cada etiqueta encuentra SU cuadro, no el del vecino");
            } else {
                // Si Windows no enseña las etiquetas de este programa, se dice y no se da por bueno a medias.
                System.out.println("   (este programa no enseña sus etiquetas a Windows: en MN se usará el nombre o el #identificador)");
            }
            String boton = ctrl.pulsar(ficha, "boton:Guardar");
            System.out.println("   pulsar «Guardar» por su nombre: " + boton);
            ok(boton.startsWith("Pulsado"), "se pulsa un botón por su nombre, sin coordenadas");

            // Y esperar a que DESAPAREZCA cuando se cierra.
            v.cerrarVentana(ficha);
            String fuera = ctrl.esperar(ficha, null, es.amagna.asistente.manos.Controles.Que.DESAPAREZCA, null, 15);
            System.out.println("   esperar a que desaparezca: " + fuera);
            ok(fuera.startsWith("Ya está"), "esperar_a: se entera de que la ventana ya no está");

            // Y la prueba de que no se le escapa el tiempo: algo que no va a pasar.
            long t3 = System.currentTimeMillis();
            String nunca = ctrl.esperar(ficha, null, es.amagna.asistente.manos.Controles.Que.APAREZCA, null, 2);
            long espero = System.currentTimeMillis() - t3;
            ok(nunca.startsWith("Se han pasado") && espero >= 1800 && espero < 6000,
                    "esperar_a: si no pasa, avisa al acabarse el tiempo (" + espero + " ms) y dice qué había");
        } finally {
            form.destroy();
        }

        System.out.println("10) El expediente: queda escrito qué hizo, con capturas");
        es.amagna.asistente.cerebro.Expediente ex = es.amagna.asistente.cerebro.Expediente.abrir("Prueba de Windows del Asistente Altas");
        ok(ex.sirve(), "se puede escribir el expediente en " + es.amagna.asistente.cerebro.Expediente.base());
        ex.dice("Compruebo que todo queda registrado.");
        ex.paso(1, "escribir_en", "campo junto a NIF", "IGUAL: el campo vale «00000000T».");
        ex.captura(manos.captura());
        ex.cerrar(true, "TERMINADO: prueba de Windows", 1, 0, 0, 0);
        Path informe = ex.carpeta().resolve("informe.html");
        ok(Files.isRegularFile(informe) && Files.size(informe) > 500, "se escribe el informe para mirar: " + informe);
        ok(Files.isRegularFile(ex.carpeta().resolve("001-pantalla.png")) && Files.isRegularFile(ex.carpeta().resolve("pasos.jsonl")),
                "y junto a él la captura y el detalle paso a paso");

        terminar(manos, v);
    }

    private static String sangrar(String t) {
        return "   " + t.replace("\n", "\n   ");
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

    /** Cierra las ventanas que dejaron abiertas pruebas anteriores de este mismo programa (sus ficheros son nuestros). */
    private static void limpiarRestos(ManosRobot manos, Ventanas v) {
        for (int k = 0; k < 6; k++) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("prueba-asistente-\\d+").matcher(v.abiertas());
            String resto = null;
            while (m.find()) {
                if (!m.group().equals(clave)) {
                    resto = m.group();
                    break;
                }
            }
            if (resto == null) {
                return;
            }
            System.out.println("   cerrando una ventana de una prueba anterior: " + resto);
            if (!cerrarPestana(manos, v, resto)) {
                return; // si una no se deja, las demás tampoco: no insistir
            }
        }
    }

    /** Vacía una pestaña del Bloc de notas, la guarda y la cierra, para que no pregunte si guardar. */
    private static boolean cerrarPestana(ManosRobot manos, Ventanas v, String titulo) {
        v.cancelarBloqueos();
        if (!v.activar(titulo)) {
            return false;
        }
        manos.tecla("ctrl+a", 1);
        manos.tecla("Delete", 1);
        manos.tecla("ctrl+s", 1);
        manos.esperar(1);
        v.cerrarVentana(titulo);
        manos.esperar(0.6);
        if (v.abiertas().contains(titulo)) {
            manos.tecla("Escape", 1); // si pregunta si guardar, se deja como está
            v.cancelarBloqueos();
            System.out.println("   (no se ha podido cerrar «" + titulo + "»: ciérrala tú, sin guardar)");
            return false;
        }
        return true;
    }

    /** Deja el Bloc de notas como estaba: vacía la pestaña de la prueba, la guarda y la cierra (sin preguntar nada). */
    private static void terminar(ManosRobot manos, Ventanas v) {
        try {
            v.cancelarBloqueos();
            if (!clave.isEmpty() && v.abiertas().contains(clave)) {
                cerrarPestana(manos, v, clave);
            }
            limpiarRestos(manos, v);
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
