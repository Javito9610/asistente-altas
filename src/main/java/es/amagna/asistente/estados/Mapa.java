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
    /**
     * Un estado es una ventana por su título. {@code contiene}: texto que ha de leerse dentro de ella. {@code con} y
     * {@code sin}: otra ventana (por su título, con los números como #) que ha de estar o NO estar entre las abiertas;
     * sirve para distinguir dos ventanas que se llaman igual (la lista «Clientes» y el selector «Clientes» de un expediente).
     * En {@code ventana}, un «*» delante vale «cualquier cosa y luego esto».
     */
    public record Estado(String nombre, String ventana, String contiene, String con, String sin) {
        public Estado(String nombre, String ventana, String contiene) {
            this(nombre, ventana, contiene, "", "");
        }

        public Estado(String nombre, String ventana) {
            this(nombre, ventana, "", "", "");
        }
    }

    public record Transicion(String nombre, String de, String a, int segundos, boolean toca, List<Receta.Paso> pasos) {
        /** «a» puede ser varios estados separados por «|»: Cancelar, por ejemplo, pregunta «¿salir?» o cierra directamente. */
        public List<String> destinos() {
            return List.of(a.split("\\|"));
        }
    }

    /**
     * Una orden de una tarea. {@code si}: solo se hace si el estado actual es uno de esos (para ramas: «si el selector
     * sigue abierto…»). {@code incidencia}: algo que hay que contar en el informe cuando se pasa por aquí.
     * {@code olvida}: parámetros que dejan de valer desde aquí (p. ej. el contrario que no se ha podido poner).
     */
    public record Orden(String ir, String hacer, List<String> si, String incidencia, List<String> olvida) {
        public Orden(String ir, String hacer) {
            this(ir, hacer, null, null, null);
        }
    }

    private final List<Estado> estados;
    private final List<Transicion> transiciones;
    private final Map<String, List<Orden>> tareas;
    private final String principal;
    private String reposo = "inicio";

    /** El estado limpio al que se vuelve cuando hay que abandonar una tarea sin guardar. */
    public String reposo() {
        return reposo;
    }

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
            es.add(new Estado((String) m.get("nombre"), Receta.norm((String) m.get("ventana")), String.valueOf(m.getOrDefault("contiene", "")),
                    Receta.norm(String.valueOf(m.getOrDefault("con", ""))), Receta.norm(String.valueOf(m.getOrDefault("sin", "")))));
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
                Map<String, Object> entrada = new LinkedHashMap<>((Map<String, Object>) pm.getOrDefault("in", Map.of()));
                if (pm.get("rel_der") instanceof List<?> l) {
                    rel = new int[] {((Number) l.get(0)).intValue(), ((Number) l.get(1)).intValue()};
                    entrada.put("_der", true);
                }
                pasos.add(new Receta.Paso((String) pm.get("accion"),
                        entrada,
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
                ord.add(new Orden((String) m.get("ir"), (String) m.get("hacer"), lista(m.get("si")),
                        (String) m.get("incidencia"), lista(m.get("olvida"))));
            }
            tareas.put(e.getKey(), ord);
        }
        Mapa mapa = new Mapa(es, ts, tareas, String.valueOf(raiz.getOrDefault("principal", "")));
        mapa.reposo = String.valueOf(raiz.getOrDefault("reposo", "inicio"));
        mapa.validar();
        return mapa;
    }

    /** Un mapa mal escrito se rechaza al leerlo, no a mitad de un alta. */
    private static List<String> lista(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(o));
    }

    void validar() {
        if (!existe(reposo)) {
            throw new IllegalArgumentException("El estado de reposo «" + reposo + "» no existe");
        }
        for (Transicion t : transiciones) {
            if (!existe(t.de()) || !t.destinos().stream().allMatch(this::existe)) {
                throw new IllegalArgumentException("Transición «" + t.nombre() + "»: estado desconocido (" + t.de() + " → " + t.a() + ")");
            }
        }
        for (var e : tareas.entrySet()) {
            for (Orden o : e.getValue()) {
                if (o.si() != null && !o.si().stream().allMatch(this::existe)) {
                    throw new IllegalArgumentException("Tarea «" + e.getKey() + "»: «si» nombra un estado que no existe: " + o.si());
                }
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
