package es.amagna.asistente.estados;

import es.amagna.asistente.Json;
import es.amagna.asistente.cerebro.Receta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lo que la IA enseña al asistente. Cuando el mapa no sabe salir de una pantalla y la IA lo arregla, sus pasos (solo clics y teclas
 * seguras) se guardan con la «firma» de esa pantalla. La próxima vez que aparezca la misma pantalla el asistente repite los pasos
 * él solo, sin IA. Una lección empieza provisional: solo se fía de ella después de que la tarea haya acabado bien con ella
 * (y se desecha si falla más veces de las que acierta).
 */
public final class Lecciones {
    /** Lo único que se aprende: nada que escriba datos ni confirme/guarde por defecto. */
    static final Set<String> SEGURAS = Set.of("left_click", "key", "wait", "esperar_a", "responder_no", "cerrar_ventana_mn");
    static final Set<String> TECLAS_PROHIBIDAS = Set.of("return", "enter", "kp_enter", "space");
    static final int MAX_PASOS = 8;

    public record Leccion(String firma, List<Receta.Paso> pasos, int exitos, int fallos) {
        public boolean fiable() {
            return fallos <= exitos;
        }
    }

    private final Path fichero;
    private final Map<String, Leccion> todas = new LinkedHashMap<>();

    public Lecciones(Path fichero) {
        this.fichero = fichero;
        cargar();
    }

    /**
     * La huella de una pantalla atascada: qué ventana tiene el foco y qué diálogos hay encima, sin números ni posiciones
     * (esos cambian) y con el principio del texto del diálogo, si se pudo leer.
     */
    public static String firma(String ventanas, String texto) {
        StringBuilder b = new StringBuilder();
        for (String linea : ventanas.split("\\R")) {
            boolean delante = linea.contains("[delante") || linea.contains("diálogos abiertos dentro") || linea.contains("BLOQUEADA");
            if (!delante) {
                continue;
            }
            String l = linea.replaceAll("\\[pos[^\\]]*\\]", "").replaceAll("\\(pid \\d+\\)", "").replaceAll("\\d+", "#")
                    .replaceAll("\\s+", " ").strip();
            b.append(l).append(" | ");
        }
        String t = texto == null ? "" : texto.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-záéíóúñü ]", " ").replaceAll("\\s+", " ").strip();
        if (t.length() > 80) {
            t = t.substring(0, 80);
        }
        return b + "texto: " + t;
    }

    /** ¿Es seguro aprender estos pasos? */
    public static boolean seguros(List<Receta.Paso> pasos) {
        if (pasos.isEmpty() || pasos.size() > MAX_PASOS) {
            return false;
        }
        for (Receta.Paso p : pasos) {
            if (!SEGURAS.contains(p.accion())) {
                return false;
            }
            if ("key".equals(p.accion())) {
                String k = String.valueOf(p.in().get("text")).toLowerCase(java.util.Locale.ROOT);
                for (String prohibida : TECLAS_PROHIBIDAS) {
                    if (k.equals(prohibida) || k.endsWith("+" + prohibida)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public synchronized Leccion buscar(String firma) {
        Leccion l = todas.get(firma);
        return l != null && l.fiable() ? l : null;
    }

    /** Guarda lo que acaba de hacer la IA. Si no es seguro, no se aprende y se dice por qué. Devuelve el motivo si no. */
    public synchronized String aprender(String firma, List<Receta.Paso> pasos) {
        if (!seguros(pasos)) {
            return pasos.isEmpty() ? "la IA no hizo ningún paso grabable" : "los pasos de la IA incluyen algo que no se aprende solo (teclear, Intro, guardar…)";
        }
        Leccion vieja = todas.get(firma);
        todas.put(firma, new Leccion(firma, List.copyOf(pasos), vieja == null ? 0 : vieja.exitos, vieja == null ? 0 : vieja.fallos));
        guardar();
        return null;
    }

    public synchronized void resultado(String firma, boolean bien) {
        Leccion l = todas.get(firma);
        if (l == null) {
            return;
        }
        todas.put(firma, new Leccion(firma, l.pasos, l.exitos + (bien ? 1 : 0), l.fallos + (bien ? 0 : 1)));
        guardar();
    }

    public synchronized int cuantas() {
        return todas.size();
    }

    public synchronized Leccion tal(String firma) {
        return todas.get(firma);
    }

    @SuppressWarnings("unchecked")
    private void cargar() {
        try {
            if (!Files.exists(fichero)) {
                return;
            }
            Map<String, Object> raiz = Json.objeto(Files.readString(fichero, StandardCharsets.UTF_8));
            for (Object o : (List<Object>) raiz.getOrDefault("lecciones", List.of())) {
                Map<String, Object> m = (Map<String, Object>) o;
                List<Receta.Paso> pasos = new ArrayList<>();
                for (Object po : (List<Object>) m.get("pasos")) {
                    Map<String, Object> p = (Map<String, Object>) po;
                    int[] rel = null;
                    if (p.get("rel") instanceof List<?> l) {
                        rel = new int[] {((Number) l.get(0)).intValue(), ((Number) l.get(1)).intValue()};
                    }
                    pasos.add(new Receta.Paso((String) p.get("accion"), new LinkedHashMap<>((Map<String, Object>) p.get("in")),
                            (String) p.get("ventana"), rel));
                }
                String firma = (String) m.get("firma");
                todas.put(firma, new Leccion(firma, pasos, ((Number) m.getOrDefault("exitos", 0)).intValue(),
                        ((Number) m.getOrDefault("fallos", 0)).intValue()));
            }
        } catch (IOException | RuntimeException e) {
            // un fichero estropeado no debe romper nada: se empieza sin lecciones
        }
    }

    private void guardar() {
        try {
            List<Object> lista = new ArrayList<>();
            for (Leccion l : todas.values()) {
                List<Object> ps = new ArrayList<>();
                for (Receta.Paso p : l.pasos) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("accion", p.accion());
                    m.put("in", p.in());
                    if (p.ventana() != null) {
                        m.put("ventana", p.ventana());
                    }
                    if (p.rel() != null) {
                        m.put("rel", List.of((long) p.rel()[0], (long) p.rel()[1]));
                    }
                    ps.add(m);
                }
                lista.add(Json.obj("firma", l.firma, "exitos", (long) l.exitos, "fallos", (long) l.fallos, "pasos", ps));
            }
            Files.createDirectories(fichero.toAbsolutePath().getParent());
            Path tmp = fichero.resolveSibling(fichero.getFileName() + ".tmp");
            Files.writeString(tmp, Json.escribir(Json.obj("lecciones", lista)), StandardCharsets.UTF_8);
            Files.move(tmp, fichero, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            // nunca debe romper una tarea
        }
    }
}
