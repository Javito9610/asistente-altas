package es.amagna.asistente;

import es.amagna.asistente.altas.Alta;
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
        percepcion();
        plan();
        receta();
        estados();
        campoInterior();
        auditor();
        anclas();
        pedidos();
        alta();
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
        ok(pDialogo > 80 && pDialogo > pPrincipal && pExacto > pDialogo,
                "se prefiere el diálogo visible «Selección de Ejercicio Contable» a la ventana principal");

        // Esto se descartaba por el título, y descartaba de paso la ventana de las pruebas y a cualquier cliente
        // que se llamara «Asistencia». Una consola se reconoce por lo que es, no por cómo se llama.
        ok(es.amagna.asistente.manos.VentanasNativas.esRuidoDeBusqueda("")
                        && es.amagna.asistente.manos.VentanasNativas.esRuidoDeBusqueda("   ")
                        && es.amagna.asistente.manos.VentanasNativas.esRuidoDeBusqueda(null)
                        && !es.amagna.asistente.manos.VentanasNativas.esRuidoDeBusqueda("prueba-asistente-123.txt - Notepad")
                        && !es.amagna.asistente.manos.VentanasNativas.esRuidoDeBusqueda("Asistencia Tecnica S.L. - Ficha"),
                "una ventana no se descarta porque su título tenga una palabra en la lista negra");
        ok(es.amagna.asistente.manos.VentanasNativas.puntosDeVentana(
                        "asistente", "prueba-asistente-123.txt - Notepad", false, false) > 0,
                "y se puede buscar una ventana cuyo título lleve esa palabra");

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
     * Percepción de la pantalla y entorno: la parte que se puede comprobar sin una pantalla delante. (Que Windows
     * avise de verdad de una ventana nueva se comprueba en PruebasWindows, contra Windows de verdad.)
     */
    static void percepcion() {
        // --- Dónde estamos: de tres datos sueltos a una respuesta ---
        var S = es.amagna.asistente.manos.Entorno.Sesion.class;
        ok(es.amagna.asistente.manos.Entorno.deducir(0, true, 0) == S.getEnumConstants()[3]
                        && es.amagna.asistente.manos.Entorno.deducir(0, false, -1).name().equals("SIN_ESCRITORIO"),
                "la sesión 0 (la de los servicios) no tiene escritorio, aunque Windows diga lo que diga");
        ok(es.amagna.asistente.manos.Entorno.deducir(2, true, 0).name().equals("ACTIVA")
                        && es.amagna.asistente.manos.Entorno.deducir(2, false, 0).name().equals("BLOQUEADA"),
                "una sesión «activa» sin escritorio de entrada es una pantalla bloqueada");
        ok(es.amagna.asistente.manos.Entorno.deducir(2, false, 4).name().equals("DESCONECTADA")
                        && es.amagna.asistente.manos.Entorno.deducir(2, true, 4).name().equals("DESCONECTADA"),
                "escritorio remoto cerrado: la sesión sigue viva pero desconectada");
        ok(es.amagna.asistente.manos.Entorno.deducir(2, false, 5).name().equals("BLOQUEADA"),
                "pantalla bloqueada");
        ok(es.amagna.asistente.manos.Entorno.deducir(1, false, -1).name().equals("NO_SE_SABE")
                        && es.amagna.asistente.manos.Entorno.deducir(-1, true, -1).name().equals("ACTIVA"),
                "si Windows no contesta, se dice «no se sabe» y no se inventa");

        // --- Qué se puede hacer según dónde estemos ---
        var desconectada = new es.amagna.asistente.manos.Entorno.Estado(
                es.amagna.asistente.manos.Entorno.Sesion.DESCONECTADA, false, true, 2, false);
        ok(desconectada.puedeManejarVentanas() && !desconectada.puedeUsarRaton() && !desconectada.puedeFotografiarLaPantalla(),
                "sesión desconectada: las ventanas se manejan por dentro, pero no hay ratón ni foto de pantalla");
        ok(desconectada.enUnaFrase().contains("sin ratón"), "y lo dice con palabras");
        var servicio = new es.amagna.asistente.manos.Entorno.Estado(
                es.amagna.asistente.manos.Entorno.Sesion.SIN_ESCRITORIO, false, false, 0, false);
        ok(!servicio.puedeManejarVentanas() && servicio.enUnaFrase().startsWith("Aquí no se puede trabajar"),
                "como servicio de Windows no se puede trabajar, y no lo disimula");
        var activa = new es.amagna.asistente.manos.Entorno.Estado(
                es.amagna.asistente.manos.Entorno.Sesion.ACTIVA, true, false, 1, false);
        ok(activa.puedeManejarVentanas() && activa.puedeUsarRaton() && activa.puedeFotografiarLaPantalla(),
                "sesión normal: todo disponible");
        var sinPantallaJava = new es.amagna.asistente.manos.Entorno.Estado(
                es.amagna.asistente.manos.Entorno.Sesion.ACTIVA, true, false, 1, true);
        ok(!sinPantallaJava.puedeUsarRaton(), "si Java no tiene pantalla (headless) tampoco hay ratón");

        // --- El dibujo de una ventana: cuándo se da por «en blanco» ---
        int[] liso = new int[3840 * 2160];
        ok(es.amagna.asistente.manos.Captura.esUniforme(liso), "un lienzo de un solo color es «en blanco»");
        int[] conTexto = new int[3840 * 2160];
        conTexto[1234567] = 0x00FFFFFF; // un solo píxel de texto en una pantalla 4K
        ok(!es.amagna.asistente.manos.Captura.esUniforme(conTexto),
                "un solo píxel distinto basta: una ventana casi toda gris con una línea de texto NO está en blanco");
        int[] soloRelleno = {0xFF000000, 0x00000000, 0x7F000000};
        ok(es.amagna.asistente.manos.Captura.esUniforme(soloRelleno),
                "el cuarto byte es relleno, no transparencia: no cuenta como un color distinto");
        ok(es.amagna.asistente.manos.Captura.esUniforme(new int[0]) && es.amagna.asistente.manos.Captura.esUniforme(null),
                "sin píxeles no hay nada visto");

        // --- Reducir sin deformar y sin agrandar ---
        var grande = new java.awt.image.BufferedImage(3840, 2160, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var r1 = es.amagna.asistente.manos.Captura.reducida(grande, 1280, 720);
        ok(r1.getWidth() == 1280 && r1.getHeight() == 720, "4K cabe en 1280x720 exacto");
        var ancha = new java.awt.image.BufferedImage(2000, 1000, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var r2 = es.amagna.asistente.manos.Captura.reducida(ancha, 1280, 720);
        ok(r2.getWidth() == 1280 && r2.getHeight() == 640 || r2.getWidth() == 1440 && r2.getHeight() == 720,
                "se conserva la proporción: 2:1 sigue siendo 2:1");
        ok(r2.getHeight() <= 720 && r2.getWidth() <= 1280 && (double) r2.getWidth() / r2.getHeight() > 1.99,
                "y cabe en el recuadro");
        var pequena = new java.awt.image.BufferedImage(100, 50, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ok(es.amagna.asistente.manos.Captura.reducida(pequena, 1280, 720) == pequena, "una imagen pequeña no se agranda");
        var noHay = es.amagna.asistente.manos.Captura.noHay("sin ventana");
        ok(!noHay.valido() && noHay.png() == null && noHay.pngQueCabe(100, 100) == null && noHay.problema().equals("sin ventana"),
                "cuando no hay ventana, no hay imagen y se dice por qué (nunca una imagen vacía)");

        // --- Los avisos de Windows: lo que se puede comprobar sin Windows ---
        var P = es.amagna.asistente.manos.Percepcion.class;
        es.amagna.asistente.manos.Percepcion.registrar(new es.amagna.asistente.manos.Percepcion.Cambio(1, 0x8002, 111));
        var vig = es.amagna.asistente.manos.Percepcion.mirar();
        es.amagna.asistente.manos.Percepcion.registrar(new es.amagna.asistente.manos.Percepcion.Cambio(2, 0x8002, 222));
        es.amagna.asistente.manos.Percepcion.registrar(new es.amagna.asistente.manos.Percepcion.Cambio(3, 0x8000, 333));
        var visto = es.amagna.asistente.manos.Percepcion.esperar(vig, c -> c.evento() == 0x8000, 2);
        ok(visto != null && visto.ventana() == 333,
                "lo que pasó DESPUÉS de abrir la vigilia se ve, aunque pasara antes de ponerse a esperar");
        ok(es.amagna.asistente.manos.Percepcion.esperar(vig, c -> c.ventana() == 111, 0.2) == null,
                "y lo que pasó ANTES de abrirla no se cuela");
        ok(es.amagna.asistente.manos.Percepcion.esperar(vig, c -> true, 0.2) == null,
                "un cambio ya visto no se entrega dos veces");
        ok(visto != null && visto.comoSeLlama().equals("creada"), "los eventos se cuentan con palabras");

        // El caso que importa: SIN enganche de Windows, esperar tiene que DORMIR, no devolver al instante.
        // (Quien lo llama espera para no martillear UI Automation; si volviera de inmediato sería un bucle sin pausa.)
        ok(!es.amagna.asistente.manos.Percepcion.disponible(), "en esta prueba no hay enganche de Windows");
        var v2 = es.amagna.asistente.manos.Percepcion.mirar();
        long t0 = System.nanoTime();
        var nada = es.amagna.asistente.manos.Percepcion.esperar(v2, c -> true, 0.4);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        ok(nada == null && ms >= 350 && ms < 1500,
                "sin enganche, esperar duerme lo que se le pide (" + ms + " ms), no devuelve al instante");

        long t1 = System.nanoTime();
        boolean nunca = es.amagna.asistente.manos.Percepcion.esperarA(() -> false, 0.6);
        long ms1 = (System.nanoTime() - t1) / 1_000_000;
        ok(!nunca && ms1 >= 550 && ms1 < 2000, "esperarA se rinde al acabar el tiempo (" + ms1 + " ms)");

        long t2 = System.nanoTime();
        ok(es.amagna.asistente.manos.Percepcion.esperarA(() -> true, 5), "esperarA con la condición ya cumplida contesta que sí");
        ok((System.nanoTime() - t2) / 1_000_000 < 200, "y no espera nada");

        long[] cuando = {0};
        long ini = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                return;
            }
            cuando[0] = System.currentTimeMillis();
        });
        t.start();
        boolean llego = es.amagna.asistente.manos.Percepcion.esperarA(() -> cuando[0] != 0, 5);
        long tardo = System.currentTimeMillis() - ini;
        ok(llego && tardo < 1200, "si la condición se cumple a mitad de espera, se entera enseguida (" + tardo + " ms)");

        // La memoria no crece sin fin y las vigilias antiguas no se rompen al tirar lo viejo.
        var vieja = es.amagna.asistente.manos.Percepcion.mirar();
        long antes = es.amagna.asistente.manos.Percepcion.vistos();
        for (int i = 0; i < 6000; i++) {
            es.amagna.asistente.manos.Percepcion.registrar(new es.amagna.asistente.manos.Percepcion.Cambio(i, 0x8003, 1000 + i));
        }
        ok(es.amagna.asistente.manos.Percepcion.vistos() == antes + 6000, "se cuentan todos los cambios aunque se tiren los viejos");
        ok(es.amagna.asistente.manos.Percepcion.ultimos(10_000).size() <= 4000, "pero solo se guardan los últimos 4000");
        var tras = es.amagna.asistente.manos.Percepcion.esperar(vieja, c -> true, 0.2);
        ok(tras != null && tras.ventana() >= 1000, "una vigilia abierta antes de la poda sigue funcionando: ve lo que queda");
    }

    /**
     * Leer los papeles: que se le enseñen los documentos como lo que son, que se entienda lo que contesta y, sobre
     * todo, que un alta con dudas o sin NIF NO siga.
     */

    // ---- mapa de estados ----
    static String ventanasDe(String estado) {
        String mn = "- «MNprogram - Abogados - Empresa de prueba [\\Usu1] Año 2026» (MNprogram)\n- «C:\\Windows\\system32\\cmd.exe» (WindowsTerminal)";
        return switch (estado) {
            case "fuera" -> "- «Claude» (claude)\n- «Explorador» (explorer)";
            case "inicio" -> mn;
            case "lista" -> "- «Expedientes» (GestorConsultas) [delante, con el foco]\n" + mn;
            case "form" -> "- «Expediente 36/2026» (GestorConsultas) [delante, con el foco]\n- «Expedientes» (GestorConsultas)\n" + mn;
            case "atencion", "atencion-otra" -> "- «Atención» (MNprogram) [delante, con el foco]\n- «Expediente 36/2026» (GestorConsultas) [BLOQUEADA: tiene un diálogo encima]\n- «Expedientes» (GestorConsultas) [BLOQUEADA]\n" + mn;
            case "selector" -> "- «Clientes» (GestorConsultas) [delante, con el foco]\n- «Expediente 36/2026» (GestorConsultas) [BLOQUEADA: tiene un diálogo encima]\n- «Expedientes» (GestorConsultas) [BLOQUEADA]\n" + mn;
            case "selector-contrario" -> "- «Contrarios» (GestorConsultas) [delante, con el foco]\n- «Expediente 36/2026» (GestorConsultas) [BLOQUEADA: tiene un diálogo encima]\n- «Expedientes» (GestorConsultas) [BLOQUEADA]\n" + mn;
            default -> throw new IllegalArgumentException(estado);
        };
    }

    /** Un MN de mentira: el estado cambia con lo que se «pulsa». */
    static final class MundoFalso {
        String estado;
        int guardados;
        int rescates;
        String falla; // acción que falla una vez
        boolean fallaGuardar;
        boolean auditaMal;
        String fallaSiempre;
        boolean atascaAlFallar;
        boolean sinContrario;
        java.util.List<String> espia;
        java.util.Map<String, String> extra = java.util.Map.of();
        String cliente = "1";

        MundoFalso(String e) {
            estado = e;
        }
    }

    static es.amagna.asistente.estados.Ejecutor.Resultado correrFalso(es.amagna.asistente.estados.Mapa mapa, MundoFalso w, boolean conIa) {
        return ejecutorFalso(mapa, w, conIa).ejecutar("crear-expediente", parametrosFalsos(w));
    }

    static es.amagna.asistente.estados.Ejecutor ejecutorFalso(es.amagna.asistente.estados.Mapa mapa, MundoFalso w, boolean conIa) {
        long[] t = {0};
        es.amagna.asistente.estados.Ejecutor.Reloj reloj = new es.amagna.asistente.estados.Ejecutor.Reloj() {
            public long ahora() {
                return t[0];
            }

            public void dormir(long ms) {
                t[0] += ms;
            }
        };
        es.amagna.asistente.estados.Ejecutor.Hace hace = (p, ps) -> {
            if (w.falla != null && w.falla.equals(p.accion())) {
                w.falla = null;
                if (w.atascaAlFallar) {
                    w.estado = "atencion-otra";
                }
                return "no ha reaccionado";
            }
            if (w.espia != null && p.accion().equals("type")) {
                w.espia.add(es.amagna.asistente.cerebro.Receta.sustituir(String.valueOf(p.in().get("text")), ps));
            }
            if (w.fallaSiempre != null && w.fallaSiempre.equals(p.accion())) {
                if (w.atascaAlFallar) {
                    w.estado = "atencion-otra";
                }
                return "no ha reaccionado nunca";
            }
            if (w.auditaMal && p.accion().equals("auditar")) {
                return "AUDITORÍA NO OK (no coincide: Cliente)";
            }
            switch (p.accion()) {
                case "abrir_mn" -> w.estado = "inicio";
                case "ruta" -> w.estado = "lista";
                case "pulsar_hasta" -> w.estado = String.valueOf(p.in().get("control")).contains("Sí") ? "lista" : "form";
                case "pulsar_control" -> {
                    if (String.valueOf(p.in().get("control")).contains("Cancelar")) {
                        w.estado = "lista";
                    }
                }
                case "key" -> {
                    if (w.estado.equals("selector") || w.estado.equals("selector-contrario")) {
                        w.estado = "form";
                    } else if (w.sinContrario && w.estado.equals("form") && "Return".equals(p.in().get("text"))) {
                        w.estado = "selector-contrario"; // el NIF no sale, o sale más de uno: la ventana sigue abierta
                    }
                }
                case "double_click" -> {
                    if (p.rel() != null && p.rel()[0] == 597) {
                        w.estado = "selector";
                    }
                }
                case "left_click" -> {
                    if (p.rel() != null && p.rel()[0] == 69) {
                        w.estado = "form";
                    }
                    if (p.rel() != null && p.rel()[0] == 317) {
                        if (w.fallaGuardar) {
                            return "se colgó";
                        }
                        w.guardados++;
                        w.estado = "lista";
                    }
                }
                default -> { }
            }
            return null;
        };
        es.amagna.asistente.estados.Ejecutor.Rescate rescate = conIa ? (n, sit) -> {
            w.rescates++;
            w.estado = "lista"; // la IA deja la pantalla limpia
        } : null;
        es.amagna.asistente.estados.Ejecutor.Observa observa = new es.amagna.asistente.estados.Ejecutor.Observa() {
            public String ventanas() {
                return ventanasDe(w.estado);
            }

            public String texto(String ventana) {
                return w.estado.equals("atencion") ? "¿Deseas salir sin guardar?" : "¿Actualizar el número a la serie?";
            }
        };
        return new es.amagna.asistente.estados.Ejecutor(mapa, observa, hace, rescate, reloj, x -> { });
    }

    static java.util.Map<String, String> parametrosFalsos(MundoFalso w) {
        var m = new java.util.LinkedHashMap<String, String>();
        m.put("descripcion", "MICRO");
        m.put("cliente", w.cliente);
        m.putAll(w.extra);
        return m;
    }

    static void estados() {
        try {
            estados2();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void estados2() throws Exception {
        var mapa = es.amagna.asistente.estados.Mapa.leer(java.nio.file.Path.of("estados", "mn.json"));
        ok(mapa.tareas().containsKey("crear-expediente") && mapa.accion("guardar").toca() && !mapa.accion("alta").toca(),
                "estados: el mapa de MN se lee y se valida; Guardar está marcado como irreversible");
        var R = es.amagna.asistente.estados.Reconocedor.class;
        ok(es.amagna.asistente.estados.Reconocedor.estado(mapa, ventanasDe("fuera")).equals("fuera")
                        && es.amagna.asistente.estados.Reconocedor.estado(mapa, ventanasDe("inicio")).equals("inicio")
                        && es.amagna.asistente.estados.Reconocedor.estado(mapa, ventanasDe("lista")).equals("lista-expedientes")
                        && es.amagna.asistente.estados.Reconocedor.estado(mapa, ventanasDe("form")).equals("formulario-expediente"),
                "estados: reconoce fuera, inicio, lista y formulario (el número del expediente no importa)");
        ok(es.amagna.asistente.estados.Reconocedor.estado(mapa, ventanasDe("selector")).equals("selector-cliente"),
                "estados: con el selector delante, las ventanas de debajo están bloqueadas y no cuentan");
        ok(es.amagna.asistente.estados.Reconocedor.estado(mapa, "- «Cargando...» (MNprogram) [delante]\n" + ventanasDe("inicio")).equals("cargando"),
                "estados: «Cargando...» se reconoce");
        String mnc = "- «MNprogram - Abogados - Empresa de prueba [\\Usu1] Año 2026» (MNprogram)\n";
        ok(es.amagna.asistente.estados.Reconocedor.estado(mapa, "- «Clientes» (GestorConsultas) [delante, con el foco]\n- «Expedientes» (GestorConsultas)\n" + mnc)
                        .equals("lista-clientes")
                        && es.amagna.asistente.estados.Reconocedor.estado(mapa, ventanasDe("selector")).equals("selector-cliente"),
                "estados: la lista «Clientes» y el selector «Clientes» de un expediente se distinguen (el selector tiene un expediente debajo)");
        ok(es.amagna.asistente.estados.Reconocedor.estado(mapa, "- «Alta Cliente» (GestorConsultas) [delante, con el foco]\n- «Clientes» (GestorConsultas) [BLOQUEADA: tiene un diálogo encima]\n" + mnc)
                        .equals("alta-cliente")
                        && es.amagna.asistente.estados.Reconocedor.estado(mapa, "- «NOELIA TESTEO PRUEBA - Editar Cliente» (GestorConsultas) [delante]\n- «Clientes» (GestorConsultas) [BLOQUEADA]\n" + mnc)
                        .equals("editar-cliente"),
                "estados: «Alta Cliente» y «<nombre> - Editar Cliente» (título que acaba así) se reconocen");
        ok(es.amagna.asistente.estados.Reconocedor.estado(mapa, "- «Atención» (MNprogram) [delante]\n- «Alta Cliente» (GestorConsultas) [BLOQUEADA]\n" + mnc,
                        t -> "Se va a cerrar la ficha. ¿Deseas guardar los cambios realizados?").equals("atencion-guardar"),
                "estados: el aviso de «¿guardar los cambios?» de la ficha de cliente tiene su estado");
        var cc = es.amagna.asistente.estados.Planificador.camino(mapa, "inicio", "lista-clientes");
        ok(cc != null && cc.size() == 1 && cc.get(0).nombre().equals("abrir-lista-clientes-desde-inicio"),
                "estados: desde «inicio» la lista de clientes se abre directa (botón de la barra), sin pasar por el menú de expedientes");
        ok(mapa.tareas().containsKey("crear-cliente") && mapa.accion("guardar-cliente").toca() && !mapa.accion("rellenar-cliente").toca(),
                "estados: la tarea crear-cliente existe y solo Guardar es irreversible");
        ok(es.amagna.asistente.estados.Reconocedor.estado(mapa, "- «Abrir» (GestorConsultas) [delante]\n- «Alta Cliente» (GestorConsultas) [BLOQUEADA]\n" + mnc).equals("abrir-archivo")
                        && es.amagna.asistente.estados.Reconocedor.estado(mapa, "- «Documento» (GestorConsultas) [delante]\n- «Alta Cliente» (GestorConsultas) [BLOQUEADA]\n" + mnc).equals("documento-adjunto")
                        && es.amagna.asistente.estados.Planificador.camino(mapa, "abrir-archivo", "alta-cliente").size() == 1,
                "estados: los diálogos de importar adjuntos se reconocen y tienen salida sin guardar nada");
        {
            var hechos = new java.util.ArrayList<String>();
            var mundo = new es.amagna.asistente.estados.Recuperador.Mundo() {
                public boolean activar(String t) {
                    hechos.add("activar:" + t);
                    return true;
                }

                public boolean cerrar(String t) {
                    hechos.add("cerrar:" + t);
                    return true;
                }
            };
            String pantalla = "- «Superposición de Herramienta Recortes» (SnippingTool) [delante, con el foco] [pos 0,0 tam 1x1]\n"
                    + "- «Alta Cliente» (GestorConsultas) [pos 1,1 tam 5x5] — diálogos abiertos dentro: «Abrir»  [BLOQUEADA]\n"
                    + "- «justificante-prueba.txt: Bloc de notas» (Notepad) [pos 1,1 tam 5x5]\n"
                    + "- «*notas-de-Javi.txt: Bloc de notas» (Notepad) [pos 1,1 tam 5x5]\n"
                    + "- «dni-prueba.txt - Bloc de notas» (Notepad) [minimizada]\n";
            var params = java.util.Map.of("adjunto", "C:\\x\\pruebas\\ficha\\justificante-prueba.txt", "adjuntos_total", "3");
            var r = es.amagna.asistente.estados.Recuperador.revisar(pantalla, params, mundo);
            ok(r.size() == 2 && hechos.contains("cerrar:justificante-prueba.txt: Bloc de notas") && hechos.contains("activar:Abrir")
                            && hechos.stream().noneMatch(x -> x.contains("notas-de-Javi") || x.contains("dni-prueba")),
                    "recuperador: cierra solo el visor de un documento nuestro y devuelve el teclado al diálogo de MN; no toca lo ajeno");
            hechos.clear();
            ok(es.amagna.asistente.estados.Recuperador.revisar("- «Alta Cliente» (GestorConsultas) [delante, con el foco]\n", params, mundo).isEmpty() && hechos.isEmpty(),
                    "recuperador: con MN delante y nada raro, no hace nada");
        }
        var salir = es.amagna.asistente.estados.Planificador.camino(mapa, "alta-cliente", "lista-clientes");
        ok(salir != null && salir.stream().noneMatch(es.amagna.asistente.estados.Mapa.Transicion::toca)
                        && salir.get(0).nombre().equals("cancelar-alta-cliente"),
                "estados: para salir de una ficha a medias el planificador cancela, nunca guarda");
        try {
            var f1 = new java.awt.image.BufferedImage(400, 300, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var f2 = new java.awt.image.BufferedImage(400, 300, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var f3 = new java.awt.image.BufferedImage(400, 300, java.awt.image.BufferedImage.TYPE_INT_RGB);
            f2.setRGB(10, 10, 0xFFFFFF); // un parpadeo
            for (int y = 0; y < 300; y++) {
                for (int x = 0; x < 400; x++) {
                    f3.setRGB(x, y, x < 200 ? 0xFFFFFF : 0);
                }
            }
            java.util.function.Function<java.awt.image.BufferedImage, byte[]> png = im -> {
                try (var o = new java.io.ByteArrayOutputStream()) {
                    javax.imageio.ImageIO.write(im, "png", o);
                    return o.toByteArray();
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            };
            ok(Agente.casiIguales(png.apply(f1), png.apply(f2)) && !Agente.casiIguales(png.apply(f1), png.apply(f3)),
                    "pantalla quieta: un parpadeo suelto cuenta como quieta, un cambio grande no");
        } catch (RuntimeException e) {
            ok(false, "pantalla quieta: " + e);
        }
        ok("Alta Cliente".equals(Agente.tituloMnDelante("- «Claude» (claude) [delante]\n- «Expedientes» (GestorConsultas) [minimizada]\n- «Alta Cliente» (GestorConsultas) [pos 1,1 tam 5x5]\n"))
                && Agente.tituloMnDelante("- «Claude» (claude)\n") == null,
                "foto del fallo y espera de quietud miran la ventana de MN de delante, no una minimizada");
        {
            // Ruta alternativa: el segundo clic solo se da si el diálogo todavía no ha salido.
            var mini = es.amagna.asistente.estados.Mapa.desde("""
                {"principal":"P","reposo":"a","estados":[{"nombre":"a","ventana":"Aaa"}],
                 "transiciones":[{"nombre":"t","de":"a","a":"a","segundos":1,"pasos":[
                   {"accion":"uno","in":{}},
                   {"accion":"dos","in":{"_sin_ventana":"Dlg"}}]}],
                 "tareas":{"x":[{"hacer":"t"}]}}""");
            for (boolean sale : new boolean[] {false, true}) {
                var hechas = new java.util.ArrayList<String>();
                var ej = new es.amagna.asistente.estados.Ejecutor(mini,
                        () -> "- «Aaa» (GestorConsultas) [delante]\n" + (sale ? "- «Dlg» (GestorConsultas)\n" : ""),
                        (pp, ps) -> {
                            hechas.add(pp.accion());
                            return null;
                        }, null, new es.amagna.asistente.estados.Ejecutor.Reloj() {
                            long t;
                            public long ahora() {
                                return t;
                            }

                            public void dormir(long ms) {
                                t += ms;
                            }
                        }, x -> { });
                ej.ejecutar("x", java.util.Map.of());
                ok(hechas.equals(sale ? java.util.List.of("uno") : java.util.List.of("uno", "dos")),
                        "ruta alternativa: el paso «_sin_ventana» " + (sale ? "se salta si el diálogo ya salió" : "se hace si el diálogo no ha salido"));
            }
        }
        var c1 = es.amagna.asistente.estados.Planificador.camino(mapa, "fuera", "formulario-expediente");
        ok(c1 != null && c1.size() == 3 && c1.get(0).nombre().equals("abrir-mn") && c1.get(2).nombre().equals("alta"),
                "estados: de «fuera» al formulario hay tres pasos: abrir MN, abrir la lista, Alta");
        ok(es.amagna.asistente.estados.Planificador.camino(mapa, "selector-cliente", "lista-expedientes").size() == 2
                        && es.amagna.asistente.estados.Planificador.camino(mapa, "lista-expedientes", "lista-expedientes").isEmpty(),
                "estados: desde el selector se vuelve a la lista cerrándolo y cancelando; ya estar donde toca = nada que hacer");
        ok(es.amagna.asistente.estados.Planificador.camino(mapa, "lista-expedientes", "inicio") == null,
                "estados: sin camino, null (no se inventa uno)");
        for (String ini : List.of("fuera", "inicio", "lista", "form", "selector")) {
            var w = new MundoFalso(ini);
            var r = correrFalso(mapa, w, false);
            ok(r.bien() && w.guardados == 1 && r.numero().equals("36/2026") || (ini.equals("lista") || ini.equals("inicio") || ini.equals("fuera")) && r.bien() && w.guardados == 1,
                    "estados: crear-expediente sale bien y guarda UNA vez empezando en «" + ini + "»");
        }
        var wa = new MundoFalso("atencion");
        var ra = correrFalso(mapa, wa, false);
        ok(ra.bien() && wa.guardados == 1, "estados: con un «Atención: ¿salir?» abierto de antes, se contesta Sí y se sigue sin IA");
        var wb = new MundoFalso("atencion-otra");
        var rb = correrFalso(mapa, wb, false);
        ok(!rb.bien() && wb.guardados == 0, "estados: un «Atención» que NO es de salir no se contesta solo: se para");
        var wc = new MundoFalso("atencion-otra");
        var rc = correrFalso(mapa, wc, true);
        ok(rc.bien() && wc.rescates == 1 && wc.guardados == 1, "estados: ese aviso desconocido lo resuelve la IA y la tarea sigue");
        var wk = new MundoFalso("lista");
        var vistos = new java.util.ArrayList<String>();
        wk.espia = vistos;
        wk.extra = java.util.Map.of("contrario", "33857008-L", "adjunto", "C:\\x.txt");
        var rk = correrFalso(mapa, wk, false);
        ok(rk.bien() && wk.guardados == 1 && vistos.stream().anyMatch(x -> x.startsWith("33857008-L")) && vistos.contains("C:\\x.txt"),
                "estados: con contrario y adjunto se teclean sus datos; sin ellos esos pasos se saltan");
        var wsc = new MundoFalso("lista");
        wsc.sinContrario = true;
        wsc.extra = java.util.Map.of("contrario", "99999999-X", "contrario_nombre", "NADIE");
        var rsc = correrFalso(mapa, wsc, false);
        ok(rsc.bien() && wsc.guardados == 1 && rsc.incidencias().size() == 1 && rsc.incidencias().get(0).contains("99999999-X")
                        && rsc.mensaje().contains("CON INCIDENCIAS"),
                "estados: si el contrario no sale (o salen varios) el expediente se crea SIN contrario y queda una incidencia para el informe");
        var sinExtras = new MundoFalso("lista");
        var vistos2 = new java.util.ArrayList<String>();
        sinExtras.espia = vistos2;
        ok(correrFalso(mapa, sinExtras, false).bien() && vistos2.stream().noneMatch(t -> t.contains("33857008")),
                "estados: sin contrario ni adjunto no se hace nada de eso");
        var wm = new MundoFalso("lista");
        wm.cliente = "LucÃ\u00ada";
        var rm = correrFalso(mapa, wm, true);
        ok(!rm.bien() && wm.guardados == 0 && wm.rescates == 0 && rm.mensaje().contains("codificación rota"),
                "estados: un parámetro con la codificación rota (LucÃ­a) se rechaza antes de tocar nada");
        var wf = new MundoFalso("lista");
        wf.fallaSiempre = "double_click";
        var rf = correrFalso(mapa, wf, true);
        ok(!rf.bien() && wf.rescates == 0 && wf.guardados == 0 && rf.mensaje().contains("Falla igual"),
                "estados: si falla igual después de limpiar con el mapa, se para sin gastar IA (es el dato o el mapa)");
        var wg = new MundoFalso("lista");
        wg.fallaSiempre = "double_click";
        wg.atascaAlFallar = true;
        var rg = correrFalso(mapa, wg, true);
        ok(!rg.bien() && wg.rescates == 1 && wg.guardados == 0,
                "estados: si falla igual después de que la IA limpie, no se la llama otra vez");
        String conDialogo = "- «Expediente 37/2026» (GestorConsultas) — diálogos abiertos dentro: «Atención»  [BLOQUEADA: tiene un diálogo encima]\n"
                + "- «Expedientes» (GestorConsultas) [BLOQUEADA: tiene un diálogo encima]\n"
                + "- «MNprogram - Abogados - Empresa de prueba» (MNprogram)\n";
        ok(es.amagna.asistente.estados.Reconocedor.estado(mapa, conDialogo, t -> "¿Deseas salir?").equals("atencion-salir"),
                "estados: un diálogo que cuelga de otra ventana (sale en la línea de su dueña) también se reconoce");
        var wv = new MundoFalso("inicio");
        wv.auditaMal = true;
        var rv = correrFalso(mapa, wv, true);
        ok(!rv.bien() && wv.guardados == 0 && wv.rescates == 0 && rv.mensaje().contains("no se ha guardado nada")
                        && wv.estado.equals("lista"),
                "estados: si la auditoría no cuadra NO se guarda, NO se llama a la IA (no es un atasco) y la pantalla queda limpia");
        var w1 = new MundoFalso("inicio");
        w1.fallaSiempre = "ruta";
        var r1 = correrFalso(mapa, w1, false);
        ok(!r1.bien() && w1.guardados == 0 && r1.mensaje().contains("PARADO"), "estados: si un paso falla y no hay IA, se para y no guarda nada");
        var w2 = new MundoFalso("inicio");
        w2.falla = "pulsar_hasta";
        var r2 = correrFalso(mapa, w2, true);
        ok(r2.bien() && w2.guardados == 1 && w2.rescates == 0 && r2.rescates() == 0,
                "estados: si un paso falla y el mapa sabe limpiar la pantalla, la limpia él, reintenta y NO llama a la IA");
        var w2b = new MundoFalso("inicio");
        w2b.falla = "pulsar_hasta";
        w2b.atascaAlFallar = true;
        var r2b = correrFalso(mapa, w2b, true);
        ok(r2b.bien() && w2b.guardados == 1 && w2b.rescates == 1 && r2b.rescates() == 1,
                "estados: si el mapa no sabe salir de donde ha quedado, entonces sí da el empujón la IA, se reintenta y se guarda una vez");
        var w3 = new MundoFalso("form");
        w3.fallaGuardar = true;
        var r3 = correrFalso(mapa, w3, true);
        ok(!r3.bien() && w3.rescates == 0 && w3.guardados == 0 && r3.mensaje().contains("NO se repite"),
                "estados: si falla el Guardar no se repite la tarea (podría duplicar), se para y avisa");
        var w4 = new MundoFalso("inicio");
        var ej4 = ejecutorFalso(mapa, w4, true);
        var r4a = ej4.ejecutar("crear-expediente", parametrosFalsos(w4));
        w4.falla = "pulsar_hasta"; // la 2.ª tarea del MISMO Ejecutor falla antes de guardar
        var r4b = ej4.ejecutar("crear-expediente", parametrosFalsos(w4));
        ok(r4a.bien() && r4b.bien() && w4.guardados == 2 && !r4b.mensaje().contains("NO se repite"),
                "estados: lo ya guardado en una tarea no contamina la siguiente del mismo Ejecutor (se limpia y reintenta)");
    }

    static void campoInterior() {
        // MN: «Descripción Expediente» sale dos veces, un campo exterior grande y vacío y, dentro, el cuadro de verdad.
        List<int[]> r = List.of(new int[] {100, 200, 400, 30}, new int[] {104, 203, 380, 24});
        ok(es.amagna.asistente.manos.Controles.indiceMasInterior(r) == 1,
                "controles: de dos campos con el mismo nombre se elige el de dentro (el exterior sale vacío)");
        ok(es.amagna.asistente.manos.Controles.indiceMasInterior(List.of(new int[] {1, 1, 10, 10}, new int[] {1, 1, 10, 10})) == 0,
                "controles: a igual tamaño, el primero");
    }

    /** Una ventana de mentira: fondo gris con ruido fijo y un «botón» con letras dibujadas en (bx, by). */
    static java.awt.image.BufferedImage ventanaFalsa(int ancho, int alto, int bx, int by, boolean dosBotones) {
        var im = new java.awt.image.BufferedImage(ancho, alto, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.util.Random r = new java.util.Random(7);
        for (int y = 0; y < alto; y++) {
            for (int x = 0; x < ancho; x++) {
                int g = 200 + r.nextInt(6);
                im.setRGB(x, y, g << 16 | g << 8 | g);
            }
        }
        pintarBoton(im, bx, by);
        if (dosBotones) {
            pintarBoton(im, bx + 300, by + 100);
        }
        return im;
    }

    static void pintarBoton(java.awt.image.BufferedImage im, int bx, int by) {
        var g = im.createGraphics();
        g.setColor(new java.awt.Color(30, 60, 160));
        g.fillRect(bx, by, 70, 26);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(bx + 8, by + 6, 10, 14);
        g.fillRect(bx + 28, by + 10, 30, 4);
        g.dispose();
    }

    static void pedidos() {
        Map<String, Object> cli = new java.util.LinkedHashMap<>();
        cli.put("nombre", "Lucía");
        cli.put("apellidos", "Fernández  Prueba");
        cli.put("nif", "73.918.462-g");
        List<Object> fich = new java.util.ArrayList<>(List.of(
                Json.obj("ruta", "c/dni.pdf", "que_es", "identidad"),
                Json.obj("ruta", "c/hoja.pdf", "que_es", "hoja_encargo")));
        List<Object> exps = new java.util.ArrayList<>(List.of(
                Json.obj("entidad", "KVIKU FINANCE SPAIN, S.L.U.", "cif_entidad", "b 12345678", "descripcion", "MICRO",
                        "documentos", List.of("c/K/c1.pdf")),
                Json.obj("entidad", "SIN CIF S.A.", "descripcion", "PRESTAMO (T)", "documentos", List.of("c/S/c1.pdf")),
                Json.obj("entidad", "SIN DESCRIPCION S.L.", "cif_entidad", "B99999999", "descripcion", "LINEA", "documentos", List.of())));
        var l = new es.amagna.asistente.altas.Lectura.Resultado(cli, exps, fich, List.of(), "");
        var plan = es.amagna.asistente.altas.Plan.de(l, "ANDREA semana 3.zip");
        var r = es.amagna.asistente.altas.Pedido.de(l, plan);
        ok(r.pedidos().size() == 3 && r.noSeCrean().isEmpty() && !r.pedidos().get(2).params().containsKey("descripcion")
                        && r.pedidos().get(2).avisos().stream().anyMatch(a -> a.contains("descripción vacía")),
                "pedidos: sin descripción válida el expediente se crea con la descripción vacía (lo pidió Miguel) y se avisa; no se inventa una");
        var k = r.pedidos().get(0);
        ok("c/hoja.pdf".equals(r.pedidos().get(0).autorizacion()) && !r.pedidos().get(0).ficherosDelBloque().contains("c/hoja.pdf"),
                "pedidos: la hoja de encargo no se adjunta entera; de ella sale la autorización (página 2)");
        ok(r.pedidos().get(0).avisos().stream().noneMatch(a -> a.contains("hay que separarla")),
                "pedidos: el aviso de «hay que separarla» ya no sale, porque se separa");
        ok(k.params().get("cliente").equals("LUCÍA FERNÁNDEZ PRUEBA") && k.params().get("contrario").equals("B12345678")
                        && k.clave().equals("73918462G|KVIKUFINANCESPAINSLU|MICRO"),
                "pedidos: cliente en mayúsculas, CIF sin espacios, y clave NIF|entidad|descripción contra duplicados");
        ok("73918462G".equals(k.params().get("cliente_nif")), "el pedido lleva el NIF del cliente para buscarlo por NIF en MN");
        var s = r.pedidos().get(1);
        ok(!s.params().containsKey("contrario") && s.avisos().stream().anyMatch(a -> a.contains("sin contrario")),
                "pedidos: sin CIF en los papeles no se busca el contrario por nombre: expediente sin contrario y aviso");
        var m = es.amagna.asistente.altas.Pedido.conAdjuntos(k, List.of("C:\\t\\1-dni.pdf", "C:\\t\\2-contrato.pdf"));
        ok(m.get("adjunto").endsWith("1-dni.pdf") && m.get("adjunto2").endsWith("2-contrato.pdf") && m.get("adjunto_nombre").equals("1-dni"),
                "pedidos: los adjuntos van en adjunto, adjunto2…");
        ok("2".equals(m.get("adjuntos_total")) && "1-dni|2-contrato".equals(m.get("adjuntos_inicios")), "pedidos: total de adjuntos y cómo empieza cada nombre (la columna los corta)");
        String vio1 = "{Descripción Expediente=MICRO, Adjuntos (total)=2, Adjunto 1 (nombre)=-303157930-2-id..., Adjunto 2 (nombre)=-303157930-1-ju...}";
        ok(es.amagna.asistente.cerebro.Auditor.comprobarAdjuntos(vio1, 2, List.of("-303157930-1", "-303157930-2")) == null,
                "auditoría de adjuntos: lo que vio el auditor el 18/11 (otro nombre de campo, nombres cortados) cuadra");
        ok(es.amagna.asistente.cerebro.Auditor.comprobarAdjuntos("{Total Adjuntos=1, Nombre Adjunto=-695595127-1-id... (cortado)}", 1, List.of("-695595127-1")) == null,
                "auditoría de adjuntos: un adjunto cortado cuadra");
        ok(es.amagna.asistente.cerebro.Auditor.comprobarAdjuntos(vio1, 3, List.of("-303157930-1")) != null,
                "auditoría de adjuntos: si hay menos de los esperados, NO cuadra");
        ok(es.amagna.asistente.cerebro.Auditor.comprobarAdjuntos(vio1, 2, List.of("-303157930-1", "-999999999-1")) != null,
                "auditoría de adjuntos: si falta un adjunto esperado, NO cuadra");
        ok(es.amagna.asistente.cerebro.Auditor.comprobarAdjuntos("{Adjuntos (total)=12}", 2, List.of("")) != null,
                "auditoría de adjuntos: 12 no vale por 2");
        // La autorización: página 2 de una hoja de 3. Con otra cosa no se adivina.
        class Falso implements es.amagna.asistente.altas.DivisorPdf {
            final int paginas;
            Falso(int paginas) {
                this.paginas = paginas;
            }
            public int paginas(byte[] pdf) {
                return paginas;
            }
            public byte[] pagina(byte[] pdf, int numero) {
                return ("%PDF-pagina-" + numero).getBytes();
            }
        }
        byte[] pdfFalso = "%PDF-1.4 hoja".getBytes();
        var corte = es.amagna.asistente.altas.Autorizacion.separar(pdfFalso, new Falso(3));
        ok(corte.pdf() != null && new String(corte.pdf()).endsWith("pagina-2"), "autorización: de una hoja de 3 páginas se queda con la 2");
        var corte2 = es.amagna.asistente.altas.Autorizacion.separar(pdfFalso, new Falso(2));
        ok(corte2.pdf() == null && corte2.aviso().contains("2 páginas") && corte2.aviso().contains("no adivino"),
                "autorización: una hoja de 2 páginas NO se corta (no se adivina) y se avisa");
        var corte3 = es.amagna.asistente.altas.Autorizacion.separar("no soy un pdf".getBytes(), new Falso(3));
        ok(corte3.pdf() == null && corte3.aviso().contains("no es un PDF"), "autorización: un fichero que no es PDF no se corta");
        var corte4 = es.amagna.asistente.altas.Autorizacion.separar(pdfFalso, new es.amagna.asistente.altas.DivisorPdf() {
            public int paginas(byte[] pdf) throws java.io.IOException {
                throw new java.io.IOException("PDF cifrado");
            }
            public byte[] pagina(byte[] pdf, int numero) {
                return new byte[0];
            }
        });
        ok(corte4.pdf() == null && corte4.aviso().contains("PDF cifrado"), "autorización: si el PDF no se puede leer, no se adjunta nada y se dice por qué");
        ok(es.amagna.asistente.altas.Pedido.nombreSeguro("a:b*c?.pdf").equals("a_b_c_.pdf"), "pedidos: nombres de fichero que Windows acepta");
        Map<String, Object> sinNif = new java.util.LinkedHashMap<>(cli);
        sinNif.put("nif", "");
        var l2 = new es.amagna.asistente.altas.Lectura.Resultado(sinNif, exps, fich, List.of(), "");
        ok(es.amagna.asistente.altas.Pedido.de(l2, plan).pedidos().isEmpty(), "pedidos: sin NIF del cliente no se crea nada");
    }

    static void anclas() {
        var ventana = ventanaFalsa(800, 600, 200, 150, false);
        int cx = 235;
        int cy = 163;
        var recorte = es.amagna.asistente.manos.Ancla.recortar(ventana, cx, cy);
        int[] dentro = es.amagna.asistente.manos.Ancla.puntoEnRecorte(ventana, cx, cy);
        var igual = es.amagna.asistente.manos.Ancla.buscar(ventana, recorte, cx - dentro[0], cy - dentro[1]);
        ok(igual != null && igual.x() + dentro[0] == cx && igual.y() + dentro[1] == cy,
                "anclas: el mismo dibujo en el mismo sitio se encuentra exacto");
        var movida = ventanaFalsa(800, 600, 420, 330, false);
        var h = es.amagna.asistente.manos.Ancla.buscar(movida, recorte, cx - dentro[0], cy - dentro[1]);
        ok(h != null && h.x() + dentro[0] == cx + 220 && h.y() + dentro[1] == cy + 180,
                "anclas: si el botón se ha movido (lejos de lo esperado) se encuentra donde está ahora");
        var cerca = ventanaFalsa(800, 600, 215, 160, false);
        var hc = es.amagna.asistente.manos.Ancla.buscar(cerca, recorte, cx - dentro[0], cy - dentro[1]);
        ok(hc != null && hc.x() + dentro[0] == cx + 15 && hc.y() + dentro[1] == cy + 10,
                "anclas: un desplazamiento pequeño también se sigue");
        var doble = ventanaFalsa(800, 600, 200, 150, true);
        ok(es.amagna.asistente.manos.Ancla.buscar(doble, recorte, cx - dentro[0], cy - dentro[1]) == null,
                "anclas: dos botones iguales = ambiguo = no se fía (no adivina cuál)");
        var sin = ventanaFalsa(800, 600, 600, 450, false);
        var vacio = new java.awt.image.BufferedImage(800, 600, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ok(es.amagna.asistente.manos.Ancla.buscar(vacio, recorte, 0, 0) == null,
                "anclas: si el botón ya no está no se inventa un sitio");
        var liso = new java.awt.image.BufferedImage(60, 40, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ok(!es.amagna.asistente.manos.Ancla.hayContraste(liso) && es.amagna.asistente.manos.Ancla.buscar(ventana, liso, 0, 0) == null,
                "anclas: un recorte liso (sin dibujo) no sirve de ancla");
        try {
            var dir = java.nio.file.Files.createTempDirectory("anclas");
            var an = new es.amagna.asistente.manos.Anclas(dir);
            an.aprender("Expediente #/#", 357, 132, recorte);
            ok(an.leer("Expediente #/#", 357, 132) == null, "anclas: lo aprendido queda pendiente y todavía no vale");
            ok(an.confirmar() == 1 && an.leer("Expediente #/#", 357, 132) != null,
                    "anclas: al terminar bien la tarea, lo pendiente pasa a valer");
            an.aprender("Otra", 1, 2, recorte);
            an.descartar();
            an.confirmar();
            ok(an.leer("Otra", 1, 2) == null, "anclas: si la tarea falla, lo pendiente se olvida");
            an.olvidar("Expediente #/#", 357, 132);
            ok(an.leer("Expediente #/#", 357, 132) == null, "anclas: un ancla que ya no casa se puede olvidar para reaprenderla");
        } catch (java.io.IOException e) {
            ok(false, "anclas: " + e);
        }
    }

    static void auditor() {
        try {
            auditor2();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void auditor2() throws Exception {
        var esp = new java.util.LinkedHashMap<String, String>();
        esp.put("Descripción Expediente", "MICRO");
        esp.put("Etapa", "PEND. ENVIAR");
        var A = es.amagna.asistente.cerebro.Auditor.class;
        var bien = es.amagna.asistente.cerebro.Auditor.deJson(
                "{\"ok\": true, \"vio\": {\"Descripción Expediente\": \"MICRO\", \"Etapa\": \"PEND. ENVIAR\"}, \"diferencias\": [], \"no_se_ve\": [], \"otros\": []}", esp);
        ok(bien.ok(), "auditor: todo visto y coincide = OK");
        var mal = es.amagna.asistente.cerebro.Auditor.deJson(
                "{\"ok\": false, \"vio\": {\"Descripción Expediente\": \"MICR\", \"Etapa\": \"PEND. ENVIAR\"}, \"diferencias\": [\"Descripción Expediente: esperado «MICRO», se ve «MICR»\"], \"no_se_ve\": [], \"otros\": []}", esp);
        ok(!mal.ok() && mal.resumen().contains("MICR"), "auditor: una letra distinta no es OK y dice cuál");
        var mentira = es.amagna.asistente.cerebro.Auditor.deJson(
                "{\"ok\": true, \"vio\": {\"Descripción Expediente\": \"MICRO\"}, \"diferencias\": [], \"no_se_ve\": [], \"otros\": []}", esp);
        ok(!mentira.ok() && mentira.noSeVe().contains("Etapa"), "auditor: si dice «ok» pero no ha visto un campo esperado, NO vale");
        ok(!es.amagna.asistente.cerebro.Auditor.deJson("no sé", esp).ok()
                        && !es.amagna.asistente.cerebro.Auditor.deJson("{roto", esp).ok(),
                "auditor: un veredicto ilegible no es OK");
        var juezCodigo = es.amagna.asistente.cerebro.Auditor.deJson(
                "{\"ok\": false, \"vio\": {\"Descripción Expediente\": \"micro\", \"Etapa\": \" PEND.  ENVIAR\"}, \"diferencias\": [\"lo que sea\"], \"no_se_ve\": [], \"otros\": []}", esp);
        ok(juezCodigo.ok(), "auditor: el juez es el código: si lo transcrito coincide (mayúsculas, espacios) vale aunque el modelo diga otra cosa");
        var corta = new java.util.LinkedHashMap<String, String>();
        corta.put("Nombre", "hoja-encargo-prueba");
        ok(es.amagna.asistente.cerebro.Auditor.deJson("{\"vio\": {\"Nombre\": \"hoja-encargo-pru...\"}, \"no_se_ve\": [], \"otros\": []}", corta).ok()
                        && !es.amagna.asistente.cerebro.Auditor.deJson("{\"vio\": {\"Nombre\": \"otra-cosa...\"}, \"no_se_ve\": [], \"otros\": []}", corta).ok(),
                "auditor: un valor cortado por la pantalla («…») vale solo si es un principio del esperado");
        var vacio = new java.util.LinkedHashMap<String, String>();
        vacio.put("Núm. Clientes", "0");
        ok(es.amagna.asistente.cerebro.Auditor.deJson("{\"vio\": {\"Núm. Clientes\": \"0\"}, \"no_se_ve\": [], \"otros\": []}", vacio).ok()
                        && !es.amagna.asistente.cerebro.Auditor.deJson("{\"vio\": {\"Núm. Clientes\": \"1\"}, \"no_se_ve\": [], \"otros\": []}", vacio).ok(),
                "auditor: «Núm. Clientes» 0 vale y 1 no (un filtro activo o una lista vacía no son un aviso)");
        var raro = es.amagna.asistente.cerebro.Auditor.deJson(
                "{\"ok\": true, \"vio\": {\"Descripción Expediente\": \"MICRO\", \"Etapa\": \"PEND. ENVIAR\"}, \"diferencias\": [], \"no_se_ve\": [], \"otros\": [\"hay un aviso rojo encima\"]}", esp);
        ok(!raro.ok(), "auditor: algo raro en la ventana (un aviso) tampoco es OK");
        // diario
        java.nio.file.Path d = java.nio.file.Files.createTempFile("diario", ".tsv");
        java.nio.file.Files.delete(d);
        var diario = new es.amagna.asistente.estados.Diario(d);
        ok(!diario.yaHecha("73918462G|SOC|MICRO"), "diario: sin apuntar, no está hecha");
        diario.hecha("73918462G|SOC|MICRO", "crear-expediente", "36/2026");
        ok(diario.yaHecha("73918462G|SOC|MICRO") && !diario.yaHecha("73918462G|OTRA|MICRO") && !diario.yaHecha(""),
                "diario: una tarea hecha no se repite; otra clave distinta sí; sin clave no hay protección");
        java.nio.file.Files.deleteIfExists(d);
    }

    static void receta() {
        try {
            receta2();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void receta2() throws Exception {
        var R = es.amagna.asistente.cerebro.Receta.class;
        ok(es.amagna.asistente.cerebro.Receta.norm("Expediente 34/2026").equals(es.amagna.asistente.cerebro.Receta.norm("Expediente 35/2026"))
                        && !es.amagna.asistente.cerebro.Receta.norm("Clientes").equals(es.amagna.asistente.cerebro.Receta.norm("Contrarios")),
                "receta: «Expediente 34/2026» y «35/2026» son la misma ventana; Clientes y Contrarios no");
        var g = new es.amagna.asistente.cerebro.Receta.Grabadora();
        g.anadir(g.preparar("type", Json.obj("text", "Lucía\n"), null));
        g.anadir(g.preparar("screenshot", Json.obj(), null));
        g.anadir(g.preparar("pulsar_control", Json.obj("ventana", "Expediente 34/2026", "control", "boton:Guardar"), null));
        g.anadir(g.preparar("escribir_en", Json.obj("ventana", "Expediente 34/2026", "control", "campo junto a Descripción", "texto", "MICRO"), null));
        ok(g.cuantos() == 3, "receta: la captura no se graba, lo que cambia cosas sí (3 de 4)");
        var rec = g.aReceta("prueba", new java.util.LinkedHashMap<>(java.util.Map.of("cliente", "Lucía", "descripcion", "MICRO")));
        ok(rec.pasos().get(0).in().get("text").equals("{cliente}\n") && rec.pasos().get(2).in().get("texto").equals("{descripcion}"),
                "receta: los datos variables se guardan como {parámetros}");
        ok("Expediente #/#".equals(rec.pasos().get(1).ventana()), "receta: la ventana se guarda sin números");
        java.nio.file.Path f = java.nio.file.Files.createTempFile("receta", ".json");
        rec.guardar(f);
        var otra = es.amagna.asistente.cerebro.Receta.leer(f);
        java.nio.file.Files.deleteIfExists(f);
        ok(otra.pasos().size() == 3 && otra.pasos().get(0).accion().equals("type")
                        && otra.pasos().get(1).ventana().equals("Expediente #/#")
                        && otra.pasos().get(2).in().get("texto").equals("{descripcion}"),
                "receta: se guarda y se lee igual");
        ok(es.amagna.asistente.cerebro.Receta.sustituir("{cliente} y {ref}", java.util.Map.of("cliente", "ANA", "ref", "X (AND)")).equals("ANA y X (AND)"),
                "receta: se rellenan los parámetros al reproducir");
        ok(es.amagna.asistente.cerebro.Agente.pareceFallo("No se ha podido pulsar") && es.amagna.asistente.cerebro.Agente.pareceFallo("Error: x")
                        && !es.amagna.asistente.cerebro.Agente.pareceFallo("Pulsado boton «Guardar»."),
                "receta: una respuesta «no se ha podido…» no se graba como paso bueno");
    }

    static void plan() {
        ok(es.amagna.asistente.altas.Plan.refPropia("ANDREA EXPEDIENTES 31 AGOSTO.zip").equals("(AND)")
                        && es.amagna.asistente.altas.Plan.refPropia("AINHOA_GRANDE_31_5SEPT.zip").equals("(AIN)")
                        && es.amagna.asistente.altas.Plan.refPropia("DAVID CHAMORRO-Semana 31.zip").equals("(CHA)")
                        && es.amagna.asistente.altas.Plan.refPropia("otro.zip").isEmpty(),
                "Ref. Propia sale de quién manda el bloque");
        ok(es.amagna.asistente.altas.Plan.descripcionValida(" revolving (t)  + diligencias ").equals("REVOLVING (T) + DILIGENCIAS")
                        && es.amagna.asistente.altas.Plan.descripcionValida("LINEA").isEmpty()
                        && es.amagna.asistente.altas.Plan.descripcionValida(null).isEmpty(),
                "solo valen las cuatro descripciones; «LINEA» no se inventa");
        Map<String, Object> cli = new java.util.LinkedHashMap<>();
        cli.put("nombre", "ANA");
        List<Object> fich = new java.util.ArrayList<>(List.of(
                Json.obj("ruta", "c/dni.pdf", "que_es", "identidad"),
                Json.obj("ruta", "c/notas.txt", "que_es", "notas"),
                Json.obj("ruta", "c/just.jpg", "que_es", "justificante"),
                Json.obj("ruta", "c/hoja.pdf", "que_es", "hoja_encargo"),
                Json.obj("ruta", "c/A/c1.pdf", "que_es", "contrato")));
        List<Object> exps = new java.util.ArrayList<>(List.of(
                Json.obj("entidad", "ARENA FINANCE SPAIN, S.L.", "marca", "Smartcrédito", "descripcion", "MICRO",
                        "documentos", List.of("c/A/c1.pdf")),
                Json.obj("entidad", "Arena Finance Spain S.L.", "descripcion", "MICRO", "documentos", List.of("c/A/c2.pdf")),
                Json.obj("entidad", "CAIXABANK PAYMENTS", "descripcion", "REVOLVING (T)", "documentos", List.of("c/B/t1.pdf")),
                Json.obj("entidad", "CAIXABANK PAYMENTS", "descripcion", "REVOLVING (T)", "documentos", List.of("c/B/t2.pdf")),
                Json.obj("entidad", "DINEO", "descripcion", "LINEA", "documentos", List.of("c/D/d.pdf"))));
        var p = es.amagna.asistente.altas.Plan.de(new es.amagna.asistente.altas.Lectura.Resultado(cli, exps, fich,
                new java.util.ArrayList<>(), ""), "ANDREA 7-14.zip");
        ok(p.expedientes().size() == 4, "dos marcas de la misma sociedad = un expediente; dos tarjetas revolving = dos");
        var arena = p.expedientes().get(0);
        ok(arena.contratos().equals(List.of("c/A/c1.pdf", "c/A/c2.pdf")) && arena.marca().equals("Smartcrédito")
                        && arena.refPropia().equals("(AND)"),
                "los dos contratos de la sociedad van en el mismo expediente, con su Ref. Propia");
        ok(arena.adjuntos().containsAll(List.of("c/dni.pdf", "c/notas.txt", "c/hoja.pdf", "c/A/c1.pdf", "c/A/c2.pdf"))
                        && !arena.adjuntos().contains("c/just.jpg"),
                "al expediente: contratos, DNI, notas y autorización; el justificante solo a la ficha");
        ok(p.adjuntosFicha().equals(List.of("c/dni.pdf", "c/notas.txt", "c/just.jpg", "c/hoja.pdf")),
                "a la ficha: identidad, notas, justificante y hoja de encargo");
        ok(p.expedientes().get(3).descripcion().isEmpty() && !p.expedientes().get(3).avisos().isEmpty(),
                "una descripción inventada se queda vacía y avisa");
    }

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

    /**
     * El alta no abre MN si la lectura está parada o le falta un dato. El encaje de la ficha se prueba con un árbol
     * de mentira: aquí no hay programa.
     */
    static void alta() {
        String listo = """
                {"cliente": {"nombre": "Angela", "apellidos": "Garcia Prueba", "nif": "00000000T",
                             "telefono": "600112233", "correo": "", "domicilio": "", "fecha_nacimiento": ""},
                 "expedientes": [], "ficheros": [], "dudas": []}
                """;
        Alta.Decision sigue = Alta.deTexto(listo);
        ok(sigue.seguir() && sigue.lectura().cliente().get("nif").equals("00000000T"),
                "con nombre, apellidos y NIF, y sin dudas, el alta puede seguir");

        Alta.Decision duda = Alta.deTexto("""
                {"cliente": {"nombre": "Angela", "apellidos": "Garcia", "nif": "00000000T"},
                 "expedientes": [], "ficheros": [], "dudas": ["La letra del DNI no cuadra"]}
                """);
        ok(!duda.seguir() && duda.mensaje().startsWith("PARADO") && duda.mensaje().contains("no cuadra"),
                "una duda para el alta: no se abre MN");

        Alta.Decision sinNif = Alta.deTexto("""
                {"cliente": {"nombre": "Angela", "apellidos": "Garcia", "nif": ""},
                 "expedientes": [], "ficheros": [], "dudas": []}
                """);
        ok(!sinNif.seguir() && sinNif.mensaje().contains("nif"), "sin NIF no se sigue");
        ok(!Alta.deTexto("""
                {"cliente": {"nombre": "", "apellidos": "Garcia", "nif": "00000000T"}, "dudas": []}
                """).seguir(), "sin nombre no se sigue");
        ok(!Alta.deTexto("""
                {"cliente": {"nombre": "Angela", "apellidos": " ", "nif": "00000000T"}, "dudas": []}
                """).seguir(), "sin apellidos no se sigue");
        ok(!Alta.deTexto("PARADO: lo tiene que mirar una persona\n"
                + "{\"cliente\":{\"nombre\":\"Angela\",\"apellidos\":\"Garcia\",\"nif\":\"00000000T\"},\"dudas\":[]}").seguir(),
                "si la lectura dice PARADO, no se sigue aunque el JSON traiga datos");
        ok(!Alta.deTexto("""
                CLIENTE
                  nombre: Angela
                  apellidos: Garcia
                  nif: 00000000T

                NO SE SIGUE. Esto lo tiene que mirar una persona:
                  · falta una firma
                """).seguir(), "un informe que dice NO SE SIGUE no se sigue");
        Alta.Decision informe = Alta.deTexto("""
                Bloque «garcia-prueba.zip» · 4 documentos

                CLIENTE
                  nombre: Angela
                  apellidos: Garcia Prueba
                  nif: 00000000T

                EXPEDIENTES (0)

                LISTO: están los datos imprescindibles y no hay dudas.
                """);
        ok(informe.seguir() && informe.lectura().cliente().get("apellidos").equals("Garcia Prueba"),
                "el informe LISTO, con los tres datos, también vale");
        ok(!Alta.deTexto("""
                CLIENTE
                  nombre: Angela
                  apellidos: Garcia
                  nif: (no consta)

                LISTO: están los datos imprescindibles y no hay dudas.
                """).seguir(), "si el informe dice LISTO pero el NIF no consta, no se sigue");
        ok(!Alta.deTexto("").seguir() && !Alta.deTexto("no es una lectura").seguir(),
                "una lectura vacía o ilegible no se sigue");

        ok(Alta.rutaClientes("Bases de Datos > Clientes").equals(List.of("Bases de Datos", "Clientes")),
                "Clientes se abre por la ruta del mapa");
        ok(Alta.rutaClientes("Contabilidad > Cuentas").isEmpty() && Alta.rutaClientes("").isEmpty(),
                "una ruta de Contabilidad, o una vacía, no se abre");

        String ficha = """
                Controles de «Cliente»:
                - campo «NIF» (#txtNif) [etiqueta: NIF] = «»
                - campo «Nombre» (#txtNombre) [etiqueta: Nombre] = «»
                - campo «Apellidos» (#txtApellidos) [etiqueta: Apellidos] = «»
                - campo «Teléfono» (#txtTelefono) [etiqueta: Teléfono] = «»
                - boton «Guardar» (#btnGuardar)
                - boton «Alta» (#btnAlta)
                - boton «Baja» (#btnBaja)
                """;
        List<Alta.ControlFicha> controles = Alta.leerControles(ficha);
        Alta.Plano plano = Alta.encajar(sigue.lectura(), controles);
        ok(plano.listoParaRellenar()
                        && plano.de("nif").control().apodo().equals("#txtNif")
                        && plano.de("nombre").control().apodo().equals("#txtNombre")
                        && plano.de("apellidos").control().apodo().equals("#txtApellidos")
                        && plano.de("telefono").control().apodo().equals("#txtTelefono"),
                "cada dato encaja con un solo campo, por su etiqueta");
        ok(Alta.botonDeCrear(controles, "Clientes") != null
                        && Alta.botonDeCrear(controles, "Clientes").apodo().equals("#btnAlta")
                        && Alta.botonDeCrear(controles, "Selección de Empresa") == null
                        && Alta.botonDeCrear(controles, "Selección de Ejercicio Contable") == null,
                "el botón de alta es el de Clientes; en el diálogo de empresa no se pulsa");
        Alta.ControlFicha guardar = Alta.botonDeGuardar(controles, "Cliente");
        ok(guardar != null && guardar.apodo().equals("#btnGuardar")
                        && Alta.botonDeGuardar(controles, "Selección de Empresa") == null,
                "Guardar se ve en la ficha y no en el diálogo de empresa");
        ok(Alta.motivoParaNoGuardar("MNprogram - Empresa de prueba", "Cliente", "00000000T", "00000000T", guardar) == null,
                "se puede guardar si el título sigue en Empresa de prueba y el NIF coincide");
        ok(Alta.motivoParaNoGuardar("MNprogram - Empresa de prueba", "Cliente", "11111111H", "00000000T", guardar) != null,
                "si el NIF de la pantalla no es el de la lectura, no se guarda");
        ok(Alta.motivoParaNoGuardar("MNprogram", "Cliente", "00000000T", "00000000T", guardar) != null,
                "si el título ya no dice Empresa de prueba, no se guarda");
        ok(Alta.motivoParaNoGuardar("MNprogram - Mi Empresa", "Cliente", "00000000T", "00000000T", guardar) != null,
                "en una empresa real no se guarda");

        String principal = Alta.elegirPrincipal(List.of(
                new Alta.VentanaVista("Clientes", true, true),
                new Alta.VentanaVista("Alta Cliente", true, true),
                new Alta.VentanaVista("Teléfono Móvil", true, true),
                new Alta.VentanaVista("MNprogram - Abogados - Empresa de prueba [\\Usu1] Año 2026", true, false)));
        ok(principal != null && principal.contains("Empresa de prueba") && Alta.motivoCandado(principal, "Clientes") == null,
                "un hijo «Clientes» no tumba el candado si la principal dice Empresa de prueba");
        ok(Alta.empresaSigueBien(null, "Clientes", principal)
                        && !Alta.empresaSigueBien("MNprogram - Mi Empresa", "Clientes", principal),
                "si solo se ve el hijo se mantiene la principal; si pasa a otra empresa, se para");
        ok(!Alta.debePulsarAlta("00000000T", "328 ANGELA GARCIA PRUEBA 00000000T 600 11 22 33", false)
                        && Alta.debePulsarAlta("12345678Z", "328 ANGELA GARCIA PRUEBA 00000000T", false)
                        && !Alta.debePulsarAlta("12345678Z", "328 ANGELA GARCIA PRUEBA 00000000T", true),
                "si el NIF ya está en el listado, o la ficha ya está abierta, no se pulsa Alta");
        ok("Salir".equals(Alta.botonDeCierre("Direcciones de Correo Electrónico",
                        List.of("Alta", "Baja", "Modificar", "Salir"), true, true))
                        && "Guardar".equals(Alta.botonDeCierre("Dirección Correo Electrónico",
                        List.of("Guardar", "Cancelar"), true, false))
                        && "Aceptar".equals(Alta.botonDeCierre("Teléfono Móvil",
                        List.of("Aceptar", "Cancelar"), true, false))
                        && "Cancelar".equals(Alta.botonDeCierre("Teléfono Móvil",
                        List.of("Cancelar"), true, true))
                        && Alta.botonDeCierre("Teléfono Móvil", List.of("Cancelar"), true, false) == null
                        && "Cancelar".equals(Alta.botonDeCierre("Dirección Correo Electrónico",
                        List.of("Guardar", "Cancelar"), false, true)),
                "el teléfono y el correo se quedan con Aceptar o Guardar; la lista sale con Salir, nunca con Baja");
        Alta.PasoDialogo listaCorreo = Alta.decidirDialogo("Direcciones de Correo Electrónico", "No hay datos",
                List.of("Alta", "Baja", "Modificar", "Salir"));
        ok("confirmar".equals(listaCorreo.accion()) && "Salir".equals(listaCorreo.boton()),
                "la lista de correos, aunque tenga Baja, se cierra con Salir");
        java.util.List<Alta.PasoCierre> pila = Alta.secuenciaDeCierre(java.util.List.of(
                "Alta Cliente", "Teléfono Móvil", "Direcciones de Correo Electrónico", "Dirección Correo Electrónico"),
                false);
        ok(pila.size() == 4
                        && "Dirección Correo Electrónico".equals(pila.get(0).dialogo()) && "Guardar".equals(pila.get(0).boton())
                        && "Direcciones de Correo Electrónico".equals(pila.get(1).dialogo()) && "Salir".equals(pila.get(1).boton())
                        && "Teléfono Móvil".equals(pila.get(2).dialogo()) && "Guardar".equals(pila.get(2).boton())
                        && "Alta Cliente".equals(pila.get(3).dialogo()) && "Guardar".equals(pila.get(3).boton())
                        && pila.stream().noneMatch(p -> "Baja".equals(p.boton()) || "Modificar".equals(p.boton()))
                        && pila.stream().filter(p -> "Alta Cliente".equals(p.dialogo())).count() == 1,
                "el cierre es correo-Guardar, lista-Salir (no Baja), teléfono y un solo Guardar de la ficha");
        ok(Alta.ordenDeCierre("Dirección Correo Electrónico") < Alta.ordenDeCierre("Direcciones de Correo Electrónico")
                        && Alta.ordenDeCierre("Direcciones de Correo Electrónico") < Alta.ordenDeCierre("Teléfono Móvil")
                        && Alta.ordenDeCierre("Teléfono Móvil") < Alta.ordenDeCierre("Alta Cliente"),
                "el diálogo de delante es el correo suelto, luego la lista, luego el teléfono, luego la ficha");
        java.util.List<Alta.PasoCierre> telYa = Alta.secuenciaDeCierre(java.util.List.of("Teléfono Móvil", "Alta Cliente"), true);
        ok(telYa.size() == 2 && "Cancelar".equals(telYa.get(0).boton()) && "Guardar".equals(telYa.get(1).boton()),
                "si el móvil ya está en la ficha, ese diálogo se cierra con Cancelar");
        ok("Salir".equals(Alta.botonConocido("Direcciones de Correo Electrónico", true, true))
                        && "Guardar".equals(Alta.botonConocido("Dirección Correo Electrónico", true, true))
                        && !"Baja".equals(Alta.botonConocido("Direcciones de Correo Electrónico", true, false)),
                "el botón se decide por el diálogo, sin leer el texto Win32 del botón");
        ok(!Alta.sePuedeGuardarLaFicha(java.util.List.of("Dirección Correo Electrónico", "Alta Cliente"))
                        && !Alta.sePuedeGuardarLaFicha(java.util.List.of("Teléfono Móvil"))
                        && Alta.sePuedeGuardarLaFicha(java.util.List.of("Alta Cliente")),
                "no se pulsa el Guardar de la ficha mientras el correo o el teléfono siguen delante");
        int[] guardarCorreo = Alta.zonaBoton("Dirección Correo Electrónico", "Guardar", 0, 0, 400, 280);
        int[] cancelarCorreo = Alta.zonaBoton("Dirección Correo Electrónico", "Cancelar", 0, 0, 400, 280);
        int[] salirLista = Alta.zonaBoton("Direcciones de Correo Electrónico", "Salir", 0, 0, 520, 320);
        int[] guardarFicha = Alta.zonaBoton("Alta Cliente", "Guardar", 0, 0, 900, 640);
        int[] guardarMovil = Alta.zonaBoton("Teléfono Móvil", "Guardar", 0, 0, 360, 220);
        ok(guardarCorreo[0] < cancelarCorreo[0] && guardarCorreo[0] < 200
                        && salirLista[0] > 390
                        && guardarFicha[0] < 120 && guardarFicha[1] > 560
                        && guardarMovil[0] < 180,
                "si UIA no contesta, Guardar es el botón de la izquierda y Salir el de la derecha");
        ok(!Alta.altaQuedoGuardada(true, true) && !Alta.altaQuedoGuardada(true, false)
                        && !Alta.altaQuedoGuardada(false, false) && Alta.altaQuedoGuardada(false, true),
                "una ficha que sigue abierta no es un alta guardada; hace falta que se haya ido y el NIF esté en la rejilla");
        ok(Alta.zonaAlMomento("Teléfono Móvil", "Guardar")
                        && Alta.zonaAlMomento("Dirección Correo Electrónico", "Guardar")
                        && Alta.zonaAlMomento("Direcciones de Correo Electrónico", "Salir")
                        && Alta.zonaAlMomento("Alta Cliente", "Guardar")
                        && !Alta.zonaAlMomento("Direcciones de Correo Electrónico", "Baja")
                        && !Alta.zonaAlMomento("Teléfono Móvil", "Cancelar")
                        && !Alta.zonaAlMomento("Alta Cliente", "Cancelar"),
                "la pila conocida se pulsa por zona al momento, sin esperar 4 s a que UIA conteste");
        String filaLucia = "otro «Fila 330» = «330;LUCIA FERNANDEZ PRUEBA;73918462-G;611 22 33 44»";
        ok(Alta.nifYaListado("73918462G", filaLucia)
                        && Alta.nifYaListado("73918462G", "7391 8462 G")
                        && Alta.nifYaListado("73918462G", "73.918.462 G")
                        && "presente".equals(Alta.veredictoNif("73918462G", "Controles de «Clientes»:\n- " + filaLucia))
                        && "ausente".equals(Alta.veredictoNif("73918462G",
                                "Controles de «Clientes»:\n- otro «Fila 328» = «328;ANGELA GARCIA PRUEBA;00000000T»"))
                        && "sin-leer".equals(Alta.veredictoNif("73918462G", null))
                        && "sin-leer".equals(Alta.veredictoNif("73918462G", "No se pueden leer los controles"))
                        && "sin-leer".equals(Alta.veredictoNif("73918462G",
                                "Controles de «Clientes»:\n… (hay más; busca uno concreto por su nombre)")),
                "73918462G está en la rejilla aunque el volcado lleve espacios o guiones; una lectura cortada no es ausencia");
        ok("guardado".equals(Alta.cierreTrasRejilla(false, java.util.List.of("sin-leer", "presente")))
                        && "parada".equals(Alta.cierreTrasRejilla(true, java.util.List.of("presente")))
                        && "sin-leer".equals(Alta.cierreTrasRejilla(false, java.util.List.of("sin-leer", "sin-leer")))
                        && "ausente".equals(Alta.cierreTrasRejilla(false, java.util.List.of("sin-leer", "ausente")))
                        && Alta.fraseDeGuardado("MNprogram - Empresa de prueba").equals(
                                "Guardado. La ficha «Alta Cliente» se ha cerrado. Sesión: «MNprogram - Empresa de prueba»."),
                "si la ficha se cierra y el NIF aparece en la rejilla, la línea es de guardado; si sigue abierta, parada");

        String ambiguo = """
                Controles de «Cliente»:
                - campo «NIF» (#txtNif) = «»
                - campo «DNI» (#txtDni) = «»
                - campo «Nombre» (#txtNombre) = «»
                - campo «Apellidos» (#txtApellidos) = «»
                - boton «Alta» (#btnAlta)
                - boton «Crear» (#btnCrear)
                - boton «Guardar» (#btnGuardar)
                - boton «Aceptar» (#btnAceptar)
                """;
        List<Alta.ControlFicha> dos = Alta.leerControles(ambiguo);
        Alta.Plano dudoso = Alta.encajar(sigue.lectura(), dos);
        ok(!dudoso.listoParaRellenar() && dudoso.inciertos().stream().anyMatch(s -> s.startsWith("nif")),
                "si el NIF encaja con dos campos, no se rellena");
        ok(Alta.botonDeCrear(dos, "Clientes") == null, "si hay Alta y Crear, no se pulsa ninguno");
        ok(Alta.botonDeGuardar(dos, "Cliente") == null, "si hay Guardar y Aceptar, no se pulsa ninguno");

        String partidos = """
                Controles de «Cliente»:
                - campo «NIF» (#txtNif) = «»
                - campo «Nombre» (#txtNombre) = «»
                - campo «Primer apellido» (#txtApellido1) = «»
                - campo «Segundo apellido» (#txtApellido2) = «»
                """;
        ok(!Alta.encajar(sigue.lectura(), Alta.leerControles(partidos)).listoParaRellenar(),
                "si los apellidos van en dos campos, no se parte la lectura a ojo");

        String apagado = """
                Controles de «Cliente»:
                - campo «NIF» (#txtNif) = «» [desactivado]
                - campo «Nombre» (#txtNombre) = «»
                - campo «Apellidos» (#txtApellidos) = «»
                """;
        ok(!Alta.encajar(sigue.lectura(), Alta.leerControles(apagado)).listoParaRellenar(),
                "un NIF desactivado no se rellena");

        Alta.Decision garcia = Alta.deTexto("""
                {"cliente": {"nombre": "ANGELA", "apellidos": "GARCIA PRUEBA", "nif": "00000000T",
                             "telefono": "600 11 22 33",
                             "correo": "angela.garcia.prueba@ejemplo-invalido.test",
                             "domicilio": "Calle Inventada 15, 3 B, 15001 A Coruna, A Coruna"},
                 "expedientes": [], "ficheros": [], "dudas": []}
                """);
        String fichaUna = """
                Controles de «Alta Cliente»:
                - campo «» [etiqueta: Nombre] = «»
                - campo «» [etiqueta: NIF/CIF] = «»
                - campo «» [etiqueta: Teléfono] = «»
                - campo «» [etiqueta: Móvil] = «»
                - campo «» [etiqueta: Correo Electrónico] = «»
                - campo «» [etiqueta: Dirección] = «»
                - campo «» [etiqueta: Código Postal] = «»
                - campo «» [etiqueta: Población] = «»
                - campo «» [etiqueta: Número] = «328»
                - desplegable «» [etiqueta: Tipo Cliente] = «General»
                - desplegable «» [etiqueta: Provincia] = «»
                - boton «Guardar»
                """;
        Alta.Plano una = Alta.encajar(garcia.lectura(), Alta.leerControles(fichaUna));
        ok(una.listoParaRellenar() && una.de("apellidos") == null
                        && "ANGELA GARCIA PRUEBA".equals(Alta.textoPara(garcia.lectura(), "nombre", una))
                        && "Móvil".equals(una.de("telefono").control().etiqueta())
                        && "00000000T".equals(Alta.textoPara(garcia.lectura(), "nif", una))
                        && "Calle Inventada 15, 3 B".equals(Alta.textoPara(garcia.lectura(), "domicilio", una))
                        && "15001".equals(Alta.textoPara(garcia.lectura(), "codigo_postal", una))
                        && "A Coruna".equals(Alta.textoPara(garcia.lectura(), "poblacion", una))
                        && una.encajes().stream().noneMatch(e -> "Número".equals(e.control().etiqueta())),
                "en Alta Cliente el nombre va junto, el móvil gana al teléfono y la dirección se parte");
        ok("Angela".equals(Alta.textoPara(sigue.lectura(), "nombre", plano)),
                "si hay campo de apellidos, el nombre no se junta");

        Alta.PasoDialogo confirmar = Alta.decidirDialogo("Confirmación",
                "¿Desea guardar los datos del cliente?", List.of("Sí", "No"));
        ok("confirmar".equals(confirmar.accion()) && "Sí".equals(confirmar.boton()) && !confirmar.yaGuardado(),
                "una pregunta de guardar se contesta con Sí");
        Alta.PasoDialogo hecho = Alta.decidirDialogo("Información",
                "El cliente se ha guardado correctamente.", List.of("Aceptar"));
        ok("confirmar".equals(hecho.accion()) && hecho.yaGuardado() && "Aceptar".equals(hecho.boton()),
                "«se ha guardado» es la confirmación, y se cierra con Aceptar");
        Alta.PasoDialogo provincia = Alta.decidirDialogo("Aviso",
                "El campo Provincia es obligatorio.", List.of("Aceptar"));
        ok("error".equals(provincia.accion()) && provincia.campo().isEmpty(),
                "si falta la Provincia no se inventa");
        Alta.PasoDialogo nif = Alta.decidirDialogo("Aviso",
                "Debe introducir el NIF/CIF.", List.of("Aceptar"));
        ok("error".equals(nif.accion()) && "nif".equals(nif.campo()) && "Aceptar".equals(nif.boton()),
                "si falta el NIF, que ya sabemos, se señala ese campo");
        Alta.PasoDialogo baja = Alta.decidirDialogo("Clientes",
                "¿Dar de baja al cliente?", List.of("Sí", "No"));
        ok("peligro".equals(baja.accion()) && baja.boton().isEmpty(),
                "dar de baja no se pulsa, aunque haya un Sí");
        Alta.PasoDialogo modificar = Alta.decidirDialogo("Empresa",
                "Modificar empresa", List.of("Aceptar", "Cancelar"));
        ok("peligro".equals(modificar.accion()) && modificar.boton().isEmpty(),
                "Modificar empresa no se pulsa");
        Alta.PasoDialogo raro = Alta.decidirDialogo("Atención",
                "No se ha podido completar la operación.", List.of("Aceptar"));
        ok("no-claro".equals(raro.accion()) && raro.boton().isEmpty(),
                "un aviso que no se entiende no se pulsa");
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
