package es.amagna.asistente.jarvis;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * El cerebro de Jarvis como servicio: recibe mensajes de cualquier canal (Telegram, carpeta, ventana…), los entiende, los apunta
 * en el almacén y los hace UNO A UNO (un ordenador tiene un solo ratón). Reglas de seguridad:
 * <ul>
 *   <li>Solo atiende a personas autorizadas; el resto se ignora (y queda dicho en el registro).</li>
 *   <li>Las tareas que el mapa sabe hacer en la Empresa de prueba se hacen directamente; lo «libre» (cualquier otra cosa en el
 *       PC) siempre pide un «sí» antes.</li>
 *   <li>Un trabajo cortado por un apagado no se repite solo.</li>
 * </ul>
 */
public final class Servicio {
    /** Quien hace el trabajo de verdad. Devuelve true si salió bien; el mensaje es lo que se le cuenta a la persona. */
    public interface Hace {
        Resultado hacer(Trabajo t);
    }

    public record Resultado(boolean bien, String mensaje) {
    }

    /** Por dónde se habla con la persona. */
    public interface Salida {
        void enviar(String origen, String quien, String texto);
    }

    private final Almacen almacen;
    private final Interprete interprete;
    private final Hace tareas;
    private final Hace libre;
    private final Salida salida;
    private final Set<String> autorizados;
    private final Consumer<String> registro;
    private volatile boolean pausado;
    private Programadas programadas;
    private final Map<String, java.util.ArrayDeque<String>> historial = new java.util.concurrent.ConcurrentHashMap<>();

    public Servicio conProgramadas(Programadas p) {
        this.programadas = p;
        return this;
    }

    /** Lo último que se han dicho con esa persona: así «y ponle también el teléfono» se entiende. */
    private void apuntar(String quien, String linea) {
        var h = historial.computeIfAbsent(quien, k -> new java.util.ArrayDeque<>());
        synchronized (h) {
            h.addLast(linea.length() > 400 ? linea.substring(0, 400) : linea);
            while (h.size() > 8) {
                h.removeFirst();
            }
        }
    }

    private String contexto(String quien) {
        var h = historial.get(quien);
        if (h == null) {
            return "";
        }
        synchronized (h) {
            return h.isEmpty() ? "" : "Lo último de la conversación:\n" + String.join("\n", h);
        }
    }

    private void decir(String origen, String quien, String texto) {
        apuntar(quien, "Jarvis: " + texto);
        salida.enviar(origen, quien, texto);
    }

    /** Lo llama el bucle principal cada pocos segundos: lanza lo programado que toque. */
    public void tic(java.time.LocalDateTime ahora) {
        if (programadas == null) {
            return;
        }
        for (var p : programadas.vencidas(ahora)) {
            registro.accept("Programada " + p.id() + ": " + p.peticion());
            decir(p.origen(), p.quien(), "⏰ Toca lo programado: " + p.peticion());
            recibir(p.origen(), p.quien(), p.peticion());
        }
    }

    public Servicio(Almacen almacen, Interprete interprete, Hace tareas, Hace libre, Salida salida, Set<String> autorizados,
            Consumer<String> registro) {
        this.almacen = almacen;
        this.interprete = interprete;
        this.tareas = tareas;
        this.libre = libre;
        this.salida = salida;
        this.autorizados = autorizados;
        this.registro = registro;
    }

    /** Al arrancar: avisa de lo que se cortó. */
    public void arrancar() {
        for (Trabajo t : almacen.marcarInterrumpidos()) {
            decir(t.origen(), t.quien(), "⚠️ Se me apagó el ordenador haciendo esto: «" + t.peticion() + "». No lo repito solo por si ya estaba hecho. "
                    + "Mira en MN y, si falta, pídemelo otra vez.");
        }
    }

    public void pausar(boolean si) {
        pausado = si;
    }

    public boolean pausado() {
        return pausado;
    }

    /** Un mensaje de una persona. */
    public void recibir(String origen, String quien, String texto) {
        if (!autorizados.contains(quien)) {
            registro.accept("Mensaje de «" + quien + "» por " + origen + " ignorado: no está autorizado.");
            return;
        }
        String n = norm(texto);
        if (n.isEmpty()) {
            return;
        }
        // Palabras sueltas que no necesitan IA.
        Trabajo esperando = ultimoEsperando(quien);
        if (esperando != null && Set.of("si", "sí", "vale", "ok", "confirmo", "adelante", "hazlo", "dale").contains(n)) {
            almacen.guardar(esperando.con(Trabajo.PENDIENTE, null));
            decir(origen, quien, "Hecho, me pongo con ello.");
            return;
        }
        if (esperando != null && Set.of("no", "cancela", "cancelar", "para", "olvidalo").contains(n)) {
            almacen.guardar(esperando.con(Trabajo.RECHAZADO, "Cancelado por la persona."));
            decir(origen, quien, "Vale, lo dejo.");
            return;
        }
        if (Set.of("estado", "cola", "que haces", "que tal").contains(n)) {
            decir(origen, quien, estado());
            return;
        }
        if (Set.of("pausa", "pausar").contains(n)) {
            pausado = true;
            decir(origen, quien, "En pausa: no empiezo nada nuevo hasta que me digas «sigue».");
            return;
        }
        if (Set.of("sigue", "continua", "reanuda").contains(n)) {
            pausado = false;
            decir(origen, quien, "Seguimos.");
            return;
        }
        apuntar(quien, "Persona: " + texto);
        if (programadas != null && Set.of("programadas", "agenda", "que hay programado").contains(n)) {
            var l = programadas.todas();
            decir(origen, quien, l.isEmpty() ? "No hay nada programado." : "Programado:\n" + l.stream()
                    .map(p -> "· " + p.id() + " — " + p.hora() + " " + p.dias().size() + " días/sem: " + p.peticion()).collect(java.util.stream.Collectors.joining("\n"))
                    + "\n(Para quitar uno: «quita p12345».)");
            return;
        }
        if (programadas != null && n.matches("quita p\\d+")) {
            decir(origen, quien, programadas.quitar(n.substring(6)) ? "Quitado." : "No encuentro esa.");
            return;
        }
        Entendido e = interprete.entender(texto, contexto(quien));
        switch (e.tipo()) {
            case Entendido.PROGRAMAR -> {
                String id = programadas == null ? null : programadas.anadir(origen, quien, e.params().getOrDefault("hora", ""),
                        e.params().getOrDefault("dias", "todos"), e.texto());
                decir(origen, quien, id == null ? "No he podido programarlo: no entiendo la hora o los días. ¿Me lo repites (por ejemplo «cada día laborable a las 9:00»)?"
                        : "Programado (" + id + "): " + e.params().getOrDefault("hora", "") + " · " + e.params().getOrDefault("dias", "todos") + " → " + e.texto());
            }
            case Entendido.TAREA -> {
                Trabajo t = almacen.nuevo(origen, quien, texto, e.tarea(), e.params(), Trabajo.PENDIENTE);
                decir(origen, quien, "Entendido: «" + e.tarea() + "». " + (e.texto().isBlank() ? "" : e.texto() + " ")
                        + "Te aviso cuando acabe. (" + pendientes() + " en cola)");
                registro.accept("Trabajo " + t.id() + " apuntado: " + e.tarea());
            }
            case Entendido.LIBRE -> {
                Map<String, String> ps = new java.util.LinkedHashMap<>(e.params());
                ps.put("encargo", e.texto());
                if (!e.tarea().isBlank()) {
                    ps.put("_receta", e.tarea());
                }
                Trabajo t = almacen.nuevo(origen, quien, texto, "libre", ps, Trabajo.ESPERANDO);
                decir(origen, quien, "Voy a hacer esto en el ordenador: " + e.texto() + "\n¿Lo hago? (sí / no)");
                registro.accept("Trabajo " + t.id() + " espera confirmación.");
            }
            default -> decir(origen, quien, e.texto());
        }
    }

    /** Hace el siguiente trabajo pendiente, si hay. Devuelve true si hizo algo. */
    public boolean procesarUno() {
        if (pausado) {
            return false;
        }
        List<Trabajo> pend = almacen.en(Trabajo.PENDIENTE);
        if (pend.isEmpty()) {
            return false;
        }
        Trabajo t = pend.get(0);
        almacen.guardar(t.con(Trabajo.EN_CURSO, null));
        Resultado r;
        try {
            r = ("libre".equals(t.tarea()) ? libre : tareas).hacer(t);
        } catch (RuntimeException e) {
            r = new Resultado(false, "Se me ha roto algo por dentro: " + e.getMessage());
        }
        almacen.guardar(t.con(r.bien() ? Trabajo.HECHO : Trabajo.FALLO, r.mensaje()));
        decir(t.origen(), t.quien(), (r.bien() ? "✅ " : "❌ ") + r.mensaje());
        registro.accept("Trabajo " + t.id() + (r.bien() ? " hecho" : " con fallo") + ".");
        return true;
    }

    public String estado() {
        List<String> l = new ArrayList<>();
        for (Trabajo t : almacen.todos()) {
            if (!t.acabado()) {
                l.add("· " + t.estado() + ": " + t.peticion());
            }
        }
        List<Trabajo> todos = almacen.todos();
        Trabajo ultimo = todos.stream().filter(Trabajo::acabado).reduce((a, b) -> b).orElse(null);
        return (pausado ? "En pausa. " : "") + (l.isEmpty() ? "No tengo nada pendiente." : "Pendiente:\n" + String.join("\n", l))
                + (ultimo == null ? "" : "\nLo último: " + ultimo.estado() + " — " + ultimo.peticion());
    }

    private int pendientes() {
        return almacen.en(Trabajo.PENDIENTE).size();
    }

    private Trabajo ultimoEsperando(String quien) {
        Trabajo r = null;
        for (Trabajo t : almacen.en(Trabajo.ESPERANDO)) {
            if (t.quien().equals(quien)) {
                r = t;
            }
        }
        return r;
    }

    static String norm(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[¿?¡!.,]", "").strip();
    }
}
