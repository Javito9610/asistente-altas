package es.amagna.asistente.estados;

import es.amagna.asistente.cerebro.Receta;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hace una tarea del mapa: mira dónde está, calcula el camino, ejecuta cada transición y COMPRUEBA que ha llegado
 * donde tocaba. Si algo falla (pantalla desconocida, paso que no responde, estado inesperado) llama al rescate (la IA)
 * con lo que ha pasado y vuelve a mirar dónde está. Funciona desde cualquier punto de partida.
 */
public final class Ejecutor {
    public interface Observa {
        String ventanas();

        /** El texto que hay dentro de una ventana (para distinguir un aviso de otro). */
        default String texto(String ventana) {
            return "";
        }
    }

    /** null = hecho; si no, el motivo. */
    public interface Hace {
        String paso(Receta.Paso paso, Map<String, String> params);
    }

    public interface Rescate {
        void intentar(String tarea, String situacion);
    }

    public interface Reloj {
        long ahora();

        void dormir(long ms);
    }

    /** {@code incidencias}: lo que se ha hecho a medias y una persona debe mirar (para el informe), aunque la tarea haya salido «bien». */
    public record Resultado(boolean bien, String mensaje, String numero, int rescates, List<String> incidencias) {
        public Resultado(boolean bien, String mensaje, String numero, int rescates) {
            this(bien, mensaje, numero, rescates, List.of());
        }
    }

    private static final Reloj REAL = new Reloj() {
        public long ahora() {
            return System.currentTimeMillis();
        }

        public void dormir(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    };
    private static final Pattern NUMERO = Pattern.compile("(\\d+/\\d{4})");
    static final int MAX_RESCATES = 2;

    private final Mapa mapa;
    private final Observa observa;
    private final Hace hace;
    private final Rescate rescate;
    private final Reloj reloj;
    private final java.util.function.Consumer<String> registro;
    private String numero = "";
    private int rescates;
    private boolean comprometido;

    public Ejecutor(Mapa mapa, Observa observa, Hace hace, Rescate rescate, Reloj reloj, java.util.function.Consumer<String> registro) {
        this.mapa = mapa;
        this.observa = observa;
        this.hace = hace;
        this.rescate = rescate;
        this.reloj = reloj == null ? REAL : reloj;
        this.registro = registro;
    }

    private static class Fallo extends RuntimeException {
        Fallo(String m) {
            super(m);
        }
    }

    /**
     * Una comprobación o una auditoría que dice que lo escrito NO es lo esperado. No es un atasco: repetirlo daría lo
     * mismo, y la IA no lo arregla. Se deja la pantalla limpia (sin guardar) y se para diciendo qué no cuadra.
     */
    private static final class Verificacion extends Fallo {
        Verificacion(String m) {
            super(m);
        }
    }

    public Resultado ejecutar(String tarea, Map<String, String> params) {
        List<Mapa.Orden> ordenes = mapa.tareas().get(tarea);
        if (ordenes == null) {
            return new Resultado(false, "No hay ninguna tarea «" + tarea + "». Las que hay: " + String.join(", ", mapa.tareas().keySet()), "", 0);
        }
        for (var e : params.entrySet()) {
            if (e.getValue() != null && e.getValue().matches("(?s).*(Ã.|Â.|\\uFFFD|\\?\\?).*")) {
                return new Resultado(false, "PARADO: el parámetro «" + e.getKey() + "» llega con la codificación rota («" + e.getValue()
                        + "»): el .cmd se guardó en otra codificación. Usa solo letras sin tilde en los .cmd. No se ha tocado nada.", "", 0);
            }
        }
        // Un Ejecutor puede encadenar tareas: lo irreversible y los empujones son de ESTA tarea, no de la anterior.
        comprometido = false;
        numero = "";
        rescates = 0;
        String ultimoFallo = null;
        int limpiezasMapa = 0;
        List<String> incidencias = new java.util.ArrayList<>();
        while (true) {
            // Cada intento parte de los datos originales: lo que una rama «olvida» se decide de nuevo en cada vuelta.
            Map<String, String> datos = new java.util.LinkedHashMap<>(params);
            incidencias.clear();
            try {
                for (Mapa.Orden o : ordenes) {
                    if (o.si() != null && !o.si().contains(estado())) {
                        continue;
                    }
                    if (o.incidencia() != null) {
                        incidencias.add(Receta.sustituir(o.incidencia(), datos));
                    }
                    if (o.olvida() != null) {
                        o.olvida().forEach(datos::remove);
                    }
                    if (o.ir() != null) {
                        alcanzar(o.ir(), datos);
                    } else if (o.hacer() != null) {
                        Mapa.Transicion t = mapa.accion(o.hacer());
                        alcanzar(t.de(), datos);
                        recorrer(t, datos);
                    }
                }
                break;
            } catch (Fallo f) {
                registro.accept("  FALLO: " + f.getMessage());
                String vs = observa.ventanas();
                registro.accept("  Ventanas en ese momento:\n" + vs);
                guardarEvidencia(vs);
                var dlg = java.util.regex.Pattern.compile("diálogo «([^»]+)»").matcher(vs);
                if (dlg.find()) {
                    String leido = observa.texto(dlg.group(1));
                    registro.accept("  Lo que se lee del diálogo «" + dlg.group(1) + "»: "
                            + (leido == null ? "" : leido.replaceAll("\\s+", " ").strip()).substring(0, Math.min(300, leido == null ? 0 : leido.replaceAll("\\s+", " ").strip().length())));
                }
                if (f instanceof Verificacion && !comprometido) {
                    String limpio = dejarLimpio();
                    return new Resultado(false, "PARADO: la verificación no cuadra, no se ha guardado nada. " + f.getMessage() + " " + limpio,
                            numero, rescates);
                }
                if (comprometido) {
                    // Ya se hizo lo irreversible (Guardar). Repetir la tarea crearía un duplicado: se para y se dice.
                    return new Resultado(false, "PARADO: " + f.getMessage() + " OJO: ya se había guardado; NO se repite. "
                            + "Comprueba a mano si el expediente " + (numero.isBlank() ? "" : numero + " ") + "quedó bien.", numero, rescates);
                }
                if (f.getMessage().equals(ultimoFallo)) {
                    // Falló igual después de dejar la pantalla limpia: no es un atasco puntual, es el dato o el mapa.
                    return new Resultado(false, "PARADO: " + f.getMessage() + " Falla igual tras limpiar la pantalla: revisa los datos "
                            + "(parámetros) o el mapa; repetir no lo arregla.", numero, rescates);
                }
                ultimoFallo = f.getMessage();
                // Primero el mapa: si sabe salir de donde ha quedado (cerrar el aviso, el selector, cancelar el formulario),
                // lo limpia él y se reintenta una vez. La IA solo entra si el mapa no sabe cómo salir de ahí.
                if (limpiezasMapa < 2 && limpiarConMapa()) {
                    limpiezasMapa++;
                    registro.accept("  Pantalla limpia con el mapa (sin IA); se reintenta la tarea desde ahí.");
                    continue;
                }
                if (rescate == null || rescates >= MAX_RESCATES) {
                    return new Resultado(false, "PARADO: " + f.getMessage(), numero, rescates);
                }
                rescates++;
                registro.accept("  La IA da el empujón (" + rescates + " de " + MAX_RESCATES + ")…");
                rescate.intentar(tarea, f.getMessage() + " Estado actual: " + estado() + ". Ventanas:\n" + observa.ventanas());
                // Después del empujón se vuelve a empezar la tarea desde donde esté la pantalla: el mapa vale desde cualquier punto.
            }
        }
        return new Resultado(true, "HECHO: «" + tarea + "»" + (numero.isBlank() ? "" : " (expediente " + numero + ")")
                + (incidencias.isEmpty() ? "" : " CON INCIDENCIAS: " + String.join(" · ", incidencias)), numero, rescates, List.copyOf(incidencias));
    }

    /** Sale de lo que haya abierto sin guardar (Cancelar y «¿salir?» → Sí) hasta el estado de reposo del mapa. */
    private String dejarLimpio() {
        try {
            alcanzar(mapa.reposo(), java.util.Map.of());
            return "Pantalla limpia: «" + mapa.reposo() + "».";
        } catch (RuntimeException e) {
            return "OJO: no he podido dejar la pantalla limpia (" + e.getMessage() + "): míralo a mano.";
        }
    }

    /**
     * Foto de la ventana de MN que está delante en el momento del fallo (registro/fallo-HHmmss.png): así el siguiente
     * arreglo se hace mirando lo que había en pantalla y no adivinando. Nunca debe romper nada. Es una foto de la
     * empresa de prueba; si la pantalla fuera de una empresa real, no se guarda.
     */
    private void guardarEvidencia(String ventanas) {
        try {
            String titulo = es.amagna.asistente.cerebro.Agente.tituloMnDelante(ventanas);
            if (titulo == null || !ventanas.contains("Empresa de prueba")) {
                return;
            }
            var v = new es.amagna.asistente.manos.VentanasNativas(false);
            var h = v.handleDe(titulo);
            var c = es.amagna.asistente.manos.Captura.deVentana(h);
            byte[] png = c == null || !c.valido() ? null : c.png();
            if (png != null) {
                var f = java.nio.file.Path.of("registro", "fallo-" + java.time.LocalTime.now().toString().substring(0, 8).replace(':', '-') + ".png");
                java.nio.file.Files.createDirectories(f.getParent());
                java.nio.file.Files.write(f, png);
                registro.accept("  Foto del fallo: " + f);
            }
        } catch (Throwable e) {
            registro.accept("  (no se pudo guardar la foto del fallo: " + e.getMessage() + ")");
        }
    }

    /** ¿Sabe el mapa salir de donde está hasta el estado de reposo? (Sin IA.) */
    private boolean limpiarConMapa() {
        try {
            alcanzar(mapa.reposo(), java.util.Map.of());
            return true;
        } catch (RuntimeException e) {
            registro.accept("  El mapa no sabe limpiar la pantalla: " + e.getMessage());
            return false;
        }
    }

    /** El título real (con sus números) de la ventana que se parece al título dado (el número no importa). */
    public static String tituloReal(String abiertas, String titulo) {
        String n = Receta.norm(titulo);
        for (Reconocedor.Ventana w : Reconocedor.leer(abiertas)) {
            if (Receta.norm(w.titulo()).equals(n)) {
                return w.titulo();
            }
        }
        return null;
    }

    /** El estado, esperando a que un «Cargando…» se quite. */
    private String ultimoAtencion = "";

    String estado() {
        String e = Reconocedor.estado(mapa, observa.ventanas(), observa::texto);
        long fin = reloj.ahora() + 25_000;
        while ("cargando".equals(e) && reloj.ahora() < fin) {
            reloj.dormir(500);
            e = Reconocedor.estado(mapa, observa.ventanas(), observa::texto);
        }
        if ("atencion".equals(e)) {
            // Un aviso «Atención» que el mapa no distingue: se deja escrito lo que se lee para no adivinar por qué.
            String leido;
            try {
                leido = observa.texto("Atención");
            } catch (RuntimeException x) {
                leido = "(error al leer: " + x.getMessage() + ")";
            }
            String resumen = leido == null ? "" : leido.replaceAll("\\s+", " ").strip();
            if (!resumen.equals(ultimoAtencion)) {
                ultimoAtencion = resumen;
                registro.accept("  Aviso «Atención» sin reconocer. Se lee: " + (resumen.length() > 500 ? resumen.substring(0, 500) + "…" : resumen));
            }
        }
        anotarNumero(e);
        return e;
    }

    private void anotarNumero(String estado) {
        String t = Reconocedor.tituloDe(mapa, observa.ventanas(), estado);
        if (t != null && estado.startsWith("formulario")) {
            Matcher m = NUMERO.matcher(t);
            if (m.find()) {
                numero = m.group(1);
            }
        }
    }

    private void alcanzar(String destino, Map<String, String> params) {
        for (int vuelta = 0; vuelta < 8; vuelta++) {
            String aqui = estado();
            if (aqui.equals(destino)) {
                return;
            }
            List<Mapa.Transicion> camino = Planificador.camino(mapa, aqui, destino);
            if (camino == null || camino.isEmpty()) {
                throw new Fallo("no sé llegar de «" + aqui + "» a «" + destino + "» (pantalla desconocida o sin camino en el mapa).");
            }
            recorrer(camino.get(0), params);
        }
        throw new Fallo("no he conseguido llegar a «" + destino + "» tras 8 intentos.");
    }

    private void recorrer(Mapa.Transicion t, Map<String, String> params) {
        registro.accept("  " + t.de() + " → " + t.a() + (t.nombre().isBlank() ? "" : " (" + t.nombre() + ")"));
        if (t.toca()) {
            comprometido = true; // desde el primer paso: un clic en Guardar puede haber guardado aunque lo demás falle
        }
        for (Receta.Paso p : t.pasos()) {
            Object si = p.in().get("_si");
            if (si != null && (params.get(String.valueOf(si)) == null || params.get(String.valueOf(si)).isBlank())) {
                continue;
            }
            long desde = reloj.ahora();
            String err = hace.paso(p, params);
            long ms = reloj.ahora() - desde;
            if (ms >= 4000 && !p.accion().equals("wait")) {
                // Para saber DÓNDE se va el tiempo antes de recortar nada: solo los pasos que tardan.
                registro.accept("  · «" + p.accion() + "» tardó " + (ms / 1000) + " s" + (p.ventana() == null ? "" : " (" + p.ventana() + ")"));
            }
            if (err != null && (p.accion().equals("comprobar") || p.accion().equals("auditar") || err.startsWith("falta un parámetro"))) {
                throw new Verificacion("«" + t.nombre() + "», paso «" + p.accion() + "»: " + err);
            }
            if (err != null) {
                throw new Fallo("«" + t.de() + " → " + t.a() + "», paso «" + p.accion() + "»: " + err);
            }
        }
        if (t.toca()) {
            comprometido = true;
        }
        long fin = reloj.ahora() + t.segundos() * 1000L;
        String ahora;
        do {
            ahora = estado();
            if (t.destinos().contains(ahora)) {
                return;
            }
            reloj.dormir(400);
        } while (reloj.ahora() < fin);
        throw new Fallo("tras «" + t.nombre() + "» esperaba estar en «" + t.a() + "» y estoy en «" + ahora + "».");
    }
}
