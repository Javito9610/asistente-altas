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
            ok(varios.contains("encaja igual de bien con") || varios.startsWith("Pulsado") || varios.startsWith("No hay ningún"), "apodo ambiguo: " + varios);
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
            String nunca = ctrl.esperar("ventana-que-no-existe-jamas-98765", null,
                    es.amagna.asistente.manos.Controles.Que.APAREZCA, null, 2);
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

        System.out.println("11) Solo cierra lo que ha abierto él (lo demás no se toca)");
        String mia = "prueba-cerrar";
        v.abrir(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                List.of("-cp", System.getProperty("java.class.path"), "es.amagna.asistente.VentanaDePrueba", mia, "formulario"));
        ok(ctrl.esperar(mia, null, es.amagna.asistente.manos.Controles.Que.APAREZCA, null, 25).startsWith("Ya está"),
                "se abre una ventana nuestra para probarlo");
        // Un asistente que no la ha abierto no puede cerrarla, por mucho que se lo pidan.
        String ajena = new VentanasNativas(false).cerrarSiEsNuestra(mia);
        System.out.println("   otro asistente intenta cerrarla: " + ajena);
        ok(ajena.startsWith("NO SE CIERRA"), "no se cierra una ventana que no ha abierto él: la deja en paz y lo dice");
        // La nuestra sí.
        String propia = ((VentanasNativas) v).cerrarSiEsNuestra(mia);
        System.out.println("   la que abrió él: " + propia);
        ok(propia.startsWith("Cerrada"), "la que ha abierto él sí la cierra");
        ok(ctrl.esperar(mia, null, es.amagna.asistente.manos.Controles.Que.DESAPAREZCA, null, 10).startsWith("Ya está"),
                "y se queda cerrada de verdad");

        System.out.println("12) El guion: la tarea entera sin IA, comprobada dos veces y sin repetirse");
        String vent = "prueba-guion";
        Path dirG = Files.createTempDirectory("guion-prueba");
        Path fMapa = dirG.resolve("mapa.json");
        Path fGuion = dirG.resolve("guion.json");
        String exeJava = Path.of(System.getProperty("java.home"), "bin", "java").toString().replace("\\", "\\\\");
        String cp = System.getProperty("java.class.path").replace("\\", "\\\\");
        Files.writeString(fMapa, """
                {
                  "nombre": "Ficha de prueba",
                  "ventana": "%s",
                  "programa": {"ejecutable": "%s", "argumentos": ["-cp", "%s", "es.amagna.asistente.VentanaDePrueba", "%s", "formulario"]},
                  "controles": {
                    "nif":     ["#noExisteYa", "campo junto a NIF"],
                    "importe": ["campo junto a Importe"],
                    "guardar": ["boton:Guardar"]
                  }
                }""".formatted(vent, exeJava, cp, vent), java.nio.charset.StandardCharsets.UTF_8);
        String claveGuion = "prueba-" + System.nanoTime();
        Files.writeString(fGuion, """
                {
                  "clave": "%s",
                  "pasos": [
                    {"hacer": "esperar",   "control": "nif", "que": "aparezca", "segundos": 30},
                    {"hacer": "escribir",  "control": "nif", "valor": "00000000T"},
                    {"hacer": "escribir",  "control": "importe", "valor": "1.234,56 €"},
                    {"hacer": "comprobar", "control": "nif", "valor": "00000000T"},
                    {"hacer": "pulsar",    "control": "guardar", "toca": true}
                  ]
                }""".formatted(claveGuion), java.nio.charset.StandardCharsets.UTF_8);

        es.amagna.asistente.guion.Mapa mapa = es.amagna.asistente.guion.Mapa.leer(fMapa);
        es.amagna.asistente.guion.Guion gui = new es.amagna.asistente.guion.Guion(ctrl, (VentanasNativas) v, mapa, l -> System.out.println("   " + l));
        System.out.println("   " + gui.abrirSiHaceFalta());
        ok(v.abiertas().contains(vent), "el guion abre solo el programa que dice su mapa");

        // Ensayo: lo recorre entero y encuentra todo, pero no escribe nada.
        var ensayo = gui.ejecutar(fGuion, true);
        ok(ensayo.bien(), "ensayo: recorre el guion entero y encuentra todos los controles");
        ok("".equals(ctrl.valorDe(vent, "campo junto a NIF")), "ensayo: y NO ha escrito nada de verdad");
        ok(ensayo.avisos() >= 1, "ensayo: avisa de que «#noExisteYa» ya no vale y ha tirado del siguiente apodo");

        // De verdad: escribe, comprueba cada paso y hace la doble lectura final.
        var hecho = gui.ejecutar(fGuion, false);
        ok(hecho.bien(), "el guion hace la tarea entera y la da por buena: " + hecho.resumen());
        ok("00000000T".equals(ctrl.valorDe(vent, "campo junto a NIF"))
                && "1.234,56 €".equals(ctrl.valorDe(vent, "campo junto a Importe")), "y los datos están escritos EXACTOS");
        ok(hecho.lineas().stream().anyMatch(l -> l.contains("doble lectura")), "hace la comprobación final de todo lo escrito");

        // Y no se repite: esto es lo que impide dar dos veces la misma alta.
        var otra = gui.ejecutar(fGuion, false);
        System.out.println("   al repetirlo: " + otra.resumen());
        ok(!otra.bien() && otra.resumen().startsWith("NO SE REPITE"), "un trabajo ya hecho NO se vuelve a hacer");
        Files.deleteIfExists(es.amagna.asistente.guion.Guion.carpetaHechos().resolve(claveGuion + ".txt"));

        System.out.println("13) Cuando el guion se atasca, entra la IA, lo arregla y el guion sigue");
        // Un paso imposible a propósito: comprobar un valor que nadie ha puesto. En vez de la IA de verdad se pone
        // aquí un rescate de mentira que hace lo que haría ella: ver qué falta y dejarlo listo.
        Path fAtasco = dirG.resolve("atasco.json");
        Files.writeString(fAtasco, """
                {
                  "clave": "atasco-%s",
                  "pasos": [
                    {"hacer": "comprobar", "control": "importe", "valor": "999,00 €"},
                    {"hacer": "comprobar", "control": "nif", "valor": "00000000T"}
                  ]
                }""".formatted(System.nanoTime()), java.nio.charset.StandardCharsets.UTF_8);
        boolean[] llamada = {false};
        var conIa = new es.amagna.asistente.guion.Guion(ctrl, (VentanasNativas) v, mapa, l -> System.out.println("   " + l))
                .conRescate((ventanaR, controlR, queria, porQue) -> {
                    llamada[0] = true;
                    ctrl.escribir(ventanaR, mapa.apodos(controlR).get(0), "999,00 €");
                    return "He visto que «" + controlR + "» estaba vacío y lo he dejado como tenía que estar.";
                });
        var rescatado = conIa.ejecutar(fAtasco, false);
        ok(llamada[0], "el guion llama a la IA cuando se atasca, no se rinde");
        ok(rescatado.bien() && rescatado.rescates() == 1, "la IA lo arregla y el guion sigue por donde iba: " + rescatado.resumen());
        ok(rescatado.lineas().stream().anyMatch(l -> l.contains("RESCATADO")), "queda escrito que ese paso lo salvó la IA");
        // Y si la IA tampoco puede, se para: no sigue a ciegas.
        Path fImposible = dirG.resolve("imposible.json");
        Files.writeString(fImposible, """
                {"pasos": [{"hacer": "comprobar", "control": "nif", "valor": "ESTO NO VA A PASAR"}]}""",
                java.nio.charset.StandardCharsets.UTF_8);
        var rendido = new es.amagna.asistente.guion.Guion(ctrl, (VentanasNativas) v, mapa, l -> System.out.println("   " + l))
                .conRescate((a1, b1, c1, d1) -> "no he sabido arreglarlo")
                .ejecutar(fImposible, false);
        ok(!rendido.bien(), "si ni la IA puede, para y lo dice (no sigue a ciegas)");

        System.out.println("14) Cartografiar: lo que propone la IA se comprueba uno por uno contra el programa");
        // Se simula lo que contestaría la IA: unas formas buenas y otras que ya no valen.
        var propuesta = new es.amagna.asistente.guion.Aprendiz.Propuesta(new java.util.LinkedHashMap<>(Map.of(
                "nif", List.of("#inventado", "campo junto a NIF"),
                "guardar", List.of("boton:Guardar"),
                "telefono", List.of("campo junto a Teléfono", "#tampocoExiste"))), new java.util.ArrayList<>());
        var verificada = es.amagna.asistente.guion.Aprendiz.verificar(ctrl, vent, propuesta, l -> System.out.println("   " + l));
        ok(verificada.controles().containsKey("nif") && verificada.controles().get("nif").equals(List.of("campo junto a NIF")),
                "de un control, se queda solo con las formas que funcionan de verdad");
        ok(verificada.controles().containsKey("guardar"), "lo que funciona entero se queda tal cual");
        ok(!verificada.controles().containsKey("telefono"), "lo que la IA se imaginó y no existe se queda fuera del mapa");
        Path fAprendido = dirG.resolve("aprendido.json");
        es.amagna.asistente.guion.Aprendiz.guardar(fAprendido, "Ficha aprendida", vent, "", List.of(), verificada);
        var releido = es.amagna.asistente.guion.Mapa.leer(fAprendido);
        ok(releido.controles().size() == 2 && releido.ventana().equals(vent), "el mapa aprendido se guarda y se vuelve a leer igual");

        System.out.println("14b) El plano del programa: leer los menús enteros sin abrir ni uno");
        // El Bloc de notas tiene menús (Archivo, Editar, Ver) y vale para probar lo mismo que haremos con MN.
        v.activar(clave);
        var rutas = ctrl.rutasDeMenu(clave, 6);
        System.out.println("   menús que se ven sin tocar nada: " + rutas.size());
        rutas.stream().limit(6).forEach(r -> System.out.println("     · " + String.join(" > ", r)));
        ok(rutas.size() >= 3, "se leen las opciones de menú de un programa sin abrir ninguna");
        // Sin dar por hecho el idioma de Windows: aquí sale «Archivo» y en el Windows de las pruebas «File».
        String menuArchivo = rutas.stream().map(r -> r.get(r.size() - 1))
                .filter(n -> n.equalsIgnoreCase("Archivo") || n.equalsIgnoreCase("File")).findFirst().orElse("");
        ok(!menuArchivo.isBlank(), "entre ellas está la de Archivo, se llame como se llame en este Windows ("
                + (menuArchivo.isBlank() ? "no está" : menuArchivo) + ")");
        // Y pulsar por RUTA, no por nombre suelto (en MN hay nombres repetidos en sitios distintos).
        String porRuta = ctrl.pulsarRuta(clave, List.of(menuArchivo));
        System.out.println("   pulsar por ruta: " + porRuta);
        ok(porRuta.startsWith("Pulsado") || porRuta.startsWith("Desplegado"), "se pulsa una opción siguiendo su ruta");
        manos.tecla("Escape", 1);
        String noExiste = ctrl.pulsarRuta(clave, List.of(menuArchivo, "Esto No Existe"));
        ok(noExiste.startsWith("No se encuentra"), "y si la ruta no existe lo dice, sin pulsar nada al azar");

        System.out.println("14c) El aviso: que dé señales de vida al terminar algo largo");
        long t4 = System.currentTimeMillis();
        es.amagna.asistente.manos.Aviso.avisar("Asistente Altas (prueba)", "Esto es el globito de aviso.", false);
        long tardoAviso = System.currentTimeMillis() - t4;
        System.out.println("   el aviso ha tardado " + tardoAviso + " ms");
        ok(tardoAviso < 4000, "avisar no bloquea el trabajo (" + tardoAviso + " ms)");
        ok(java.awt.SystemTray.isSupported(), "este Windows tiene bandeja de sistema para el globito");

        System.out.println("14d) Los secretos: la contraseña, cifrada por Windows y nunca a la vista");
        String nombreSecreto = "prueba-" + System.nanoTime();
        String laClave = "C0ntr@señaDePrueba-Ñandú!";
        es.amagna.asistente.manos.Secreto.guardar(nombreSecreto, laClave.toCharArray());
        ok(es.amagna.asistente.manos.Secreto.hay(nombreSecreto), "se guarda un secreto");
        char[] vuelto = es.amagna.asistente.manos.Secreto.leer(nombreSecreto);
        ok(vuelto != null && new String(vuelto).equals(laClave), "y se recupera EXACTO, con acentos y símbolos");
        Path ficheroSecreto = es.amagna.asistente.manos.Secreto.carpeta().resolve(nombreSecreto + ".dat");
        byte[] crudo = Files.readAllBytes(ficheroSecreto);
        ok(!new String(crudo, java.nio.charset.StandardCharsets.UTF_8).contains("C0ntr@")
                && !new String(crudo, java.nio.charset.StandardCharsets.UTF_16LE).contains("C0ntr@"),
                "en el fichero NO está la contraseña: Windows la ha cifrado con esta cuenta");
        ok(crudo.length > laClave.length() + 20, "lo guardado es un bloque cifrado, no el texto (" + crudo.length + " bytes)");
        ok(es.amagna.asistente.manos.Secreto.nombres().contains(nombreSecreto)
                && !String.join(",", es.amagna.asistente.manos.Secreto.nombres()).contains("C0ntr@"),
                "la lista enseña los nombres, nunca los valores");
        es.amagna.asistente.manos.Secreto.olvidar(nombreSecreto);
        ok(!es.amagna.asistente.manos.Secreto.hay(nombreSecreto) && es.amagna.asistente.manos.Secreto.leer(nombreSecreto) == null,
                "y se puede olvidar");

        System.out.println("14e) La sesión: que se reponga él solo cuando el programa se queda tonto");
        // Se monta un programa de mentira con su mapa, como si fuera MN.
        String vSesion = "prueba-sesion";
        Path mSesion = dirG.resolve("sesion.json");
        Files.writeString(mSesion, """
                {"nombre": "Programa de prueba", "ventana": "%s",
                 "programa": {"ejecutable": "%s", "argumentos": ["-cp", "%s", "es.amagna.asistente.VentanaDePrueba", "%s", "formulario"]},
                 "controles": {"nif": ["campo junto a NIF"]}}""".formatted(vSesion, exeJava, cp, vSesion),
                java.nio.charset.StandardCharsets.UTF_8);
        var sesion = new es.amagna.asistente.guion.Sesion(ctrl, (VentanasNativas) v,
                es.amagna.asistente.guion.Mapa.leer(mSesion), "", l -> System.out.println("   " + l));
        var antesDeNada = sesion.mirar();
        System.out.println("   con el programa cerrado: " + antesDeNada.comoEsta());
        ok(!antesDeNada.lista() && antesDeNada.comoEsta().contains("no está abierto"),
                "se da cuenta de que el programa no está abierto");
        var puesta = sesion.asegurar();
        System.out.println("   después de arreglarlo: " + puesta.comoEsta() + " (escalón " + puesta.escalon() + ")");
        ok(puesta.lista(), "lo abre él solo y lo deja listo, sin que nadie se lo diga");
        ok(sesion.mirar().lista(), "y al volver a mirar sigue listo");
        // Y comprueba que está donde tiene que estar, no solo que está abierto.
        var otroSitio = new es.amagna.asistente.guion.Sesion(ctrl, (VentanasNativas) v,
                es.amagna.asistente.guion.Mapa.leer(mSesion), "Empresa de prueba", l -> { }).mirar();
        ok(!otroSitio.lista() && otroSitio.comoEsta().contains("sitio equivocado"),
                "y si el título no dice lo que tiene que decir, NO da la sesión por buena");
        ((VentanasNativas) v).cerrarSiEsNuestra(vSesion);

        System.out.println("14f) El usuario junto a la contraseña, y el arranque al encender el ordenador");
        String cuenta = "prueba-cuenta-" + System.nanoTime();
        es.amagna.asistente.manos.Secreto.guardar(cuenta, "clave".toCharArray());
        es.amagna.asistente.manos.Secreto.guardarUsuario(cuenta, "pbancario2@ejemplo.test");
        ok("pbancario2@ejemplo.test".equals(es.amagna.asistente.manos.Secreto.usuario(cuenta)),
                "el usuario se guarda junto a su contraseña: sin él, la contraseña no sirve de nada");
        es.amagna.asistente.manos.Secreto.olvidar(cuenta);
        ok(es.amagna.asistente.manos.Secreto.usuario(cuenta) == null && !es.amagna.asistente.manos.Secreto.hay(cuenta),
                "y al olvidar la cuenta se van los dos");
        String estado = es.amagna.asistente.Arranque.ver();
        System.out.println("   arranque automático: " + (estado.startsWith("ERROR") ? "no instalado (correcto en una prueba)"
                : "instalado"));
        ok(estado.startsWith("ERROR") || estado.contains("AsistenteAltas"),
                "se puede consultar si el arranque automático está puesto, sin tocarlo");

        System.out.println("14g) Llegar solo hasta una pantalla: abrir el programa, entrar por el menú y leerla");
        // Lo mismo que hará con MN: si el programa no está abierto, lo abre él; después entra por el menú.
        String vIr = "prueba-navegar";
        Path mIr = dirG.resolve("navegar.json");
        Files.writeString(mIr, """
                {"nombre": "Navegar", "ventana": "%s",
                 "programa": {"ejecutable": "%s", "argumentos": ["-cp", "%s", "es.amagna.asistente.VentanaDePrueba", "%s", "formulario"]},
                 "controles": {"guardar": ["boton:Guardar"]}}""".formatted(vIr, exeJava, cp, vIr),
                java.nio.charset.StandardCharsets.UTF_8);
        var sesionIr = new es.amagna.asistente.guion.Sesion(ctrl, (VentanasNativas) v,
                es.amagna.asistente.guion.Mapa.leer(mIr), "", l -> System.out.println("   " + l));
        ok(!sesionIr.mirar().lista(), "de partida el programa no está abierto");
        ok(sesionIr.asegurar().lista(), "lo abre él solo, sin que nadie lo abra a mano");
        // Y una vez dentro, distingue la pantalla del menú desplegado.
        v.activar(clave);
        var antesMenu = es.amagna.asistente.guion.Explorador.ventanasAhora();
        ctrl.pulsarRuta(clave, List.of(menuArchivo));
        var queSalio = es.amagna.asistente.guion.Explorador.esperarPantalla(antesMenu, 4);
        System.out.println("   al pulsar un menú: ventana=" + queSalio.ventana() + " menu=" + queSalio.menu());
        if (!queSalio.menu()) {
            // Que la prueba diga QUÉ ha salido, para no andar adivinando qué clase de ventana usa este Windows.
            for (var e : VentanasNativas.instantanea().entrySet()) {
                if (!antesMenu.contains(e.getKey())) {
                    System.out.println("     ventana nueva: clase «"
                            + es.amagna.asistente.manos.Win32.clase(new com.sun.jna.Pointer(e.getKey()))
                            + "» título «" + e.getValue() + "»");
                }
            }
        }
        ok(queSalio.ventana() == null && queSalio.menu(),
                "sabe que lo que se ha abierto es un menú desplegado, no una pantalla");
        manos.tecla("Escape", 1);
        ((VentanasNativas) v).cerrarSiEsNuestra(vIr);

        System.out.println("15) No se lía con su propia consola, y despierta la ventana si está minimizada");
        // El título de una consola lleva dentro el comando que se escribió, así que buscar «MNprogram» encontraba
        // la consola donde se había escrito «MNprogram»: el asistente se leía a sí mismo.
        com.sun.jna.Pointer consola = es.amagna.asistente.manos.Win32.consola();
        String tituloConsola = consola == null ? "" : es.amagna.asistente.manos.Win32.titulo(consola);
        if (tituloConsola.isBlank()) {
            System.out.println("   (esta consola no tiene título; no se puede probar aquí)");
        } else {
            com.sun.jna.Pointer salio = ((VentanasNativas) v).handleDe(tituloConsola);
            System.out.println("   buscando el título de la propia consola: " + (salio == null ? "nada, como debe ser"
                    : "«" + es.amagna.asistente.manos.Win32.titulo(salio) + "»"));
            ok(salio == null || com.sun.jna.Pointer.nativeValue(salio) != com.sun.jna.Pointer.nativeValue(consola),
                    "nunca se devuelve a sí mismo: la consola no es una ventana de trabajo");
        }
        // Y una ventana minimizada se despierta sola: eso no se le pide a la persona.
        String dormida = "prueba-minimizada";
        v.abrir(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                List.of("-cp", System.getProperty("java.class.path"), "es.amagna.asistente.VentanaDePrueba", dormida, "formulario"));
        ok(ctrl.esperar(dormida, null, es.amagna.asistente.manos.Controles.Que.APAREZCA, null, 25).startsWith("Ya está"),
                "se abre la ventana que vamos a minimizar");
        com.sun.jna.Pointer hD = ((VentanasNativas) v).handleDe(dormida);
        es.amagna.asistente.manos.Win32.User32.I.ShowWindow(hD, es.amagna.asistente.manos.Win32.SW_MINIMIZE);
        manos.esperar(1);
        ok(es.amagna.asistente.manos.Win32.User32.I.IsIconic(hD), "la ventana está minimizada de verdad");
        String leido = ctrl.leer(dormida, "campo junto a NIF");
        System.out.println("   leyendo una ventana minimizada: " + leido);
        ok(!leido.startsWith("No ") && !es.amagna.asistente.manos.Win32.User32.I.IsIconic(hD),
                "la despierta él solo y la lee: a la persona no se le pide nada");
        ((VentanasNativas) v).cerrarSiEsNuestra(dormida);

        ((VentanasNativas) v).cerrarSiEsNuestra(vent);

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

    /**
     * Cierra NUESTRA pestaña del Bloc de notas y solo la nuestra. Primero la vacía y la guarda en su fichero (así no
     * pregunta si guardar) y después cierra la PESTAÑA con Ctrl+W. Cerrar la ventana entera no vale: en el Bloc de notas
     * de ahora una ventana lleva varias pestañas y se llevaría por delante las de la persona que esté usando el PC.
     */
    private static boolean cerrarPestana(ManosRobot manos, Ventanas v, String titulo) {
        v.cancelarBloqueos();
        if (!v.activar(titulo)) {
            return false;
        }
        manos.tecla("ctrl+a", 1);
        manos.tecla("Delete", 1);
        manos.tecla("ctrl+s", 1);
        manos.esperar(1.2);
        manos.tecla("ctrl+w", 1); // cerrar la pestaña (en el Bloc de notas clásico, la ventana: también vale)
        manos.esperar(1);
        if (!v.abiertas().contains(titulo)) {
            return true;
        }
        // Si el atajo no llegó, se pulsa el aspa de la pestaña por su nombre, sin coordenadas.
        try {
            String r = new es.amagna.asistente.manos.Controles((VentanasNativas) v).pulsar(titulo, "boton:Cerrar pestaña");
            System.out.println("   cerrar la pestaña por su nombre: " + r);
            manos.esperar(1);
        } catch (RuntimeException e) {
            System.out.println("   (no se ha podido pulsar el aspa de la pestaña: " + e.getMessage() + ")");
        }
        if (!v.abiertas().contains(titulo)) {
            return true;
        }
        manos.tecla("Escape", 1); // si pregunta si guardar, se deja como está
        v.cancelarBloqueos();
        System.out.println("   (no se ha podido cerrar «" + titulo + "»: ciérrala tú, sin guardar)");
        return false;
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
