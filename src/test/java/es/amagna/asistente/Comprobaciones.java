package es.amagna.asistente;

import es.amagna.asistente.altas.Lectura;
import es.amagna.asistente.cerebro.Agente;
import es.amagna.asistente.cerebro.Cerebro;
import es.amagna.asistente.manos.Escala;
import es.amagna.asistente.manos.Manos;
import es.amagna.asistente.manos.Teclas;
import java.awt.event.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Comprobaciones sin pantalla ni API (se pueden pasar en cualquier sitio): JSON, escala de coordenadas, teclas y el bucle
 * del agente con un Claude y unas manos de mentira. Uso: java -cp ... es.amagna.asistente.Comprobaciones
 */
public final class Comprobaciones {
    private static int bien = 0;

    public static void main(String[] args) {
        json();
        escala();
        teclas();
        agente();
        filas();
        zips();
        lectura();
        System.out.println("\nTODO BIEN: " + bien + " comprobaciones.");
    }

    static void ok(boolean c, String que) {
        if (!c) {
            throw new AssertionError("FALLA: " + que);
        }
        bien++;
        System.out.println("  ok  " + que);
    }

    static void json() {
        String t = "{\"a\":1,\"b\":[true,null,2.5,\"x\\\"y\\n ñ\"],\"c\":{\"d\":\"\\u00e1\"}}";
        Map<String, Object> m = Json.objeto(t);
        ok(m.get("a").equals(1L) && ((List<?>) m.get("b")).get(2).equals(2.5), "JSON: números y listas");
        ok(((Map<?, ?>) m.get("c")).get("d").equals("á"), "JSON: escapes unicode");
        ok(Json.objeto(Json.escribir(m)).equals(m), "JSON: ida y vuelta");
    }

    static void escala() {
        Escala e = Escala.para(1920, 1080);
        ok(e.anchoImagen() == 1280 && e.altoImagen() == 720, "1920×1080 → captura 1280×720");
        int peor = 0;
        for (int x = 0; x < e.anchoImagen(); x++) { // cada píxel de la captura cae dentro de su zona en pantalla
            int px = e.aPantalla(x, 0)[0];
            double ini = x / e.factor(), fin = (x + 1) / e.factor();
            if (px < Math.floor(ini) || px > Math.ceil(fin)) {
                peor++;
            }
        }
        ok(peor == 0, "1920×1080: cada píxel de la captura cae en su sitio de la pantalla");
        int[] centro = e.aPantalla(640, 360);
        ok(Math.abs(centro[0] - 960) <= 1 && Math.abs(centro[1] - 540) <= 1, "el centro de la captura es el centro de la pantalla");
        Escala w125 = Escala.para(1536, 864); // 1920×1080 al 125 %: Java ve 1536×864 lógicos
        ok(w125.anchoImagen() == 1280 && w125.aPantalla(1279, 719)[0] == 1535 && w125.aPantalla(1279, 719)[1] == 863, "pantalla al 125 %: la esquina cae en la esquina");
        Escala p = Escala.para(1280, 720);
        ok(p.factor() == 1.0 && p.aPantalla(100, 200)[0] == 100, "1280×720: sin reducir, mismos puntos");
        Escala k4 = Escala.para(2560, 1440);
        int[] ida = k4.aPantalla(500, 300);
        int[] vuelta = k4.aImagen(ida[0], ida[1]);
        ok(vuelta[0] == 500 && vuelta[1] == 300, "2560×1440: ida y vuelta exacta");
        try {
            e.aPantalla(1280, 10);
            ok(false, "fuera de la captura");
        } catch (IllegalArgumentException x) {
            ok(true, "un punto fuera de la captura no se pulsa: error");
        }
    }

    static void teclas() {
        ok(Teclas.combinacion("ctrl+s").equals(List.of(KeyEvent.VK_CONTROL, KeyEvent.VK_S)), "ctrl+s");
        ok(Teclas.combinacion("alt+F4").equals(List.of(KeyEvent.VK_ALT, KeyEvent.VK_F4)), "alt+F4");
        ok(Teclas.codigo("Return") == KeyEvent.VK_ENTER && Teclas.codigo("Page_Down") == KeyEvent.VK_PAGE_DOWN, "Return y Page_Down");
        try {
            Teclas.codigo("TeclaInventada");
            ok(false, "tecla inventada");
        } catch (IllegalArgumentException x) {
            ok(true, "una tecla desconocida no se pulsa: error");
        }
    }

    @SuppressWarnings("unchecked")
    static void agente() {
        List<String> hecho = new ArrayList<>();
        Manos manos = new Manos() {
            final Escala e = Escala.para(1280, 720);
            public byte[] captura() { hecho.add("captura"); return new byte[] {1, 2, (byte) hecho.size()}; } // cada captura distinta
            public byte[] zoom(int a, int b, int c, int d) { hecho.add("zoom"); return new byte[] {4}; }
            public Escala escala() { return e; }
            public void mover(int x, int y) { hecho.add("mover"); }
            public void clic(Integer x, Integer y, int b, int v, String m) { hecho.add("clic " + x + "," + y + " b" + b + " x" + v + (m == null ? "" : " " + m)); }
            public void arrastrar(int a, int b, int c, int d, String m) { hecho.add("arrastrar"); }
            public void bajarBotonIzquierdo() { }
            public void subirBotonIzquierdo() { }
            public void escribir(String t) { hecho.add("escribir " + t); }
            public void tecla(String c, int r) { if (c.equals("ctrl+inventada")) { throw new IllegalArgumentException("Tecla desconocida"); } hecho.add("tecla " + c + " x" + r); }
            public void mantenerTecla(String c, double s) { }
            public void rueda(String d, int n, Integer x, Integer y, String m) { hecho.add("rueda " + d + " " + n); }
            public void esperar(double s) { }
            public int[] posicion() { return new int[] {5, 6}; }
        };
        List<Map<String, Object>> peticiones = new ArrayList<>();
        List<Map<String, Object>> guion = new ArrayList<>(List.of(
                respuesta(pensar(), uso("screenshot", Map.of())),
                respuesta(pensar(), uso("left_click", Map.of("coordinate", List.of(100L, 200L))), uso("type", Map.of("text", "Ñandú")), uso("key", Map.of("text", "Tab", "repeat", 2L))),
                respuesta(pensar(), uso("screenshot", Map.of())),
                respuesta(pensar(), uso("key", Map.of("text", "ctrl+inventada")), uso("type", Map.of("text", "no debe escribirse"))),
                respuesta(pensar(), uso("screenshot", Map.of())),
                respuesta(pensar(), uso("screenshot", Map.of())),
                respuesta(pensar(), uso("screenshot", Map.of())),
                respuesta(pensar(), uso("screenshot", Map.of())),
                respuesta(pensar(), uso("screenshot", Map.of())),
                respuesta(texto("TERMINADO: escrito y comprobado."))));
        Cerebro claude = p -> {
            peticiones.add((Map<String, Object>) Json.leer(Json.escribir(p))); // copia, como lo vería la API
            return guion.remove(0);
        };
        List<String> log = new ArrayList<>();
        Agente.Resultado r = new Agente(claude, manos, "modelo-prueba", "instrucciones", 20, log::add).ejecutar("Escribe Ñandú");
        ok(r.terminado() && r.mensaje().startsWith("TERMINADO") && r.pasos() == 10, "el agente termina cuando Claude dice TERMINADO");
        ok(hecho.contains("clic 100,200 b1 x1") && hecho.contains("escribir Ñandú") && hecho.contains("tecla Tab x2"), "las manos hacen lo que dice Claude, en orden");
        ok(!hecho.contains("escribir no debe escribirse"), "tras una acción que falla, el resto del turno no se ejecuta");
        Map<String, Object> p0 = peticiones.get(0);
        Map<String, Object> herr = (Map<String, Object>) ((List<Object>) p0.get("tools")).get(0);
        ok(Agente.HERRAMIENTA.equals(herr.get("type")) && herr.get("cache_control") != null, "herramienta de ordenador actual, en caché");
        List<Object> msgs4 = (List<Object>) peticiones.get(4).get("messages");
        Map<String, Object> ultimo = (Map<String, Object>) msgs4.get(msgs4.size() - 1);
        Map<String, Object> tr = (Map<String, Object>) ((List<Object>) ultimo.get("content")).get(0);
        ok("computer".equals(tr.get("toolset_name")) && Boolean.TRUE.equals(tr.get("is_error")), "el error vuelve a Claude marcado como error");
        Map<String, Object> tr2 = (Map<String, Object>) ((List<Object>) ultimo.get("content")).get(1);
        ok(String.valueOf(tr2.get("content")).contains("Not executed"), "la acción siguiente se marca como no ejecutada");
        int[] imgs = peticiones.stream().mapToInt(p -> Json.escribir(p).split("\"type\":\"image\"", -1).length - 1).toArray();
        ok(java.util.Arrays.equals(imgs, new int[] {0, 1, 1, 2, 2, 3, 4, 5, 6, 3}), "capturas: se recortan por tandas, de 7 a 3 " + java.util.Arrays.toString(imgs));
        int antes = Json.escribir(peticiones.get(8)).split("\"type\":\"thinking\"", -1).length - 1;
        int despues = Json.escribir(peticiones.get(9)).split("\"type\":\"thinking\"", -1).length - 1;
        ok(antes == 8 && despues == 1, "al recortar, se quitan los pensamientos posteriores al corte y se conserva el anterior (" + antes + " → " + despues + ")");
        Map<String, Object> sinFin = respuesta(uso("screenshot", Map.of()));
        Agente.Resultado tope = new Agente(p -> respuesta(uso("screenshot", Map.of())), manos, "m", "i", 3, s -> { }).ejecutar("bucle");
        ok(!tope.terminado() && tope.mensaje().startsWith("PARADO") && tope.mensaje().contains("tope de 3 pasos"), "con el tope de pasos, se para");

        // Atascado: la pantalla no cambia → aviso a la 3.ª captura igual y parada a la 5.ª (sin seguir gastando).
        Manos quietas = new Manos() {
            public byte[] captura() { return new byte[] {9, 9, 9}; }
            public byte[] zoom(int a, int b, int c, int d) { return new byte[] {9}; }
            public Escala escala() { return Escala.para(1280, 720); }
            public void mover(int x, int y) { }
            public void clic(Integer x, Integer y, int b, int v, String m) { }
            public void arrastrar(int a, int b, int c, int d, String m) { }
            public void bajarBotonIzquierdo() { }
            public void subirBotonIzquierdo() { }
            public void escribir(String t) { }
            public void tecla(String c, int r) { }
            public void mantenerTecla(String c, double s) { }
            public void rueda(String d, int n, Integer x, Integer y, String m) { }
            public void esperar(double s) { }
            public int[] posicion() { return new int[] {0, 0}; }
        };
        List<Map<String, Object>> vistas = new ArrayList<>();
        Agente.Resultado atasco = new Agente(p -> {
            vistas.add((Map<String, Object>) Json.leer(Json.escribir(p)));
            return respuesta(uso("key", Map.of("text", "ctrl+n")), uso("screenshot", Map.of()));
        }, quietas, "m", "i", 20, s -> { }).ejecutar("atasco");
        ok(!atasco.terminado() && atasco.mensaje().contains("sin avances") && atasco.pasos() == 5, "atascado: se para solo a la 5.ª captura idéntica (paso " + atasco.pasos() + ")");
        ok(Json.escribir(vistas.get(3)).contains("AVISO del programa") && !Json.escribir(vistas.get(2)).contains("AVISO del programa"),
                "atascado: a la 3.ª captura idéntica se avisa a Claude");

        // Tope de gasto.
        Agente.Resultado caro = new Agente(p -> respuesta(uso("screenshot", Map.of())), manos, null, "m", "i", 50, 250, s -> { }).ejecutar("caro");
        ok(!caro.terminado() && caro.mensaje().contains("tope de gasto") && caro.pasos() == 3, "con el tope de gasto, se para (paso " + caro.pasos() + ")");

        // Herramientas propias de ventanas.
        List<String> vent = new ArrayList<>();
        es.amagna.asistente.manos.Ventanas v = new es.amagna.asistente.manos.Ventanas() {
            public void abrir(String p, List<String> a) { vent.add("abrir " + p + a); }
            public boolean activar(String t) { vent.add("activar " + t); return true; }
            public String activa() { return "Sin título: Bloc de notas"; }
            public String abiertas() { return "- «Sin título: Bloc de notas» (Notepad) — diálogos abiertos dentro: «Abrir» "; }
            public Campo campoConFoco() { return new Campo("Editor de texto", "ControlType.Document", "Hola, soy el Asistente Altas de Amagna", "Sin título: Bloc de notas"); }
        };
        List<Map<String, Object>> conV = new ArrayList<>();
        List<Map<String, Object>> guionV = new ArrayList<>(List.of(
                respuesta(propia("abrir_programa", Map.of("programa", "notepad.exe")), propia("activar_ventana", Map.of("titulo", "Bloc de notas"))),
                respuesta(propia("ventana_activa", Map.of())),
                respuesta(propia("leer_campo", Map.of("esperado", "Hola, soy el Asistente Altas de Amagna.")), propia("ventanas_abiertas", Map.of())),
                respuesta(texto("TERMINADO: ok"))));
        new Agente(p -> { conV.add((Map<String, Object>) Json.leer(Json.escribir(p))); return guionV.remove(0); }, manos, v, "m", "i", 10, 100_000, s -> { }).ejecutar("ventanas");
        ok(vent.equals(List.of("abrir notepad.exe[]", "activar Bloc de notas")), "abrir_programa y activar_ventana llegan a Windows");
        String pet = Json.escribir(conV.get(2));
        ok(pet.contains("Ventana activa: «Sin título: Bloc de notas»") && !Json.escribir(conV.get(1)).contains("\"toolset_name\":\"computer\",\"content\":[{\"type\":\"text\",\"text\":\"Abierto"),
                "ventana_activa responde; las herramientas propias no llevan toolset_name");
        List<Object> tools = (List<Object>) conV.get(0).get("tools");
        ok(tools.size() == 9 && Agente.HERRAMIENTA.equals(((Map<String, Object>) tools.get(8)).get("type")), "con ventanas: 8 herramientas propias + la de ordenador");
        List<Object> conUia = (List<Object>) ((Map<String, Object>) Json.leer(Json.escribir(Json.obj("x", 1)))).getOrDefault("no", List.of());
        ok(conUia.isEmpty(), "sin controles por dentro, el agente sigue funcionando con ojos y manos");
        String tras = Json.escribir(conV.get(3));
        ok(tras.contains("DISTINTO en el carácter 39") && tras.contains("Editor de texto"), "leer_campo lee el control con el foco y detecta el punto que falta");
        ok(tras.contains("diálogos abiertos dentro: «Abrir»"), "ventanas_abiertas enseña los diálogos que la captura no ve");
        ok(Agente.comparar("Hola, soy el Asistente.", "Hola, soy el Asistente.").startsWith("IGUAL"), "comparar: igual");
        String d = Agente.comparar("Hola, soy el Asistente.", "Hola, soy el Asistente");
        ok(d.startsWith("DISTINTO en el carácter 23") && d.contains("se esperaba «.» y hay (fin)"), "comparar: le falta el punto final → DISTINTO y dónde");
        ok(Agente.comparar("línea 1\nlínea 2", "línea 1\r\nlínea 2").startsWith("IGUAL") && Agente.comparar("a\nb", "a\rb").startsWith("IGUAL"), "comparar: saltos de línea de Windows cuentan igual");
        ok(Agente.comparar("PEREZ", "PÉREZ").startsWith("DISTINTO en el carácter 2"), "comparar: un acento de más → DISTINTO");

        // --- Cómo se nombra un control: sin acentos, sin «&» y sin los dos puntos del final ---
        ok(es.amagna.asistente.manos.Controles.llano("&Número de expediente:").equals("numero de expediente"),
                "el nombre de un control se compara sin acentos, sin «&» y sin los dos puntos finales");
        ok(es.amagna.asistente.manos.Controles.llano("  NIF  ").equals("nif") && es.amagna.asistente.manos.Controles.llano(null).isEmpty(),
                "el nombre de un control se compara sin espacios de sobra y aguanta un nombre vacío");

        // --- El expediente: la prueba de lo que hizo ---
        try {
            java.nio.file.Path base = java.nio.file.Files.createTempDirectory("expediente");
            es.amagna.asistente.cerebro.Expediente ex = es.amagna.asistente.cerebro.Expediente.abrirEn(base, "Dar de alta a PÉREZ GARCÍA");
            ok(ex.sirve(), "el expediente se abre");
            ex.dice("Voy a abrir el programa");
            ex.paso(1, "escribir_en", "campo:NIF", "IGUAL: campo «NIF» vale ahora exactamente «00000000T».");
            ex.captura(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
            ex.error("algo salió regular");
            ex.cerrar(true, "TERMINADO: alta creada", 7, 1000, 200, 500);
            String html = java.nio.file.Files.readString(ex.carpeta().resolve("informe.html"));
            String jsonl = java.nio.file.Files.readString(ex.carpeta().resolve("pasos.jsonl"));
            ok(html.contains("PÉREZ GARCÍA") && html.contains("TERMINADO: alta creada") && html.contains("001-pantalla.png"),
                    "el informe recoge la tarea, cómo acabó y las capturas");
            ok(html.contains("<title>") && html.contains("prefers-color-scheme") && !html.contains("<script"),
                    "el informe se abre solo, se ve bien de día y de noche y no lleva programación dentro");
            ok(jsonl.lines().count() == 6 && jsonl.contains("\"que\":\"fin\"") && jsonl.contains("\"tokens_entrada\":1000"),
                    "pasos.jsonl guarda una línea por cosa hecha, con el gasto al final");
            ok(java.nio.file.Files.exists(ex.carpeta().resolve("001-pantalla.png")), "las capturas se guardan junto al informe");
            java.nio.file.Path fichero = java.nio.file.Files.createTempFile("no-es-carpeta", ".txt");
            es.amagna.asistente.cerebro.Expediente malo = es.amagna.asistente.cerebro.Expediente.abrirEn(fichero, "x");
            malo.paso(1, "a", "b", "c");
            malo.cerrar(false, "PARADO", 1, 0, 0, 0);
            ok(!malo.sirve(), "si no se puede escribir el expediente, el asistente sigue trabajando igual");
        } catch (java.io.IOException e) {
            ok(false, "el expediente se puede escribir: " + e.getMessage());
        }

        // --- El mapa del programa: varias formas de encontrar cada control ---
        try {
            java.nio.file.Path m = java.nio.file.Files.createTempFile("mapa", ".json");
            java.nio.file.Files.writeString(m, """
                    {"nombre": "Ficha MN", "ventana": "MN program",
                     "programa": {"ejecutable": "mn.exe", "argumentos": ["/empresa", "1"]},
                     "controles": {"nif": ["#txtNif", "campo junto a NIF"], "guardar": "boton:Guardar"}}""");
            es.amagna.asistente.guion.Mapa mapa = es.amagna.asistente.guion.Mapa.leer(m);
            ok(mapa.ventana().equals("MN program") && mapa.ejecutable().equals("mn.exe") && mapa.argumentos().size() == 2,
                    "el mapa dice en qué ventana está el programa y con qué se abre");
            ok(mapa.apodos("nif").equals(List.of("#txtNif", "campo junto a NIF")),
                    "cada control lleva sus formas de encontrarlo, por orden de preferencia");
            ok(mapa.apodos("guardar").equals(List.of("boton:Guardar")), "y también vale poner una sola");
            try {
                mapa.apodos("telefono");
                ok(false, "pedir un control que no está en el mapa avisa");
            } catch (IllegalArgumentException e) {
                ok(e.getMessage().contains("nif") && e.getMessage().contains("guardar"),
                        "pedir un control que no está en el mapa dice cuáles hay");
            }
            java.nio.file.Path malo = java.nio.file.Files.createTempFile("mapa-malo", ".json");
            java.nio.file.Files.writeString(malo, "{\"controles\": {\"a\": [\"b\"]}}");
            try {
                es.amagna.asistente.guion.Mapa.leer(malo);
                ok(false, "un mapa sin ventana se rechaza");
            } catch (IllegalArgumentException e) {
                ok(e.getMessage().contains("ventana"), "un mapa sin ventana se rechaza diciendo qué le falta");
            }
        } catch (java.io.IOException e) {
            ok(false, "se puede leer un mapa: " + e.getMessage());
        }

        // --- El aprendiz: la IA mira y propone, el programa comprueba ---
        var propuesta = es.amagna.asistente.guion.Aprendiz.proponer(
                p2 -> respuesta(texto("""
                        {"controles": {
                          "nif": ["#txtNif", "campo junto a NIF"],
                          "importe": ["campo junto a Importe"],
                          "guardar": "boton:Guardar"
                        }}""")), "m", "- campo «1»\n- boton «Guardar»");
        ok(propuesta.controles().size() == 3 && propuesta.controles().get("nif").equals(List.of("#txtNif", "campo junto a NIF")),
                "del mapa que propone la IA se sacan los controles y sus formas, por orden");
        ok(propuesta.controles().get("guardar").equals(List.of("boton:Guardar")), "y también si propone una sola forma");
        var conRuido = es.amagna.asistente.guion.Aprendiz.proponer(
                p2 -> respuesta(texto("Claro, aquí tienes el mapa:\n```json\n{\"controles\": {\"nif\": [\"#a\"]}}\n```\n¡Espero que sirva!")),
                "m", "x");
        ok(conRuido.controles().size() == 1, "si la IA se enrolla antes o después, se saca el mapa igual");
        try {
            es.amagna.asistente.guion.Aprendiz.proponer(p2 -> respuesta(texto("No he podido verlo")), "m", "x");
            ok(false, "una respuesta sin mapa se rechaza");
        } catch (IllegalStateException e) {
            ok(true, "una respuesta sin mapa se rechaza en vez de dar por bueno un mapa vacío");
        }

        // --- El explorador: qué menú abre una pantalla y qué menú HACE algo ---
        ok(es.amagna.asistente.guion.Explorador.porQueNoSeToca(List.of("Bases de Datos", "Clientes")) == null
                && es.amagna.asistente.guion.Explorador.porQueNoSeToca(List.of("Expedientes", "Expedientes")) == null,
                "una pantalla normal se puede abrir");
        ok(es.amagna.asistente.guion.Explorador.porQueNoSeToca(List.of("Fichaje", "Inicio Jornada")) != null,
                "«Fichaje > Inicio Jornada» NO se abre: te fichar\u00eda la entrada");
        ok(es.amagna.asistente.guion.Explorador.porQueNoSeToca(List.of("Contabilidad", "Utilidades", "Cierre/Apertura Ejercicio", "Cierre Ejercicio")) != null
                && es.amagna.asistente.guion.Explorador.porQueNoSeToca(List.of("Expedientes", "Papelera de Reciclaje")) != null,
                "cerrar el ejercicio y la papelera tampoco se tocan");
        ok(es.amagna.asistente.guion.Explorador.porQueNoSeToca(List.of("Contabilidad", "Utilidades", "Actualizar %IVA")) != null,
                "ni «Actualizar %IVA», que suena inofensivo y cambia datos de todos");
        try {
            java.nio.file.Path fp = java.nio.file.Files.createTempFile("plano", ".json");
            es.amagna.asistente.guion.Explorador.guardarPlano(fp, "MNprogram", List.of(
                    new es.amagna.asistente.guion.Explorador.Pantalla(List.of("Bases de Datos", "Clientes"), true, null),
                    new es.amagna.asistente.guion.Explorador.Pantalla(List.of("Fichaje", "Inicio Jornada"), false, "peligrosa")));
            var leido = es.amagna.asistente.guion.Explorador.leerPlano(fp);
            ok(leido.size() == 2 && leido.get(0).seguro() && !leido.get(1).seguro(),
                    "el plano se guarda y se vuelve a leer con lo aprobado y lo prohibido");
            // Y aunque alguien ponga a mano «abrir: true» en una peligrosa, sigue sin tocarse.
            java.nio.file.Files.writeString(fp, java.nio.file.Files.readString(fp).replace("\"abrir\":false", "\"abrir\":true"));
            var forzado = es.amagna.asistente.guion.Explorador.leerPlano(fp);
            ok(!forzado.get(1).seguro(),
                    "si alguien aprueba a mano una peligrosa, se sigue sin abrir: la lista negra manda sobre el fichero");
        } catch (java.io.IOException e) {
            ok(false, "el plano se puede guardar: " + e.getMessage());
        }

        // --- Y aquí entra la IA: lee el menú y dice qué abre pantalla y qué ejecuta algo ---
        var plano = List.of(
                new es.amagna.asistente.guion.Explorador.Pantalla(List.of("Bases de Datos", "Clientes"), true, null),
                new es.amagna.asistente.guion.Explorador.Pantalla(List.of("Agenda"), true, null),
                new es.amagna.asistente.guion.Explorador.Pantalla(List.of("Utilidades", "Recalcular saldos"), true, null),
                new es.amagna.asistente.guion.Explorador.Pantalla(List.of("Informes", "Resumen"), true, null),
                new es.amagna.asistente.guion.Explorador.Pantalla(List.of("Fichaje", "Inicio Jornada"), true, null));
        var juicios = es.amagna.asistente.guion.Explorador.clasificar(p4 -> respuesta(texto("""
                {"opciones": [
                  {"ruta": "Bases de Datos > Clientes", "que_hace": "abre", "por_que": "es un listado"},
                  {"ruta": "Agenda", "que_hace": "abre", "por_que": "es una pantalla"},
                  {"ruta": "Utilidades > Recalcular saldos", "que_hace": "hace", "por_que": "recalcula datos de todos"},
                  {"ruta": "Informes > Resumen", "que_hace": "duda", "por_que": "podría lanzar un proceso largo"},
                  {"ruta": "Fichaje > Inicio Jornada", "que_hace": "abre", "por_que": "me he equivocado a propósito"}
                ]}""")), "m", plano);
        ok(juicios.size() == 5 && juicios.get("Agenda").queHace().equals("abre"), "la IA clasifica cada opción del menú");
        var juntado = es.amagna.asistente.guion.Explorador.juntar(plano, juicios);
        ok(juntado.get(0).seguro() && juntado.get(1).seguro(), "lo que la IA dice que abre una pantalla, se abre");
        ok(!juntado.get(2).seguro() && juntado.get(2).motivo().contains("recalcula"),
                "lo que la IA ve que ejecuta algo NO se abre, y dice por qué (una lista de palabras no lo habría pillado)");
        ok(!juntado.get(3).seguro(), "y lo que la IA duda, tampoco: ante la duda no se entra");
        ok(!juntado.get(4).seguro() && juntado.get(4).motivo().contains("fichaje"),
                "si la IA se equivoca y aprueba una peligrosa, el código la para igual: tienen que estar de acuerdo las dos");
        ok(es.amagna.asistente.guion.Explorador.porQueNoSeToca(List.of("Lexnet")) != null
                && es.amagna.asistente.guion.Explorador.porQueNoSeToca(List.of("Correo")) != null,
                "los módulos que hablan con fuera (Lexnet, Correo) no se abren: al entrar pueden bajar o mandar cosas");
        // Un secreto en un guion no se enseña NUNCA, ni siquiera cuando algo falla.
        try {
            java.nio.file.Path fs = java.nio.file.Files.createTempFile("guion-secreto", ".json");
            java.nio.file.Files.writeString(fs,
                    "{\"pasos\": [{\"hacer\": \"escribir\", \"control\": \"clave\", \"valor\": \"secreto:no-existe\"}]}");
            java.nio.file.Path ms = java.nio.file.Files.createTempFile("mapa-secreto", ".json");
            java.nio.file.Files.writeString(ms, "{\"ventana\": \"x\", \"controles\": {\"clave\": [\"#c\"]}}");
            List<String> dicho = new ArrayList<>();
            var parte = new es.amagna.asistente.guion.Guion(new es.amagna.asistente.manos.Controles(null), null,
                    es.amagna.asistente.guion.Mapa.leer(ms), dicho::add).ejecutar(fs, false);
            ok(!parte.bien() && parte.resumen().startsWith("NO SE EMPIEZA") && parte.resumen().contains("no-existe"),
                    "si falta una contraseña, el guion NO EMPIEZA: mejor eso que quedarse a medias de un alta");
            ok(parte.resumen().contains("asistente secreto no-existe"), "y dice el comando exacto para guardarla");
        } catch (java.io.IOException e) {
            ok(false, "se puede probar un guion con secreto: " + e.getMessage());
        }

        // El mapa sabe dónde está su pantalla de entrada: así una sola orden puede también volver a entrar sola.
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("mapas");
            java.nio.file.Files.writeString(dir.resolve("prog.json"),
                    "{\"ventana\": \"Programa\", \"entrada\": {\"mapa\": \"prog-entrada.json\", \"guion\": \"entrar.json\"},"
                    + " \"controles\": {\"a\": [\"b\"]}}");
            var conEntrada = es.amagna.asistente.guion.Mapa.leer(dir.resolve("prog.json"));
            ok(conEntrada.entradaMapa() != null && conEntrada.entradaMapa().endsWith("prog-entrada.json")
                    && conEntrada.entradaGuion() != null && conEntrada.entradaGuion().endsWith("entrar.json"),
                    "el mapa apunta a su pantalla de entrada, y las rutas se cuentan desde el propio mapa");
            java.nio.file.Files.writeString(dir.resolve("solo.json"), "{\"ventana\": \"X\", \"controles\": {\"a\": [\"b\"]}}");
            var sinEntrada = es.amagna.asistente.guion.Mapa.leer(dir.resolve("solo.json"));
            ok(sinEntrada.entradaMapa() == null && sinEntrada.entradaGuion() == null,
                    "y un programa que no pide entrar no necesita ese bloque");
        } catch (java.io.IOException e) {
            ok(false, "se puede leer un mapa con entrada: " + e.getMessage());
        }

        // El cerrojo de la empresa: si el mapa dice dónde hay que estar, eso manda.
        try {
            java.nio.file.Path dd = java.nio.file.Files.createTempFile("mapa-dd", ".json");
            java.nio.file.Files.writeString(dd,
                    "{\"ventana\": \"MN\", \"debe_decir\": \"Empresa de prueba\", \"controles\": {\"a\": [\"b\"]}}");
            ok("Empresa de prueba".equals(es.amagna.asistente.guion.Mapa.leer(dd).debeDecir()),
                    "el mapa puede exigir dónde hay que estar («Empresa de prueba»), y eso no se negocia");
        } catch (java.io.IOException e) {
            ok(false, "se puede leer «debe_decir»: " + e.getMessage());
        }

        // El guion de entrada de MN: la empresa va POR NOMBRE; la última fila es solo el ejercicio.
        try {
            java.nio.file.Path fg = java.nio.file.Path.of("guiones", "entrar-mn.json");
            Map<String, Object> g = Json.objeto(java.nio.file.Files.readString(fg));
            boolean empresaPorNombre = false;
            boolean empresaUltima = false;
            boolean ejercicioUltimo = false;
            boolean loginSaltaSiEmpresa = false;
            boolean loginSaltaSiEjercicioReal = false;
            boolean empresaNoUsaUltima = true;
            boolean tituloEjercicioReal = false;
            boolean hastaEjercicioReal = false;
            boolean ejercicioHastaDesaparece = false;
            boolean ejercicioNoSaltaPorPrincipal = true;
            for (Object o : (List<?>) g.get("pasos")) {
                Map<?, ?> p = (Map<?, ?>) o;
                String hacer = String.valueOf(p.get("hacer"));
                if ("escribir".equals(hacer) && "clave".equals(String.valueOf(p.get("control")))) {
                    String saltar = String.valueOf(p.get("saltar_si"));
                    loginSaltaSiEmpresa = saltar.contains("Selección de Empresa");
                    loginSaltaSiEjercicioReal = saltar.contains("Selección de Ejercicio Contable");
                }
                if (!"avanzar".equals(hacer)) {
                    continue;
                }
                String dialogo = String.valueOf(p.get("ventana"));
                if (dialogo.contains("Empresa")) {
                    empresaPorNombre = "Empresa de prueba".equals(p.get("fila"));
                    empresaUltima = Boolean.TRUE.equals(p.get("ultima"));
                    empresaNoUsaUltima = p.get("ultima") == null || Boolean.FALSE.equals(p.get("ultima"));
                    if (p.get("hasta") instanceof Map<?, ?> h) {
                        hastaEjercicioReal = "Selección de Ejercicio Contable".equals(String.valueOf(h.get("ventana")));
                    }
                }
                if (dialogo.contains("Ejercicio")) {
                    tituloEjercicioReal = "Selección de Ejercicio Contable".equals(dialogo);
                    ejercicioUltimo = Boolean.TRUE.equals(p.get("ultima"));
                    if (p.get("hasta") instanceof Map<?, ?> h) {
                        ejercicioHastaDesaparece = "desaparezca".equals(String.valueOf(h.get("que")))
                                && "Selección de Ejercicio Contable".equals(String.valueOf(h.get("ventana")));
                    }
                    String saltarEj = String.valueOf(p.get("saltar_si"));
                    ejercicioNoSaltaPorPrincipal = p.get("saltar_si") == null
                            || !saltarEj.contains("MNprogram");
                }
            }
            ok(empresaPorNombre && !empresaUltima && empresaNoUsaUltima,
                    "el guion de entrada elige «Empresa de prueba», no la última fila (la empresa 4)");
            ok(ejercicioUltimo && tituloEjercicioReal && ejercicioHastaDesaparece,
                    "y el ejercicio sí es el último, en «Selección de Ejercicio Contable», hasta que desaparezca");
            ok(loginSaltaSiEmpresa && loginSaltaSiEjercicioReal && hastaEjercicioReal
                    && ejercicioNoSaltaPorPrincipal,
                    "si ya está Selección de Empresa o de Ejercicio Contable, no se vuelve a escribir la contraseña detrás; el ejercicio no se salta porque se vea MN");
        } catch (java.io.IOException e) {
            ok(false, "se puede leer el guion de entrada: " + e.getMessage());
        }

        // Nunca quedarse mudo para siempre: si el programa no contesta, se deja por imposible y se avisa.
        long t0espera = System.currentTimeMillis();
        String tarde = es.amagna.asistente.guion.Explorador.conTiempo(1, () -> {
            Thread.sleep(30_000);
            return "esto no debería llegar";
        }, "no contesta");
        long esperado = System.currentTimeMillis() - t0espera;
        ok("no contesta".equals(tarde) && esperado < 5000,
                "si el programa no contesta, se deja por imposible al segundo (" + esperado + " ms), no se cuelga para siempre");
        ok("ya".equals(es.amagna.asistente.guion.Explorador.conTiempo(5, () -> "ya", "no contesta")),
                "y si contesta a tiempo, se devuelve lo suyo");

        // Qué es un menú desplegado y qué es una pantalla. MN es WinForms y usa «#32768»; otros programas abren el
        // menú como una ventana flotante con otro nombre: hay que conocer las dos formas.
        ok(es.amagna.asistente.guion.Explorador.esDesplegable("#32768", ""), "«#32768» es un menú desplegado (los de MN)");
        ok(es.amagna.asistente.guion.Explorador.esDesplegable("DropDownSomething", "")
                && es.amagna.asistente.guion.Explorador.esDesplegable("Xaml_WindowedPopupClass", "")
                && es.amagna.asistente.guion.Explorador.esDesplegable("algo", "PopupHost"),
                "y los menús de los programas modernos, también");
        ok(!es.amagna.asistente.guion.Explorador.esDesplegable("WindowsForms10.Window.8.app.0.141b42a_r6_ad1", "Clientes"),
                "pero una ventana normal de MN NO se confunde con un menú");

        ok(es.amagna.asistente.guion.Explorador.esCargandoPrueba("Cargando...")
                && es.amagna.asistente.guion.Explorador.esCargandoPrueba("Espere por favor")
                && !es.amagna.asistente.guion.Explorador.esCargandoPrueba("Clientes"),
                "un cartel de «Cargando…» no es la pantalla: se deja pasar y se espera a la de verdad");

        // Un submenú no es una pantalla: pulsarlo deja el menú desplegado y al programa esperando.
        var menus = List.of(List.of("Bases de Datos"), List.of("Bases de Datos", "Clientes"),
                List.of("Bases de Datos", "Ficheros"), List.of("Bases de Datos", "Ficheros", "Contrarios"),
                List.of("Agenda"));
        ok(es.amagna.asistente.guion.Explorador.esSubmenu(List.of("Bases de Datos"), menus),
                "«Bases de Datos» es un submenú: no se entra, se entra en las de dentro");
        ok(es.amagna.asistente.guion.Explorador.esSubmenu(List.of("Bases de Datos", "Ficheros"), menus),
                "y un submenú dentro de otro, igual");
        ok(!es.amagna.asistente.guion.Explorador.esSubmenu(List.of("Bases de Datos", "Clientes"), menus)
                && !es.amagna.asistente.guion.Explorador.esSubmenu(List.of("Agenda"), menus),
                "las que no tienen nada debajo sí son pantallas, estén al final o arriba del todo");
        var recortado = es.amagna.asistente.guion.Explorador.soloEstas(
                es.amagna.asistente.guion.Explorador.juntar(plano, juicios), List.of("Clientes"));
        ok(recortado.get(0).seguro() && recortado.stream().filter(es.amagna.asistente.guion.Explorador.Pantalla::seguro).count() == 1,
                "se puede pedir que entre SOLO en lo que hace falta, y el resto se queda fuera");
        var sinClasificar = es.amagna.asistente.guion.Explorador.juntar(plano, Map.of());
        ok(sinClasificar.stream().noneMatch(es.amagna.asistente.guion.Explorador.Pantalla::seguro),
                "y si la IA se deja opciones sin clasificar, esas no se tocan");

        // --- Los especialistas: cada uno con lo suyo, y con las manos atadas para lo demás ---
        try {
            var carto = es.amagna.asistente.cerebro.Especialista.cargar("cartografo", "modelo-por-defecto", 999);
            ok(carto.instrucciones().contains("Asistente Altas") && carto.instrucciones().contains("Tu especialidad: cartografo"),
                    "un especialista hereda las normas comunes y añade las suyas: no empieza de cero");
            ok(carto.pasos() == 30 && carto.topeTokens() == 150_000, "trae sus propios parámetros (pasos y gasto)");
            ok(carto.modelo().equals("modelo-por-defecto"), "si no dice modelo, se usa el de la casa");
            ok(carto.puede().contains("ver_controles") && !carto.puede().contains("escribir_en") && !carto.puede().contains("pulsar_control"),
                    "el cartógrafo puede mirar pero NO puede escribir ni pulsar");
            var desa = es.amagna.asistente.cerebro.Especialista.cargar("desatascador", "m", 999);
            ok(desa.puede().isEmpty() && desa.pasos() == 12, "el desatascador sí puede tocar, pero con poca cuerda");
            ok(desa.instrucciones().contains("Selección de Ejercicio Contable")
                    && desa.instrucciones().toLowerCase().contains("nunca la captura"),
                    "el desatascador mira UIA del diálogo real, no captura primero ni Contabilidad");
            try {
                es.amagna.asistente.cerebro.Especialista.cargar("el-que-no-existe", "m", 1);
                ok(false, "pedir un especialista que no existe avisa");
            } catch (IllegalArgumentException e) {
                ok(e.getMessage().contains("cartografo"), "pedir un especialista que no existe dice cuáles hay");
            }
        } catch (java.io.IOException e) {
            ok(false, "se pueden leer los especialistas: " + e.getMessage());
        }

        // Y lo que de verdad importa: que el LÍMITE lo imponga el programa, no las instrucciones.
        int antesDeNada = hecho.size();
        List<Map<String, Object>> conS = new ArrayList<>();
        List<Map<String, Object>> guionS = new ArrayList<>(List.of(
                respuesta(propia("escribir_en", Map.of("ventana", "x", "control", "nif", "texto", "lo que sea"))),
                respuesta(uso("left_click", Map.of("coordinate", List.of(10L, 10L)))),
                respuesta(propia("abrir_programa", Map.of("programa", "notepad.exe"))),
                respuesta(texto("PARADO: no puedo tocar nada"))));
        new Agente(p3 -> { conS.add((Map<String, Object>) Json.leer(Json.escribir(p3))); return guionS.remove(0); },
                manos, v, "m", "i", 10, 100_000, s2 -> { })
                .conControles(new es.amagna.asistente.manos.Controles(null))
                .soloPuede(java.util.Set.of("ver_controles", "leer_control", "screenshot"))
                .ejecutar("solo mirar");
        String pedido = Json.escribir(conS.get(0));
        ok(pedido.contains("ver_controles") && !pedido.contains("\"name\":\"escribir_en\"") && !pedido.contains("\"name\":\"pulsar_control\""),
                "a un especialista de solo mirar ni se le enseñan las herramientas que no puede usar");
        ok(Json.escribir(conS.get(1)).contains("NO PERMITIDO") && Json.escribir(conS.get(1)).contains("escribir_en"),
                "y si aun así pide una prohibida, el PROGRAMA se la niega (no vale con decírselo)");
        ok(Json.escribir(conS.get(2)).contains("NO PERMITIDO"), "tampoco puede saltárselo con el ratón");
        ok(Json.escribir(conS.get(3)).contains("NO PERMITIDO") && vent.size() == 2 && hecho.size() == antesDeNada,
                "ni abriendo programas: en toda la tarea no ha tocado nada de nada");
    }

    static int ids = 0;

    static Map<String, Object> uso(String accion, Map<String, Object> in) {
        return Json.obj("type", "tool_use", "id", "toolu_" + (ids++), "name", accion, "toolset_name", "computer", "input", new java.util.LinkedHashMap<>(in));
    }

    static Map<String, Object> propia(String nombre, Map<String, Object> in) {
        return Json.obj("type", "tool_use", "id", "toolu_" + (ids++), "name", nombre, "input", new java.util.LinkedHashMap<>(in));
    }

    static Map<String, Object> pensar() {
        return Json.obj("type", "thinking", "thinking", "...", "signature", "firma");
    }

    static void filas() {
        ok(es.amagna.asistente.manos.Controles.cuentaComoFila("fila", "Ejercicio Fila 0"),
                "un DataItem de MN cuenta como fila");
        ok(es.amagna.asistente.manos.Controles.cuentaComoFila("opcion", "2026"),
                "un ListItem con el año también: si no, el diálogo del ejercicio no ve ninguna fila");
        ok(es.amagna.asistente.manos.Controles.cuentaComoFila("otro", "2.026"),
                "y un Custom con el año a la española (2.026) también");
        ok(es.amagna.asistente.manos.Controles.cuentaComoFila("otro", "Row 3"),
                "y un Custom llamado Row N, que es lo que pone DevExpress a veces");
        ok(!es.amagna.asistente.manos.Controles.cuentaComoFila("otro", "Fila superior"),
                "la cabecera «Fila superior» de un DataGridView NO es una fila de datos");
        ok(es.amagna.asistente.manos.Controles.elValorEsLaFila("1;Empresa de prueba", "Empresa de prueba"),
                "en MN el nombre de la empresa está en el valor de la fila, no en «Fila 0»");
        ok(es.amagna.asistente.manos.Controles.elValorEsLaFila("Empresa de prueba", "Empresa de prueba"),
                "y si el valor es solo el nombre, también");
        ok(!es.amagna.asistente.manos.Controles.elValorEsLaFila("4;Empresa 4", "Empresa de prueba"),
                "«Empresa 4» no vale por «Empresa de prueba»");
        ok(!es.amagna.asistente.manos.Controles.elValorEsLaFila("1;Empresa de prueba", "Empresa"),
                "un trozo suelto («Empresa») no elige una fila: coincidiría con la 3 y la 4");
        ok(es.amagna.asistente.manos.Controles.esLaFilaEntera("Fila 0")
                && !es.amagna.asistente.manos.Controles.esLaFilaEntera("Nombre Fila 0"),
                "«Fila 0» es la fila entera; «Nombre Fila 0» es la celda");
        ok(es.amagna.asistente.manos.Controles.esCeldaDeFila("Nombre Fila 0")
                && es.amagna.asistente.manos.Controles.esCeldaDeFila("Número Fila 0")
                && !es.amagna.asistente.manos.Controles.esCeldaDeFila("Fila 0"),
                "y no se confunden: si se puntúan igual, el asistente no tocaba ninguna");
        ok(!es.amagna.asistente.manos.Controles.cuentaComoFila("boton", "Seleccionar"),
                "un botón de confirmar NO es una fila");
        ok(!es.amagna.asistente.manos.Controles.cuentaComoFila("panel", "Iniciar Sesión"),
                "ni el panel de entrar");
        var confirmar = es.amagna.asistente.manos.Controles.apodosDeConfirmar();
        ok(confirmar.get(0).startsWith("boton:") && confirmar.contains("panel:Seleccionar") && confirmar.contains("#btnAceptar"),
                "confirmar se busca por tipo o identificador, no por un trozo de nombre suelto");
        ok(!confirmar.contains("abrir") && !confirmar.contains("si"),
                "no se pulsa «abrir» ni «sí» a secas: en un diálogo de MN eso es un combo o un texto");
        ok(confirmar.stream().noneMatch(es.amagna.asistente.manos.Controles::esBotonPeligrosoDeEmpresa)
                && confirmar.stream().noneMatch(a -> a.toLowerCase().contains("modificar")),
                "confirmar nunca incluye Modificar (ni Alta ni Baja)");

        // El caso real de MN: nombre «Fila 0», valor «1;Empresa de prueba». El texto de ayuda
        // contiene las mismas palabras y, si se puntúa, empata y no se toca ninguna.
        ok(es.amagna.asistente.manos.Controles.esFraseDeAyuda(
                "La empresa de prueba incluye ejemplos para realizar pruebas."),
                "la frase de ayuda del diálogo NO es una fila");
        ok(!es.amagna.asistente.manos.Controles.esFraseDeAyuda("Empresa de prueba")
                && !es.amagna.asistente.manos.Controles.esFraseDeAyuda("Fila 0")
                && !es.amagna.asistente.manos.Controles.esFraseDeAyuda("Nombre Fila 0"),
                "y el nombre de la empresa o de la fila, sí cabe");
        ok(!es.amagna.asistente.manos.Controles.cuentaComoFila("texto",
                "La empresa de prueba incluye ejemplos para realizar pruebas.")
                && !es.amagna.asistente.manos.Controles.cuentaComoFila("otro",
                "La empresa de prueba incluye ejemplos para realizar pruebas.")
                && !es.amagna.asistente.manos.Controles.cuentaComoFila("fila",
                "No se puede modificar la Empresa de prueba."),
                "ni el texto de ayuda ni el aviso de Modificar cuentan como fila");

        int pFila = es.amagna.asistente.manos.Controles.puntosDeFila(
                "otro", "Fila 0", "1;Empresa de prueba", "Empresa de prueba");
        int pCelda = es.amagna.asistente.manos.Controles.puntosDeFila(
                "fila", "Nombre Fila 0", "Empresa de prueba", "Empresa de prueba");
        int pAyuda = es.amagna.asistente.manos.Controles.puntosDeFila(
                "texto", "La empresa de prueba incluye ejemplos para realizar pruebas.",
                null, "Empresa de prueba");
        int pCabecera = es.amagna.asistente.manos.Controles.puntosDeFila(
                "otro", "Fila superior", "Número;Nombre", "Empresa de prueba");
        int pTrozo = es.amagna.asistente.manos.Controles.puntosDeFila(
                "otro", "Fila 3", "4;Empresa 4", "Empresa");
        int pMia = es.amagna.asistente.manos.Controles.puntosDeFila(
                "otro", "Fila 1", "2;Mi Empresa", "Empresa de prueba");
        ok(pFila >= 112 && pFila - pCelda >= 15,
                "«Fila 0» = «1;Empresa de prueba» gana a la celda por más del empate (" + pFila + " vs " + pCelda + ")");
        ok(pAyuda == 0 && pCabecera == 0 && pTrozo == 0 && pMia == 0,
                "ayuda, cabecera, un trozo «Empresa» y «Mi Empresa» no puntúan para Empresa de prueba");

        ok(es.amagna.asistente.manos.Controles.empresaProhibida("Mi Empresa")
                && es.amagna.asistente.manos.Controles.empresaProhibida("2;Mi Empresa")
                && es.amagna.asistente.manos.Controles.empresaProhibida("3;Empresa 3")
                && es.amagna.asistente.manos.Controles.empresaProhibida("4;Empresa 4")
                && es.amagna.asistente.manos.Controles.empresaProhibida(
                        "MNprogram - Abogados - Mi Empresa [\\Usu2] Año 2026"),
                "Mi Empresa / Empresa 3 / Empresa 4 están prohibidas, también en el título");
        ok(!es.amagna.asistente.manos.Controles.empresaProhibida("Empresa de prueba")
                && !es.amagna.asistente.manos.Controles.empresaProhibida("1;Empresa de prueba"),
                "y Empresa de prueba no lo está");

        ok(es.amagna.asistente.manos.Controles.esDialogoDeEmpresa("Selección de Empresa")
                && es.amagna.asistente.manos.Controles.esBotonPeligrosoDeEmpresa("Modificar")
                && es.amagna.asistente.manos.Controles.esBotonPeligrosoDeEmpresa("boton:Alta")
                && es.amagna.asistente.manos.Controles.esBotonPeligrosoDeEmpresa("#btnBaja")
                && !es.amagna.asistente.manos.Controles.esBotonPeligrosoDeEmpresa("Seleccionar")
                && !es.amagna.asistente.manos.Controles.esBotonPeligrosoDeEmpresa("Aceptar"),
                "en ese diálogo Alta/Baja/Modificar están prohibidos; Seleccionar y Aceptar no");
        ok(es.amagna.asistente.manos.Controles.rechazoDeEmpresa("Selección de Empresa", "boton:Modificar") != null
                && es.amagna.asistente.manos.Controles.rechazoDeEmpresa("Selección de Empresa", "fila:Mi Empresa") != null
                && es.amagna.asistente.manos.Controles.rechazoDeEmpresa(
                        "Selección de Empresa", "fila:Empresa de prueba", "otro", "Fila 0", "1;Empresa de prueba") == null
                && es.amagna.asistente.manos.Controles.rechazoDeEmpresa(
                        "Selección de Empresa", "fila:Fila 1", "otro", "Fila 1", "2;Mi Empresa") != null,
                "se rechaza Modificar y Mi Empresa; se acepta la fila de Empresa de prueba");
        ok(es.amagna.asistente.manos.Controles.esAvisoInformativo("Información")
                && es.amagna.asistente.manos.Controles.esAvisoInformativo("Aviso"),
                "el popup «Información» se reconoce para cerrarlo con Aceptar");
        ok("Empresa de prueba".equals(
                es.amagna.asistente.manos.Controles.nombrePedidoDeFila("fila:Empresa de prueba"))
                && "Empresa de prueba".equals(
                es.amagna.asistente.manos.Controles.nombrePedidoDeFila("Empresa de prueba")),
                "de «fila:Empresa de prueba» se saca el nombre pedido");
        ok(es.amagna.asistente.manos.Controles.descripcionEsLaFila(
                "otro «Fila 0» = «1;Empresa de prueba»", "Empresa de prueba")
                && !es.amagna.asistente.manos.Controles.descripcionEsLaFila(
                "otro «Fila 1» = «2;Mi Empresa»", "Empresa de prueba"),
                "la descripción de UIA («Fila 0» = «1;Empresa de prueba») basta para probar la fila");

        ok(es.amagna.asistente.guion.Guion.noLlamarIa(
                "No se encuentra «fila:Empresa de prueba». No se confirma nada.",
                java.util.Map.of("fila", "Empresa de prueba", "hacer", "avanzar"))
                && es.amagna.asistente.guion.Guion.noLlamarIa("PARADO: empresa real", java.util.Map.of())
                && es.amagna.asistente.guion.Guion.noLlamarIa("lo que sea", java.util.Map.of("fila", "Empresa de prueba")),
                "si falta Empresa de prueba, la IA no entra a pulsar otra cosa");
        ok(!es.amagna.asistente.guion.Guion.noLlamarIa("el campo NIF no está", java.util.Map.of("hacer", "escribir")),
                "y en un atasco que no es de empresa, sí puede entrar");
        ok(es.amagna.asistente.guion.Guion.noLlamarIa(
                "PARADO: «Selección de Ejercicio Contable» sigue abierto.",
                java.util.Map.of("hacer", "avanzar", "ventana", "Selección de Ejercicio Contable")),
                "en el diálogo de ejercicio no entra la IA: se para");

        ok(es.amagna.asistente.guion.Guion.esSi(Boolean.TRUE)
                && es.amagna.asistente.guion.Guion.esSi("true")
                && es.amagna.asistente.guion.Guion.esSi(Json.objeto("{\"ultima\":true}").get("ultima"))
                && !es.amagna.asistente.guion.Guion.esSi(null)
                && !es.amagna.asistente.guion.Guion.esSi(Boolean.FALSE)
                && !es.amagna.asistente.guion.Guion.esSi("false"),
                "«ultima: true» del JSON es un boolean de verdad y se lee; no se pierde al pasarlo a texto");

        ok(!es.amagna.asistente.guion.Guion.saltarPorqueYaEstamos("avanzar",
                "Selección de Ejercicio Contable",
                List.of("MNprogram - Abogados"),
                t -> true)
                && !es.amagna.asistente.guion.Guion.saltarPorqueYaEstamos("avanzar",
                "Selección de Ejercicio Contable",
                List.of("MNprogram - Abogados"),
                t -> "MNprogram - Abogados".equals(t))
                && es.amagna.asistente.guion.Guion.saltarPorqueYaEstamos("escribir",
                "Control de acceso",
                List.of("Selección de Ejercicio Contable"),
                t -> t.contains("Ejercicio")),
                "el ejercicio no se salta porque se vea MN detrás; el login sí se salta si ya está el diálogo");

        ok(es.amagna.asistente.manos.Controles.esDialogoDeEjercicio("Selección de Ejercicio Contable")
                && es.amagna.asistente.manos.Controles.esDialogoDeEjercicio("Ejercicio Contable")
                && !es.amagna.asistente.manos.Controles.esDialogoDeEjercicio("Selección de Empresa")
                && !es.amagna.asistente.manos.Controles.esDialogoDeEjercicio("Cierre Ejercicio"),
                "el diálogo real es «Selección de Ejercicio Contable»; un cierre de ejercicio no lo es");

        ok(!es.amagna.asistente.manos.Controles.arbolSirveParaAvanzar("")
                && !es.amagna.asistente.manos.Controles.arbolSirveParaAvanzar("Controles de «x»:")
                && !es.amagna.asistente.manos.Controles.arbolSirveParaAvanzar(
                        "No hay ninguna ventana cuyo título contenga «Ejercicio Contable»."),
                "un árbol vacío no vale para avanzar: hay que reiniciar UIA y esperar");
        ok(es.amagna.asistente.manos.Controles.arbolSirveParaAvanzar(
                "Controles de «Selección de Ejercicio Contable»:\n- boton «Seleccionar»\n- otro «Fila 0» = «2026»"),
                "filas + Seleccionar sí: tras un modal hay que esperar a que UIA los vea");
        ok(es.amagna.asistente.manos.Controles.arbolDeEjercicioListo(
                "Controles de «Selección de Ejercicio Contable»:\n- boton «Seleccionar»")
                && !es.amagna.asistente.manos.Controles.arbolDeEjercicioListo("")
                && !es.amagna.asistente.manos.Controles.arbolDeEjercicioListo(
                "Controles de «Selección de Ejercicio Contable»:\n- otro «Fila 0» = «2026»")
                && es.amagna.asistente.manos.Controles.arbolDeEjercicioListo(
                "Controles de «Selección de Ejercicio Contable»:\n- boton «Seleccionar»\n- otro «Fila 0» = «2026»"),
                "listo para pulsar cuando se ve Seleccionar; filas solas o un árbol vacío no");
        ok(es.amagna.asistente.manos.Controles.elAsistentePasoElEjercicio(
                "Pasada la pantalla «Selección de Ejercicio Contable» eligiendo la última fila con «boton:Seleccionar»: Pulsado boton «Seleccionar».")
                && es.amagna.asistente.manos.Controles.elAsistentePasoElEjercicio(
                "Pasada la pantalla «Selección de Ejercicio Contable» eligiendo la última fila con doble clic en fila[-1].")
                && !es.amagna.asistente.manos.Controles.elAsistentePasoElEjercicio("Ya estaba pasada esa pantalla.")
                && !es.amagna.asistente.manos.Controles.elAsistentePasoElEjercicio(
                "ok     avanzar: ya estamos más adelante, se salta")
                && !es.amagna.asistente.manos.Controles.elAsistentePasoElEjercicio(
                "No se ha visto «Selección de Ejercicio Contable» con filas y Seleccionar. "
                        + "No se da por pasada: hay que elegir la última fila y pulsar Seleccionar.")
                && !es.amagna.asistente.manos.Controles.elAsistentePasoElEjercicio(
                "PARADO: «Selección de Ejercicio Contable» sigue abierto. El botón no ha cerrado el diálogo."),
                "pasar el ejercicio es pulsar Seleccionar; «No se da por pasada» y un PARADO no cuentan");
        String pasadaEj = "Pasada la pantalla «Selección de Ejercicio Contable» eligiendo la última fila con «boton:Seleccionar»: Pulsado boton «Seleccionar».";
        ok(!es.amagna.asistente.manos.Controles.exitoDeEjercicio("Ya estaba pasada esa pantalla.", true)
                && !es.amagna.asistente.manos.Controles.exitoDeEjercicio(
                        "No se ha podido pulsar Seleccionar.", true)
                && !es.amagna.asistente.manos.Controles.exitoDeEjercicio(pasadaEj, true),
                "con el diálogo aún abierto no vale «Ya estaba» ni un texto que mencione Seleccionar");
        ok(es.amagna.asistente.manos.Controles.exitoDeEjercicio(pasadaEj, false)
                && es.amagna.asistente.manos.Controles.exitoDeEjercicio("Ya estaba pasada esa pantalla.", false)
                && !es.amagna.asistente.manos.Controles.exitoDeEjercicio(
                        "Se ha pulsado boton «Seleccionar» y no ha reaccionado.", false),
                "«Ya estaba» solo si el diálogo ya no existe; un error con Seleccionar no es éxito");
        long tUia = System.currentTimeMillis();
        String uiaTarde = es.amagna.asistente.manos.Uia.conTope(1, () -> {
            Thread.sleep(30_000);
            return "esto no debería llegar";
        }, "abandonado");
        long tardoUia = System.currentTimeMillis() - tUia;
        ok("abandonado".equals(uiaTarde) && tardoUia < 5000,
                "una llamada UIA que no vuelve se abandona al segundo (" + tardoUia + " ms), no cuelga el paso");

        int pDialogo = es.amagna.asistente.manos.VentanasNativas.puntosDeVentana(
                "Ejercicio Contable", "Selección de Ejercicio Contable", true, false);
        int pPrincipal = es.amagna.asistente.manos.VentanasNativas.puntosDeVentana(
                "Ejercicio Contable", "MNprogram - Abogados", false, false);
        int pExacto = es.amagna.asistente.manos.VentanasNativas.puntosDeVentana(
                "Selección de Ejercicio Contable", "Selección de Ejercicio Contable", true, false);
        ok(pDialogo > 80 && pDialogo > pPrincipal && pExacto > pDialogo
                && es.amagna.asistente.manos.VentanasNativas.esRuidoDeBusqueda("Cursor Agents")
                && es.amagna.asistente.manos.VentanasNativas.puntosDeVentana(
                        "Ejercicio Contable", "Cursor Agents", false, false) == 0,
                "se prefiere el diálogo visible «Selección de Ejercicio Contable», no Cursor ni la principal");

        ok(Agente.rechazoEnEntrada(true, false, "screenshot", null) != null
                && Agente.rechazoEnEntrada(true, true, "screenshot", null) == null
                && Agente.rechazoEnEntrada(false, false, "screenshot", null) == null
                && Agente.esMenuPeligrosoEnEntrada("opcion-de-menu:Contabilidad")
                && Agente.esMenuPeligrosoEnEntrada("Fichaje")
                && Agente.rechazoEnEntrada(true, true, "pulsar_control", "opcion-de-menu:Contabilidad") != null
                && Agente.rechazoEnEntrada(true, true, "pulsar_control", "boton:Seleccionar") == null,
                "en la entrada: UIA primero, sin captura, y Contabilidad/Fichaje bloqueados");
    }

    /** La bandeja de ZIP: se avisa si no está o está vacía, y un .zip de mentira sale con su nombre y su tamaño. */
    static void zips() {
        try {
            java.nio.file.Path ausente = java.nio.file.Files.createTempDirectory("zips-padre").resolve("no-esta");
            String falta = Zips.listar(ausente);
            ok(falta.startsWith("No existe la carpeta de ZIPs:") && falta.contains(ausente.toAbsolutePath().normalize().toString()),
                    "si la carpeta de ZIPs no está, se dice y no se rompe");

            java.nio.file.Path vacia = java.nio.file.Files.createTempDirectory("zips-vacia");
            java.nio.file.Files.writeString(vacia.resolve("nota.txt"), "esto no es un zip");
            String nada = Zips.listar(vacia);
            ok(nada.contains("está vacía") && nada.contains("no hay ningún .zip")
                            && nada.contains(vacia.toAbsolutePath().normalize().toString()) && !nada.contains("nota.txt"),
                    "una carpeta sin .zip se dice en claro");

            java.nio.file.Path con = java.nio.file.Files.createTempDirectory("zips-con");
            byte[] falso = new byte[] {0x50, 0x4b, 3, 4, 9};
            java.nio.file.Files.write(con.resolve("cliente.zip"), falso);
            String lista = Zips.listar(con);
            ok(lista.contains("cliente.zip") && lista.contains(falso.length + " bytes") && !lista.contains("nota.txt"),
                    "un .zip de prueba se lista con su nombre y su tamaño");

            java.nio.file.Path cfg = java.nio.file.Files.createTempFile("entrada", ".json");
            java.nio.file.Files.writeString(cfg, Json.escribir(Json.obj(Zips.CLAVE, con.toString())));
            ok(Zips.carpeta(cfg, List.of()).toAbsolutePath().normalize().equals(con.toAbsolutePath().normalize()),
                    "entrada.json manda la carpeta con la clave " + Zips.CLAVE);
            java.nio.file.Path otra = java.nio.file.Path.of("C:\\Documentacion Cloud\\bandeja");
            ok(Zips.carpeta(cfg, List.of("--carpeta", otra.toString())).equals(otra),
                    "--carpeta cambia la ruta solo para esta vez");
            java.nio.file.Path sin = java.nio.file.Files.createTempFile("sin-entrada", ".json");
            java.nio.file.Files.writeString(sin, "{}");
            ok(Zips.carpeta(sin, List.of()).equals(java.nio.file.Path.of(Zips.CARPETA_POR_DEFECTO))
                            && Zips.carpeta(java.nio.file.Path.of("build", "entrada-que-no-existe.json"), List.of())
                            .equals(java.nio.file.Path.of(Zips.CARPETA_POR_DEFECTO)),
                    "sin entrada.json o sin la clave, se usa la carpeta de pruebas de este ordenador");

            // Bloque de documentación: 3 niveles, sin plantilla de nombre. La basura de macOS no cuenta ni en el fondo.
            Path doc = Files.createTempDirectory("zips-doc");
            Path valido = guardarZip(doc, "valido.zip",
                    "Cliente/Entidad/contrato.pdf", "pdf",
                    "Cliente/Entidad/imagen.jpg", "jpg",
                    "Cliente/Entidad/imagen.jpeg", "jpeg",
                    "Cliente/Entidad/imagen.png", "png",
                    "Cliente/Entidad/imagen.webp", "webp",
                    "Cliente/Entidad/nota.heic", "heic",
                    "Cliente/Entidad/no.docx", "no",
                    "Cliente/solo.txt", "txt",
                    "suelto.txt", "suelto",
                    "Señal/Año/informe.pdf", "utf8",
                    "__MACOSX/Cliente/Entidad/._contrato.pdf", "mac",
                    "__MACOSX/Cliente/Entidad/DeMas/._contrato.pdf", "mac-hondo",
                    "Cliente/Entidad/._oculto.pdf", "apple",
                    "Cliente/.DS_Store", "ds",
                    "Cliente/Entidad/Thumbs.db", "th",
                    "Cliente/Entidad/desktop.ini", "ini",
                    ".DS_Store", "raiz");
            String arbol = Zips.ver(valido);
            ok(arbol.contains("cliente «Cliente»") && arbol.contains("entidad «Entidad»") && arbol.contains("contrato.pdf")
                            && arbol.contains("imagen.jpg") && arbol.contains("imagen.jpeg") && arbol.contains("imagen.png")
                            && arbol.contains("imagen.webp") && arbol.contains("nota.heic")
                            && arbol.indexOf("cliente «Cliente»") < arbol.indexOf("entidad «Entidad»")
                            && arbol.indexOf("entidad «Entidad»") < arbol.indexOf("contrato.pdf"),
                    "un ZIP de 3 niveles se acepta: cliente, entidad y fichero");
            ok(arbol.contains("entidad «(sin entidad)»") && arbol.contains("solo.txt")
                            && arbol.contains("cliente «(sin carpeta)»") && arbol.contains("suelto.txt")
                            && arbol.indexOf("contrato.pdf") < arbol.indexOf("entidad «(sin entidad)»")
                            && arbol.indexOf("solo.txt") < arbol.indexOf("cliente «Señal»")
                            && arbol.indexOf("cliente «Señal»") < arbol.indexOf("cliente «(sin carpeta)»")
                            && arbol.indexOf("cliente «(sin carpeta)»") < arbol.indexOf("suelto.txt"),
                    "un fichero suelto va a «(sin carpeta)» y uno sin entidad, a «(sin entidad)»");
            ok(arbol.contains("cliente «Señal»") && arbol.contains("entidad «Año»") && arbol.contains("informe.pdf"),
                    "los nombres en UTF-8 se leen tal cual");
            ok(!arbol.contains("__MACOSX") && !arbol.contains("._") && !arbol.contains("oculto")
                            && !arbol.contains(".DS_Store") && !arbol.contains("Thumbs") && !arbol.contains("desktop.ini")
                            && !arbol.contains("docx") && !arbol.contains("No se abre"),
                    "__MACOSX, ._* , .DS_Store, Thumbs.db, desktop.ini y lo que no es pdf/jpg/… se ignora");
            ok(!Zips.listar(doc).contains("Dentro de") && !Zips.listar(doc).contains("cliente «"),
                    "listar sigue sin abrir el ZIP");
            String infUno = Zips.informe(doc, List.of());
            ok(infUno.contains("valido.zip") && infUno.contains("Dentro de «valido.zip»") && infUno.contains("cliente «Cliente»"),
                    "si solo hay un ZIP, se lista y también se enseña");
            String otroPedido = Zips.informe(doc, List.of("--ver", "otro.zip"));
            ok(otroPedido.contains("No está «otro.zip»") && !otroPedido.contains("cliente «"),
                    "si se pide uno que no está, no se enseña el único que hay");
            try {
                Zips.informe(doc, List.of("--ver"));
                ok(false, "sin nombre después de --ver se avisa");
            } catch (IllegalArgumentException e) {
                ok(e.getMessage().contains("--ver"), "sin nombre después de --ver se avisa");
            }

            Path hondoDir = Files.createTempDirectory("zips-hondo");
            Path hondo = guardarZip(hondoDir, "hondo.zip",
                    "Cliente/Entidad/ok.pdf", "bien",
                    "Cliente/Entidad/DeMas/contrato.pdf", "de-mas");
            String rechazado = Zips.ver(hondo);
            ok(rechazado.contains("No se abre") && rechazado.contains("3 niveles")
                            && rechazado.contains("Cliente/Entidad/DeMas/contrato.pdf")
                            && !rechazado.contains("cliente «") && !rechazado.contains("ok.pdf"),
                    "un ZIP de 4 niveles se rechaza entero, también lo que sí cabía");

            Path interiorDir = Files.createTempDirectory("zips-interior");
            byte[] grupo = zipDe("demanda.pdf", "d", "foto.jpg", "f", "nota.docx", "n", "__MACOSX/._demanda.pdf", "m");
            Path interior = guardarZip(interiorDir, "interior.zip",
                    "Cliente/Grupo.zip", grupo,
                    "Cliente/Entidad/contrato.pdf", "pdf");
            String conGrupo = Zips.ver(interior);
            ok(conGrupo.contains("entidad «Grupo»") && conGrupo.contains("demanda.pdf") && conGrupo.contains("foto.jpg")
                            && conGrupo.contains("entidad «Entidad»") && conGrupo.contains("contrato.pdf")
                            && !conGrupo.contains("Grupo.zip") && !conGrupo.contains("docx")
                            && !conGrupo.contains("__MACOSX") && !conGrupo.contains("._") && !conGrupo.contains("No se abre"),
                    "un ZIP interior cuenta como la entidad y no hace falta que el nombre diga USURA");

            Path sueltoZip = guardarZip(Files.createTempDirectory("zips-suelto-zip"), "raiz.zip",
                    "Suelto.zip", zipDe("nota.pdf", "n"));
            String enRaiz = Zips.ver(sueltoZip);
            ok(enRaiz.contains("cliente «(sin carpeta)»") && enRaiz.contains("entidad «Suelto»") && enRaiz.contains("nota.pdf"),
                    "un ZIP suelto en la raíz es la entidad, y el cliente es «(sin carpeta)»");

            String masHondo = Zips.ver(guardarZip(Files.createTempDirectory("zips-interior-hondo"), "hondo-interior.zip",
                    "Cliente/Grupo.zip", zipDe("sub/a.pdf", "a")));
            ok(masHondo.contains("No se abre") && masHondo.contains("3 niveles") && masHondo.contains("sub/a.pdf")
                            && !masHondo.contains("cliente «"),
                    "una carpeta dentro del ZIP interior pasa de 3 niveles y se rechaza");

            String usura = Zips.ver(guardarZip(Files.createTempDirectory("zips-usura"), "usura.zip",
                    "Cliente/Entidad/USURA_x.zip", zipDe("a.pdf", "a")));
            ok(usura.contains("No se abre") && usura.contains("3 niveles") && usura.contains("USURA_x.zip")
                            && !usura.contains("cliente «"),
                    "un ZIP dentro de una entidad serían 4 niveles y se rechaza");

            String anidado = Zips.ver(guardarZip(Files.createTempDirectory("zips-anidado"), "anidado.zip",
                    "Cliente/Grupo.zip", zipDe("otro.zip", zipDe("a.pdf", "a")),
                    "Otro/Entidad/ok.pdf", "ok"));
            ok(anidado.contains("No se abre") && anidado.contains("ZIP dentro") && anidado.contains("otro.zip")
                            && !anidado.contains("cliente «") && !anidado.contains("ok.pdf"),
                    "un ZIP dentro del ZIP interior se rechaza, y el bloque entero");

            Path varios = Files.createTempDirectory("zips-varios");
            guardarZip(varios, "uno.zip", "Uno/Entidad/a.pdf", "a");
            guardarZip(varios, "dos.zip", "Dos/Entidad/b.pdf", "b");
            String dosInteriores = Zips.ver(guardarZip(Files.createTempDirectory("zips-dos-zip"), "dos.zip",
                    "Cliente/A.zip", zipDe("a.pdf", "a"),
                    "Cliente/B.zip", zipDe("b.pdf", "b")));
            ok(dosInteriores.contains("No se abre") && dosInteriores.contains("solo se admite un ZIP")
                            && !dosInteriores.contains("cliente «"),
                    "solo se admite un ZIP dentro");
            String soloLista = Zips.informe(varios, List.of());
            ok(soloLista.contains("uno.zip") && soloLista.contains("dos.zip") && !soloLista.contains("Dentro de"),
                    "con varios ZIP solo se listan: no se abre ninguno");
            String visto = Zips.informe(varios, List.of("--ver", "dos.zip"));
            ok(visto.contains("uno.zip") && visto.contains("dos.zip") && visto.contains("cliente «Dos»")
                            && !visto.contains("cliente «Uno»"),
                    "--ver enseña solo ese ZIP");
            Path cfgVer = Files.createTempFile("entrada-ver", ".json");
            Files.writeString(cfgVer, "{}");
            List<String> argsVer = List.of("--carpeta", varios.toString(), "--ver", "dos.zip");
            String compuesto = Zips.informe(Zips.carpeta(cfgVer, argsVer), argsVer);
            ok(compuesto.contains("cliente «Dos»") && !compuesto.contains("cliente «Uno»"),
                    "--carpeta y --ver se leen como en el comando");

            String salida = Zips.ver(guardarZip(Files.createTempDirectory("zips-salida"), "salida.zip",
                    "propuesta.json", "{}",
                    "Cliente/Entidad/a.pdf", "a"));
            ok(salida.contains("paquete de alta") && salida.contains("propuesta.json")
                            && !salida.contains("cliente «") && !salida.contains("a.pdf"),
                    "propuesta.json es el paquete de alta que sale, no un bloque de documentación");
            String ficha = Zips.ver(guardarZip(Files.createTempDirectory("zips-ficha"), "ficha.zip",
                    "00 Ficha cliente/datos.txt", "x"));
            ok(ficha.contains("paquete de alta") && ficha.contains("00 Ficha cliente") && !ficha.contains("cliente «"),
                    "«00 Ficha cliente» también es el paquete que sale");
            String e1 = Zips.ver(guardarZip(Files.createTempDirectory("zips-e1"), "e1.zip",
                    "E1 - Nota/texto.txt", "x"));
            ok(e1.contains("paquete de alta") && e1.contains("E1 - Nota") && !e1.contains("cliente «"),
                    "«E1 - …» también es el paquete que sale");

            String slip = Zips.ver(guardarZip(Files.createTempDirectory("zips-slip"), "slip.zip",
                    "Cliente/../../fuera.pdf", "no"));
            ok(slip.contains("No se abre") && slip.contains("se sale") && !slip.contains("cliente «"),
                    "una ruta que se sale de la carpeta se rechaza y no rompe");

            Path cifrado = Files.createTempDirectory("zips-cifrado").resolve("cifrado.zip");
            Files.write(cifrado, zipCifrado());
            String cif = Zips.ver(cifrado);
            ok(cif.contains("No se abre") && cif.contains("cifrado") && !cif.contains("cliente «"),
                    "un ZIP cifrado se rechaza y no rompe");

            Path roto = Files.createTempDirectory("zips-roto").resolve("roto.zip");
            Files.write(roto, new byte[] {0x50, 0x4b, 3, 4, 9});
            String mal = Zips.ver(roto);
            ok(mal.contains("No se abre") && !mal.contains("cliente «"),
                    "un ZIP roto se explica y no rompe");
            ok(Zips.ver(doc.resolve("no-esta.zip")).startsWith("No está"),
                    "un ZIP que no está se dice y no rompe");

            String sinFicheros = Zips.ver(guardarZip(Files.createTempDirectory("zips-sin"), "vacio.zip",
                    "Cliente/Entidad/no.docx", "x",
                    "__MACOSX/Cliente/Entidad/._a.pdf", "m"));
            ok(sinFicheros.contains("No hay ficheros") && !sinFicheros.contains("No se abre") && !sinFicheros.contains("docx"),
                    "si no hay pdf, jpg, jpeg, png, webp, heic ni txt, se dice y no se rechaza el ZIP");
            ok(Zips.informe(vacia, List.of()).equals(Zips.listar(vacia)),
                    "una carpeta vacía sigue sin abrir nada");
        } catch (java.io.IOException e) {
            ok(false, "los ZIPs se listan sin romperse: " + e.getMessage());
        }
    }

    /**
     * Leer los papeles: que se le enseñen los documentos como lo que son, que se entienda lo que contesta y, sobre
     * todo, que un alta con dudas o sin NIF NO siga.
     */
    static void lectura() {
        ok(Lectura.comoSeManda("pdf").equals("application/pdf")
                        && Lectura.comoSeManda("jpg").equals("image/jpeg")
                        && Lectura.comoSeManda("JPG".toLowerCase(java.util.Locale.ROOT)).equals("image/jpeg")
                        && Lectura.comoSeManda("png").equals("image/png")
                        && Lectura.comoSeManda("txt").equals("texto")
                        && Lectura.comoSeManda("docx") == null,
                "cada documento se manda como lo que es (el PDF como PDF y la foto como foto)");

        try {
            Path dir = Files.createTempDirectory("lectura");
            byte[] kviku = zipDe("contrato.pdf", "%PDF-1.4 contrato");
            Path zip = guardarZip(dir, "bloque.zip",
                    "Garcia Prueba/dni.pdf", "%PDF-1.4 dni",
                    "Garcia Prueba/justificante.jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2},
                    "Garcia Prueba/notas.txt", "llamar por la tarde",
                    "Garcia Prueba/KVIKU.zip", kviku);
            Zips.Bloque bloque = Zips.abrir(zip);
            ok(bloque.vale() && bloque.documentos().size() == 4, "del bloque salen los documentos con su contenido");
            ok(bloque.documentos().stream().anyMatch(d -> d.nombre().equals("contrato.pdf") && d.entidad().equals("KVIKU")
                            && d.ruta().contains("KVIKU.zip")),
                    "un documento del ZIP de dentro trae su entidad");
            ok(bloque.documentos().stream().allMatch(d -> d.datos().length > 0), "los documentos vienen con sus bytes");

            List<Object> partes = Lectura.comoSeLeEnsena(bloque);
            String todo = Json.escribir(Json.obj("x", partes));
            ok(todo.contains("Garcia Prueba/dni.pdf")
                            && todo.contains("Garcia Prueba/KVIKU.zip") && todo.contains("contrato.pdf"),
                    "cada documento va con su ruta delante, para poder decir después qué es cada uno");
            ok(todo.contains("[entidad/expediente: KVIKU]"), "la carpeta de dentro se le enseña como pista de la entidad");
            ok(todo.contains("application/pdf") && todo.contains("image/jpeg"), "los PDF y las fotos van tal cual");
            ok(todo.contains("llamar por la tarde"), "un .txt va como texto, sin base64");
            long documentos = partes.stream().filter(x -> x instanceof Map<?, ?> m
                    && ("document".equals(m.get("type")) || "image".equals(m.get("type")))).count();
            ok(documentos == 3, "van los tres documentos de verdad (el .txt es texto, no adjunto)");

            Lectura.Resultado bueno = Lectura.deJson("""
                    Aquí va lo que he leído:
                    {"cliente": {"nombre": "Angela", "apellidos": "Garcia Prueba", "nif": "00000000t",
                                 "telefono": "600 11 22 33", "correo": "a@b.test", "domicilio": "", "fecha_nacimiento": "14/03/1985"},
                     "expedientes": [{"entidad": "KVIKU", "tipo": "usura", "contrato": "KV-2024-0098765"}],
                     "ficheros": [{"ruta": "Garcia Prueba/dni.pdf", "que_es": "identidad"},
                                  {"ruta": "Garcia Prueba/KVIKU.zip -> contrato.pdf", "que_es": "contrato"}],
                     "dudas": []}
                    """);
            ok(bueno.cliente().get("nif").equals("00000000t") && bueno.cliente().get("nombre").equals("Angela"),
                    "del JSON salen los datos del cliente tal como vienen, sin tocarlos");
            ok(bueno.expedientes().size() == 1 && bueno.ficheros().size() == 2, "salen los expedientes y los ficheros");
            ok(bueno.completo(), "con nombre, apellidos, NIF y sin dudas, el alta sigue");
            String cuenta = Lectura.comoSeCuenta(bloque, bueno);
            ok(cuenta.contains("LISTO") && cuenta.contains("identidad") && cuenta.contains("KVIKU"),
                    "el resumen que ve una persona dice lo que ha salido");

            Lectura.Resultado conDuda = Lectura.deJson("""
                    {"cliente": {"nombre": "Angela", "apellidos": "Garcia", "nif": "00000000T"},
                     "expedientes": [], "ficheros": [],
                     "dudas": ["La letra del DNI no cuadra con el número"]}
                    """);
            ok(!conDuda.completo() && conDuda.queFalta().isEmpty(),
                    "una sola duda para el alta, aunque no falte ningún dato");
            ok(Lectura.comoSeCuenta(bloque, conDuda).contains("NO SE SIGUE")
                            && Lectura.comoSeCuenta(bloque, conDuda).contains("no cuadra"),
                    "cuando no se sigue, se dice por qué y con las palabras de la duda");

            Lectura.Resultado sinNif = Lectura.deJson("""
                    {"cliente": {"nombre": "Angela", "apellidos": "Garcia", "nif": ""},
                     "expedientes": [], "ficheros": [], "dudas": []}
                    """);
            ok(!sinNif.completo() && sinNif.queFalta().equals(List.of("nif")),
                    "sin NIF no se da de alta a nadie, y se dice qué falta");

            Lectura.Resultado vacio = Lectura.deJson("{}");
            ok(!vacio.completo() && vacio.queFalta().size() == 3 && vacio.dudas().isEmpty(),
                    "si no contesta nada, faltan los tres datos imprescindibles");

            try {
                Lectura.deJson("No he podido leer los documentos.");
                ok(false, "una contestación sin datos no se da por buena");
            } catch (IllegalStateException e) {
                ok(e.getMessage().contains("ningún dato"), "una contestación sin datos no se da por buena");
            }

            Cerebro miente = peticion -> respuesta(Json.obj("type", "text", "text",
                    "{\"cliente\": {\"nombre\": \"Angela\", \"apellidos\": \"Garcia\", \"nif\": \"00000000T\"},"
                    + " \"expedientes\": [], \"ficheros\": [], \"dudas\": []}"));
            Lectura.Resultado delCerebro = Lectura.leer(miente, "modelo-de-prueba", "instrucciones", bloque);
            ok(delCerebro.completo() && delCerebro.cliente().get("nif").equals("00000000T"),
                    "la lectura entera funciona de punta a punta");

            Path malo = guardarZip(Files.createTempDirectory("lectura-mal"), "mal.zip",
                    "Cliente/Entidad/DeMas/contrato.pdf", "de-mas");
            ok(!Zips.abrir(malo).vale(), "un bloque que no pasa las normas tampoco se lee");
            try {
                Lectura.leer(miente, "m", "i", Zips.abrir(malo));
                ok(false, "no se le enseñan a la IA los papeles de un bloque rechazado");
            } catch (IllegalArgumentException e) {
                ok(true, "no se le enseñan a la IA los papeles de un bloque rechazado");
            }
        } catch (java.io.IOException e) {
            ok(false, "leer los papeles no se rompe: " + e.getMessage());
        }
    }

    /** Un ZIP mínimo en memoria. Los datos pueden ser texto o los bytes de otro ZIP. */
    static byte[] zipDe(Object... partes) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            for (int i = 0; i < partes.length; i += 2) {
                zos.putNextEntry(new ZipEntry((String) partes[i]));
                byte[] datos = partes[i + 1] instanceof byte[] b ? b : String.valueOf(partes[i + 1]).getBytes(StandardCharsets.UTF_8);
                zos.write(datos);
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    static Path guardarZip(Path dir, String nombre, Object... partes) throws java.io.IOException {
        Path p = dir.resolve(nombre);
        Files.write(p, zipDe(partes));
        return p;
    }

    /** ZIP almacenado con el bit de cifrado: Java lo rechaza al abrirlo, sin contraseña de verdad. */
    static byte[] zipCifrado() {
        byte[] nombre = "Cliente/Entidad/a.pdf".getBytes(StandardCharsets.UTF_8);
        byte[] datos = new byte[] {9, 9, 9, 9};
        CRC32 crc = new CRC32();
        crc.update(datos);
        int crcV = (int) crc.getValue();
        int n = nombre.length;
        int tam = 30 + n + datos.length + 46 + n + 22;
        ByteBuffer bb = ByteBuffer.allocate(tam).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0x04034b50);
        bb.putShort((short) 20);
        bb.putShort((short) 1);
        bb.putShort((short) 0);
        bb.putShort((short) 0);
        bb.putShort((short) 0);
        bb.putInt(crcV);
        bb.putInt(datos.length);
        bb.putInt(datos.length);
        bb.putShort((short) n);
        bb.putShort((short) 0);
        bb.put(nombre);
        bb.put(datos);
        int cd = bb.position();
        bb.putInt(0x02014b50);
        bb.putShort((short) 20);
        bb.putShort((short) 20);
        bb.putShort((short) 1);
        bb.putShort((short) 0);
        bb.putShort((short) 0);
        bb.putShort((short) 0);
        bb.putInt(crcV);
        bb.putInt(datos.length);
        bb.putInt(datos.length);
        bb.putShort((short) n);
        bb.putShort((short) 0);
        bb.putShort((short) 0);
        bb.putShort((short) 0);
        bb.putShort((short) 0);
        bb.putInt(0);
        bb.putInt(0);
        bb.put(nombre);
        int cdTam = bb.position() - cd;
        bb.putInt(0x06054b50);
        bb.putShort((short) 0);
        bb.putShort((short) 0);
        bb.putShort((short) 1);
        bb.putShort((short) 1);
        bb.putInt(cdTam);
        bb.putInt(cd);
        bb.putShort((short) 0);
        if (bb.position() != tam) {
            throw new IllegalStateException("ZIP cifrado mal armado: " + bb.position() + " de " + tam);
        }
        return bb.array();
    }

    static Map<String, Object> texto(String t) {
        return Json.obj("type", "text", "text", t);
    }

    @SafeVarargs
    static Map<String, Object> respuesta(Map<String, Object>... bloques) {
        boolean herramientas = Arrays.stream(bloques).anyMatch(b -> "tool_use".equals(b.get("type")));
        return Json.obj("content", new ArrayList<Object>(List.of(bloques)), "stop_reason", herramientas ? "tool_use" : "end_turn",
                "usage", Json.obj("input_tokens", 100L, "output_tokens", 10L, "cache_read_input_tokens", 50L));
    }

    private static final class Arrays {
        static <T> java.util.stream.Stream<T> stream(T[] a) { return java.util.Arrays.stream(a); }
    }
}
