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

/**
 * El mapa de pantallas de un programa: qué estados hay, cómo se reconoce cada uno, cómo se pasa de uno a otro y qué
 * tareas se pueden pedir. Es un fichero (estados/mn.json): datos, no código.
 *
 * <pre>
 * { "estados":      [{"nombre": "lista-expedientes", "ventana": "Expedientes"}],
 *   "transiciones": [{"nombre": "alta", "de": "lista-expedientes", "a": "formulario-expediente", "segundos": 30,
 *                     "pasos": [{"accion": "pulsar_control", "ventana": "Expedientes", "in": {"control": "boton:Alta"}}]}],
 *   "tareas":       {"crear-expediente": [{"ir": "lista-expedientes"}, {"hacer": "alta"}]} }
 * </pre>
 *
 * «ventana» es el título con los números cambiados por # (ver {@link Receta#norm}). Un estado se reconoce porque hay
 * una ventana así delante y sin nada encima. Los estados especiales «fuera» (el programa no está) e «inicio» (solo la
 * ventana principal) no hace falta declararlos, pero sí sus transiciones.
 */
public final class Mapa {
    public record Estado(String nombre, String ventana) {
    }

    public record Transicion(String nombre, String de, String a, int segundos, boolean toca, List<Receta.Paso> pasos) {
    }

    public record Orden(String ir, String hacer) {
    }

    private final List<Estado> estados;
    private final List<Transicion> transiciones;
    private final Map<String, List<Orden>> tareas;
    private final String principal;

    public Mapa(List<Estado> estados, List<Transicion> transiciones, Map<String, List<Orden>> tareas, String principal) {
        this.estados = estados;
        this.transiciones = transiciones;
        this.tareas = tareas;
        this.principal = principal;
    }

    public List<Estado> estados() {
        return estados;
    }

    public List<Transicion> transiciones() {
        return transiciones;
    }

    public Map<String, List<Orden>> tareas() {
        return tareas;
    }

    /** Parte del título de la ventana principal del programa (para reconocer «inicio»). */
    public String principal() {
        return principal;
    }

    public Transicion accion(String nombre) {
        for (Transicion t : transiciones) {
            if (nombre.equals(t.nombre())) {
                return t;
            }
        }
        return null;
    }

    public boolean existe(String estado) {
        if (estado.equals("fuera") || estado.equals("inicio")) {
            return true;
        }
        return estados.stream().anyMatch(e -> e.nombre().equals(estado));
    }

    public static Mapa leer(Path f) throws IOException {
        return desde(Files.readString(f, StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    public static Mapa desde(String json) {
        Map<String, Object> raiz = Json.objeto(json);
        List<Estado> es = new ArrayList<>();
        for (Object o : (List<Object>) raiz.getOrDefault("estados", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            es.add(new Estado((String) m.get("nombre"), Receta.norm((String) m.get("ventana"))));
        }
        List<Transicion> ts = new ArrayList<>();
        for (Object o : (List<Object>) raiz.getOrDefault("transiciones", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            List<Receta.Paso> pasos = new ArrayList<>();
            for (Object po : (List<Object>) m.getOrDefault("pasos", List.of())) {
                Map<String, Object> pm = (Map<String, Object>) po;
                int[] rel = null;
                if (pm.get("rel") instanceof List<?> l) {
                    rel = new int[] {((Number) l.get(0)).intValue(), ((Number) l.get(1)).intValue()};
                }
                pasos.add(new Receta.Paso((String) pm.get("accion"),
                        new LinkedHashMap<>((Map<String, Object>) pm.getOrDefault("in", Map.of())),
                        pm.get("ventana") == null ? null : Receta.norm((String) pm.get("ventana")), rel));
            }
            ts.add(new Transicion(String.valueOf(m.getOrDefault("nombre", "")), (String) m.get("de"), (String) m.get("a"),
                    m.get("segundos") instanceof Number n ? n.intValue() : 30, Boolean.TRUE.equals(m.get("toca")), pasos));
        }
        Map<String, List<Orden>> tareas = new LinkedHashMap<>();
        Map<String, Object> tm = (Map<String, Object>) raiz.getOrDefault("tareas", Map.of());
        for (var e : tm.entrySet()) {
            List<Orden> ord = new ArrayList<>();
            for (Object o : (List<Object>) e.getValue()) {
                Map<String, Object> m = (Map<String, Object>) o;
                ord.add(new Orden((String) m.get("ir"), (String) m.get("hacer")));
            }
            tareas.put(e.getKey(), ord);
        }
        Mapa mapa = new Mapa(es, ts, tareas, String.valueOf(raiz.getOrDefault("principal", "")));
        mapa.validar();
        return mapa;
    }

    /** Un mapa mal escrito se rechaza al leerlo, no a mitad de un alta. */
    void validar() {
        for (Transicion t : transiciones) {
            if (!existe(t.de()) || !existe(t.a())) {
                throw new IllegalArgumentException("Transición «" + t.nombre() + "»: estado desconocido (" + t.de() + " → " + t.a() + ")");
            }
        }
        for (var e : tareas.entrySet()) {
            for (Orden o : e.getValue()) {
                if (o.ir() != null && !existe(o.ir())) {
                    throw new IllegalArgumentException("Tarea «" + e.getKey() + "»: no existe el estado «" + o.ir() + "»");
                }
                if (o.hacer() != null && accion(o.hacer()) == null) {
                    throw new IllegalArgumentException("Tarea «" + e.getKey() + "»: no existe la acción «" + o.hacer() + "»");
                }
            }
        }
    }
}
