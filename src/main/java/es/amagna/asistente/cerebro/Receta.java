package es.amagna.asistente.cerebro;

import es.amagna.asistente.Json;
import es.amagna.asistente.manos.Manos;
import es.amagna.asistente.manos.Ventanas;
import es.amagna.asistente.manos.Win32;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Una receta: lo que el agente hizo la primera vez, guardado para que la próxima la reproduzca el asistente SIN IA.
 *
 * - Solo se graban las acciones que cambian cosas (clics, teclas, texto, UIA que pulsa o escribe); las capturas y
 *   las lecturas no.
 * - Los clics van **relativos a la ventana en la que se hicieron** (y con su título, con los números cambiados por #),
 *   no a la pantalla: la receta sigue valiendo aunque la ventana salga en otro sitio.
 * - Los datos variables (cliente, descripción…) se guardan como {parámetros}.
 * - Al reproducir, cada paso tiene su tiempo máximo. Si un paso falla, se para y se dice cuál: ahí entra la IA.
 */
public final class Receta {
    /** Lo que se graba. Lo demás (capturas, lecturas) no cambia nada y no hace falta repetirlo. */
    static final Set<String> SE_GRABA = Set.of("left_click", "double_click", "right_click", "triple_click", "type", "key",
            "scroll", "wait", "pulsar_control", "escribir_en", "esperar_a", "activar_ventana", "cerrar_ventana", "abrir_programa");
    static final Set<String> CON_CLIC = Set.of("left_click", "double_click", "right_click", "triple_click");
    private static final Pattern TITULO = Pattern.compile("«([^»]+)»");

    public record Paso(String accion, Map<String, Object> in, String ventana, int[] rel) {
    }

    public record Resultado(boolean bien, int pasosHechos, int pasosTotal, String mensaje) {
    }

    private final String nombre;
    private final String tarea;
    private final List<Paso> pasos;

    public Receta(String nombre, String tarea, List<Paso> pasos) {
        this.nombre = nombre;
        this.tarea = tarea == null ? "" : tarea;
        this.pasos = pasos;
    }

    public Receta(String nombre, List<Paso> pasos) {
        this(nombre, "", pasos);
    }

    /** Lo que había que hacer, con {parámetros}: es lo que se le cuenta a la IA cuando hay que rematar. */
    public String tarea() {
        return tarea;
    }

    /** Solo los n primeros pasos. */
    public List<Paso> primeros(int n) {
        return new ArrayList<>(pasos.subList(0, Math.max(0, Math.min(n, pasos.size()))));
    }

    public String nombre() {
        return nombre;
    }

    public List<Paso> pasos() {
        return pasos;
    }

    /** Los números de un título pasan a «#»: «Expediente 34/2026» y «Expediente 35/2026» son la misma ventana. */
    public static String norm(String titulo) {
        return titulo == null ? "" : titulo.replaceAll("\\d+", "#").strip();
    }

    // ---------- grabar ----------

    /** Acompaña al agente y va apuntando. */
    public static final class Grabadora {
        private final List<Paso> pasos = new ArrayList<>();

        /** ANTES de ejecutar (después la ventana puede haber cambiado). Devuelve null si esa acción no se graba. */
        @SuppressWarnings("unchecked")
        public Paso preparar(String accion, Map<String, Object> in, Manos manos) {
            if (!SE_GRABA.contains(accion)) {
                return null;
            }
            Map<String, Object> copia = new LinkedHashMap<>(in);
            String ventana = null;
            int[] rel = null;
            if (CON_CLIC.contains(accion) || "scroll".equals(accion)) {
                try {
                    var h = Win32.User32.I.GetForegroundWindow();
                    int[] r = h == null ? null : Win32.rectangulo(h);
                    if (r != null && in.get("coordinate") instanceof List<?> c) {
                        int[] o = manos.escala().aImagen(r[0], r[1]);
                        ventana = norm(Win32.titulo(h));
                        rel = new int[] {(int) Math.round(((Number) c.get(0)).doubleValue()) - o[0],
                                (int) Math.round(((Number) c.get(1)).doubleValue()) - o[1]};
                    }
                } catch (RuntimeException ignorada) {
                    // sin ventana, se graba con coordenadas de pantalla
                }
            } else if (in.get("ventana") != null) {
                ventana = norm(String.valueOf(in.get("ventana")));
            } else if (in.get("titulo") != null) {
                ventana = norm(String.valueOf(in.get("titulo")));
            }
            return new Paso(accion, copia, ventana, rel);
        }

        public void anadir(Paso p) {
            if (p != null) {
                pasos.add(p);
            }
        }

        public int cuantos() {
            return pasos.size();
        }

        public List<Paso> pasosGrabados() {
            return List.copyOf(pasos);
        }

        /** Guarda la receta. params: nombre → valor que tenía esta vez (se sustituye por {nombre} donde aparezca). */
        public Receta aReceta(String nombre, Map<String, String> params) {
            return aReceta(nombre, "", params);
        }

        public Receta aReceta(String nombre, String tarea, Map<String, String> params) {
            List<Paso> fin = new ArrayList<>();
            List<Map.Entry<String, String>> orden = new ArrayList<>(params.entrySet());
            orden.sort((a, b) -> b.getValue().length() - a.getValue().length());
            for (Paso p : pasos) {
                Map<String, Object> in = new LinkedHashMap<>(p.in());
                for (String campo : List.of("text", "texto")) {
                    if (in.get(campo) instanceof String s) {
                        for (var e : orden) {
                            if (!e.getValue().isBlank()) {
                                s = s.replace(e.getValue(), "{" + e.getKey() + "}");
                            }
                        }
                        in.put(campo, s);
                    }
                }
                fin.add(new Paso(p.accion(), in, p.ventana(), p.rel()));
            }
            String t = tarea == null ? "" : tarea;
            for (var e : orden) {
                if (!e.getValue().isBlank()) {
                    t = t.replace(e.getValue(), "{" + e.getKey() + "}");
                }
            }
            return new Receta(nombre, t, fin);
        }
    }

    public void guardar(Path fichero) throws IOException {
        Files.createDirectories(fichero.toAbsolutePath().getParent());
        List<Object> lista = new ArrayList<>();
        for (Paso p : pasos) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accion", p.accion());
            m.put("in", p.in());
            if (p.ventana() != null) {
                m.put("ventana", p.ventana());
            }
            if (p.rel() != null) {
                m.put("rel", List.of((long) p.rel()[0], (long) p.rel()[1]));
            }
            lista.add(m);
        }
        Files.writeString(fichero, Json.escribir(Json.obj("nombre", nombre, "tarea", tarea, "pasos", lista)), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static Receta leer(Path fichero) throws IOException {
        Map<String, Object> raiz = Json.objeto(Files.readString(fichero, StandardCharsets.UTF_8));
        List<Paso> pasos = new ArrayList<>();
        for (Object o : (List<Object>) raiz.getOrDefault("pasos", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            int[] rel = null;
            if (m.get("rel") instanceof List<?> l) {
                rel = new int[] {((Number) l.get(0)).intValue(), ((Number) l.get(1)).intValue()};
            }
            pasos.add(new Paso((String) m.get("accion"), new LinkedHashMap<>((Map<String, Object>) m.get("in")),
                    (String) m.get("ventana"), rel));
        }
        return new Receta(String.valueOf(raiz.getOrDefault("nombre", "receta")), String.valueOf(raiz.getOrDefault("tarea", "")), pasos);
    }

    // ---------- reproducir ----------

    /** El título real, ahora, de la ventana que se parece a la grabada (mismo título con otros números). */
    public static String tituloReal(Ventanas ventanas, String normalizado) {
        if (normalizado == null || normalizado.isBlank()) {
            return null;
        }
        String aproximado = null;
        Matcher m = TITULO.matcher(ventanas.abiertas());
        while (m.find()) {
            String n = norm(m.group(1));
            if (n.equals(normalizado)) {
                return m.group(1);
            }
            // «MNprogram» a secas vale para «MNprogram - Abogados - Empresa de prueba…»: el título largo lo contiene.
            if (aproximado == null && normalizado.length() >= 5 && n.contains(normalizado)) {
                aproximado = m.group(1);
            }
        }
        if (aproximado != null) {
            return aproximado;
        }
        // Programas de documentos («informe.txt: Bloc de notas», «Carta - Word»): el nombre del documento cambia de una vez a
        // otra, el del programa no. Vale la ventana cuyo título acaba con el nombre del programa.
        String programa = null;
        for (String sep : List.of(": ", " - ")) {
            int i = normalizado.lastIndexOf(sep);
            if (i > 0 && normalizado.length() - i - sep.length() >= 4) {
                programa = normalizado.substring(i + sep.length());
                break;
            }
        }
        if (programa != null) {
            m = TITULO.matcher(ventanas.abiertas());
            while (m.find()) {
                String n = norm(m.group(1));
                if (n.equals(programa) || n.endsWith(": " + programa) || n.endsWith(" - " + programa)) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    public static String sustituir(String s, Map<String, String> params) {
        for (var e : params.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        return s;
    }

    /** Reproduce la receta con el agente como «manos». Cada paso, con su tiempo máximo; si falla uno, se para ahí. */
    public Resultado reproducir(Agente ag, Map<String, String> params, int segundosPorPaso, Consumer<String> registro) {
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "receta");
            t.setDaemon(true);
            return t;
        });
        try {
            for (int i = 0; i < pasos.size(); i++) {
                Paso p = pasos.get(i);
                final int n = i + 1;
                Map<String, Object> in = new LinkedHashMap<>(p.in());
                for (String campo : List.of("text", "texto")) {
                    if (in.get(campo) instanceof String s) {
                        String r = sustituir(s, params);
                        if (r.contains("{") && r.matches("(?s).*\\{[a-z_]+}.*")) {
                            return new Resultado(false, i, pasos.size(), "Falta un parámetro en el paso " + n + ": " + r);
                        }
                        in.put(campo, r);
                    }
                }
                registro.accept("  receta " + n + "/" + pasos.size() + " · " + p.accion());
                Future<String> f = pool.submit(() -> hacer(ag, p, in));
                try {
                    String fallo = f.get(segundosPorPaso, TimeUnit.SECONDS);
                    if (fallo != null) {
                        return new Resultado(false, i, pasos.size(), "Paso " + n + " (" + p.accion() + "): " + fallo);
                    }
                } catch (java.util.concurrent.TimeoutException e) {
                    f.cancel(true);
                    return new Resultado(false, i, pasos.size(), "Paso " + n + " (" + p.accion() + "): tardó más de " + segundosPorPaso + " s");
                } catch (java.util.concurrent.ExecutionException e) {
                    return new Resultado(false, i, pasos.size(), "Paso " + n + " (" + p.accion() + "): " + e.getCause().getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Resultado(false, i, pasos.size(), "interrumpida");
                }
            }
            return new Resultado(true, pasos.size(), pasos.size(), "Receta «" + nombre + "» reproducida entera.");
        } finally {
            pool.shutdownNow();
        }
    }

    /** Un solo paso con sus parámetros y su tiempo máximo. null = bien; si no, por qué no. «_opcional» no es un fallo. */
    public static String paso(Agente ag, Paso p, Map<String, String> params, int segundos) {
        Map<String, Object> in = new LinkedHashMap<>(p.in());
        for (String campo : List.of("text", "texto", "esperado")) {
            if (in.get(campo) instanceof String s) {
                String r = sustituir(s, params);
                if (r.matches("(?s).*\\{[a-z_]+}.*")) {
                    return "falta un parámetro: " + r;
                }
                in.put(campo, r);
            }
        }
        boolean opcional = Boolean.TRUE.equals(in.remove("_opcional"));
        in.remove("_si");
        in.remove("_sin_ventana");
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "paso");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<String> f = pool.submit(() -> new Receta("x", List.of()).hacer(ag, p, in));
            String fallo = f.get(segundos, TimeUnit.SECONDS);
            return opcional ? null : fallo;
        } catch (java.util.concurrent.TimeoutException e) {
            return opcional ? null : "tardó más de " + segundos + " s";
        } catch (java.util.concurrent.ExecutionException e) {
            return opcional ? null : String.valueOf(e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrumpido";
        } finally {
            pool.shutdownNow();
        }
    }

    /** Devuelve null si ha ido bien, o por qué no. */
    private String hacer(Agente ag, Paso p, Map<String, Object> in) {
        String vetado = ag.vetar(p.accion(), in);
        if (vetado != null) {
            return "VETADO: " + vetado;
        }
        Ventanas v = ag.ventanas();
        Manos manos = ag.manos();
        boolean desdeDerecha = Boolean.TRUE.equals(in.remove("_der"));
        if (p.ventana() != null && v != null) {
            String real = tituloReal(v, p.ventana());
            if (real == null && !"cerrar_ventana".equals(p.accion()) && !"esperar_a".equals(p.accion())) {
                return "no está la ventana «" + p.ventana() + "»";
            }
            if (real != null) {
                if (in.containsKey("ventana")) {
                    in.put("ventana", real);
                }
                if (in.containsKey("titulo")) {
                    in.put("titulo", real);
                }
                if (p.rel() != null) {
                    v.activar(real);
                    var h = ((es.amagna.asistente.manos.VentanasNativas) v).handleDe(real);
                    // La ventana principal del programa NO se toca: encoger MN entero cambia la pantalla a todo lo demás.
                    // Esto solo vale para formularios y listas, que MN abre con el último tamaño que tuvieron.
                    if (!real.startsWith("MNprogram") && Win32.desmaximizar(h)) {
                        System.out.println("  ventana «" + real + "» maximizada: devuelta a su tamaño normal (las posiciones de la receta son de ese tamaño)");
                    }
                    if (!real.startsWith("MNprogram") && Win32.traerAPantalla(h)) {
                        System.out.println("  ventana «" + real + "» estaba medio fuera de la pantalla: movida para que se vea entera");
                    }
                    int[] r = h == null ? null : Win32.rectangulo(h);
                    if (r == null) {
                        return "no se ve la ventana «" + real + "»";
                    }
                    int[] o = manos.escala().aImagen(r[0], r[1]);
                    // _der: la x se mide desde el borde derecho (el aspa de cerrar está ahí, sea cual sea el ancho de la ventana)
                    int ancho = desdeDerecha ? manos.escala().aImagen(r[0] + r[2], r[1])[0] - o[0] : 0;
                    int cx = o[0] + ancho + p.rel()[0];
                    int cy = o[1] + p.rel()[1];
                    int[] visto = porImagen(manos, h, r, p, cx, cy);
                    if (visto != null) {
                        cx = visto[0];
                        cy = visto[1];
                    }
                    in.put("coordinate", List.of((long) cx, (long) cy));
                }
            }
        }
        if ("responder_no".equals(p.accion())) {
            // Contesta «No» a un aviso de MN por el propio Windows (sin UIA, que se cuelga en esos diálogos, y sin coordenadas).
            var h = v instanceof es.amagna.asistente.manos.VentanasNativas vn && p.ventana() != null ? vn.handleDe(p.ventana()) : null;
            return h == null ? "no está el aviso «" + p.ventana() + "»" : (Win32.responderNo(h) ? null : "el aviso no ha contestado");
        }
        if ("cerrar_ventana_mn".equals(p.accion())) {
            // Cierra con el aspa de Windows (WM_CLOSE) una ventana hija de MN que el mapa nombra: no depende de dónde
            // esté ni de cuánto mida. Solo se usa en pasos del mapa, que ya saben qué ventana es y que no se ha tocado.
            if (v == null || p.ventana() == null) {
                return "no hay ventana que cerrar";
            }
            String real = tituloReal(v, p.ventana());
            return real == null ? null : (v.cerrarVentana(real) == null ? "no se ha podido cerrar «" + real + "»" : null);
        }
        try {
            if (Agente.ES_DE_ORDENADOR.contains(p.accion())) {
                ag.ejecutarAccion(p.accion(), in);
            } else {
                String texto = Agente.texto(ag.ejecutarPropia(p.accion(), in));
                if (Agente.pareceFallo(texto)) {
                    return texto;
                }
            }
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    /**
     * El clic por su dibujo. Si hay un recorte aprendido de este sitio, se busca en la ventana tal como está ahora y
     * se devuelve donde está (en coordenadas de la imagen del agente). Si no hay recorte, se aprende uno (queda
     * pendiente hasta que la tarea termine bien). Si no se encuentra o algo no cuadra, null: se usa la posición
     * de siempre, que es lo que se hacía antes. Nunca lanza.
     */
    private static int[] porImagen(Manos manos, com.sun.jna.Pointer h, int[] r, Paso p, int cx, int cy) {
        es.amagna.asistente.manos.Anclas anclas = es.amagna.asistente.manos.Anclas.actual();
        if (anclas == null) {
            return null;
        }
        try {
            var cap = es.amagna.asistente.manos.Captura.deVentana(h);
            if (!cap.valido() || cap.enBlanco() || cap.ancho() != r[2] || cap.alto() != r[3]) {
                return null; // el dibujo no corresponde al rectángulo (escala rara): no se fía
            }
            int[] esperado = manos.escala().aPantalla(cx, cy);
            int ex = esperado[0] - r[0];
            int ey = esperado[1] - r[1];
            int rx = p.rel()[0];
            int ry = p.rel()[1];
            var recorte = anclas.leer(p.ventana(), rx, ry);
            if (recorte == null) {
                anclas.aprender(p.ventana(), rx, ry, es.amagna.asistente.manos.Ancla.recortar(cap.imagen(), ex, ey));
                return null;
            }
            int[] dentro = es.amagna.asistente.manos.Ancla.puntoEnRecorte(cap.imagen(), ex, ey);
            var hallazgo = es.amagna.asistente.manos.Ancla.buscar(cap.imagen(), recorte, ex - dentro[0], ey - dentro[1]);
            if (hallazgo == null) {
                System.out.println("  ancla " + p.ventana() + "@" + rx + "," + ry + ": no encontrada, uso la posición de siempre");
                return null;
            }
            int vx = hallazgo.x() + dentro[0];
            int vy = hallazgo.y() + dentro[1];
            System.out.println("  ancla " + p.ventana() + "@" + rx + "," + ry + ": encontrada (desvío " + (vx - ex) + "," + (vy - ey) + " px)");
            return manos.escala().aImagen(r[0] + vx, r[1] + vy);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
