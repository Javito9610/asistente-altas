package es.amagna.asistente;

import es.amagna.asistente.cerebro.Agente;
import es.amagna.asistente.cerebro.Cerebro;
import es.amagna.asistente.manos.Escala;
import es.amagna.asistente.manos.Manos;
import es.amagna.asistente.manos.Teclas;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        ok(tools.size() == 8 && Agente.HERRAMIENTA.equals(((Map<String, Object>) tools.get(7)).get("type")), "con ventanas: 7 herramientas propias + la de ordenador");
        String tras = Json.escribir(conV.get(3));
        ok(tras.contains("DISTINTO en el carácter 39") && tras.contains("Editor de texto"), "leer_campo lee el control con el foco y detecta el punto que falta");
        ok(tras.contains("diálogos abiertos dentro: «Abrir»"), "ventanas_abiertas enseña los diálogos que la captura no ve");
        ok(Agente.comparar("Hola, soy el Asistente.", "Hola, soy el Asistente.").startsWith("IGUAL"), "comparar: igual");
        String d = Agente.comparar("Hola, soy el Asistente.", "Hola, soy el Asistente");
        ok(d.startsWith("DISTINTO en el carácter 23") && d.contains("se esperaba «.» y hay (fin)"), "comparar: le falta el punto final → DISTINTO y dónde");
        ok(Agente.comparar("línea 1\nlínea 2", "línea 1\r\nlínea 2").startsWith("IGUAL") && Agente.comparar("a\nb", "a\rb").startsWith("IGUAL"), "comparar: saltos de línea de Windows cuentan igual");
        ok(Agente.comparar("PEREZ", "PÉREZ").startsWith("DISTINTO en el carácter 2"), "comparar: un acento de más → DISTINTO");
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
