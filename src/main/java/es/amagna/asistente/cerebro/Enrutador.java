package es.amagna.asistente.cerebro;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Decide a qué IA va cada petición. La regla de seguridad es de código, no de IA:
 *  · Lo que pueda llevar datos del despacho (ver {@link Sensibilidad}) o necesite herramientas, imágenes o documentos va
 *    SOLO a Claude. Si Claude no puede, no se contesta: nunca se «baja» a una IA gratuita para salir del paso.
 *  · Lo que es texto sin nada sensible va primero a Gemini (gratis), luego a Claude, y como último recurso al modelo local.
 * Si una IA se queda sin cuota, descansa un rato y no se vuelve a probar a cada mensaje.
 */
public final class Enrutador implements Cerebro {
    /** Clave opcional de la petición: el texto que DE VERDAD cambia (mensaje + contexto) y que hay que revisar. Se quita antes de enviar. */
    public static final String TEXTO_VARIABLE = "x_texto_variable";
    private static final long DESCANSO_MS = 15 * 60 * 1000L;

    private final Cerebro claude;
    private final Cerebro gemini;
    private final Cerebro local;
    private final Consumer<String> registro;
    private final AtomicLong nClaude = new AtomicLong();
    private final AtomicLong nGemini = new AtomicLong();
    private final AtomicLong nLocal = new AtomicLong();
    private volatile long geminiHasta;
    private volatile long localHasta;

    public Enrutador(Cerebro claude, Cerebro gemini, Cerebro local, Consumer<String> registro) {
        this.claude = claude;
        this.gemini = gemini;
        this.local = local;
        this.registro = registro == null ? s -> { } : registro;
    }

    @Override
    public Map<String, Object> pensar(Map<String, Object> peticion) {
        Map<String, Object> p = new LinkedHashMap<>(peticion);
        Object variable = p.remove(TEXTO_VARIABLE);
        boolean sinSalida = gemini == null && local == null;
        boolean segura = !sinSalida && GeminiApi.soloTexto(p)
                && !Sensibilidad.esSensible(variable != null ? variable.toString() : todoElTexto(p));
        List<String> orden = new ArrayList<>();
        if (segura) {
            orden.add("gemini");
            orden.add("claude");
            orden.add("local");
        } else {
            orden.add("claude");
        }
        RuntimeException ultimo = null;
        long ahora = System.currentTimeMillis();
        for (String quien : orden) {
            Cerebro c = switch (quien) {
                case "gemini" -> ahora < geminiHasta ? null : gemini;
                case "local" -> ahora < localHasta ? null : local;
                default -> claude;
            };
            if (c == null) {
                continue;
            }
            try {
                Map<String, Object> r = c.pensar(p);
                switch (quien) {
                    case "gemini" -> nGemini.incrementAndGet();
                    case "local" -> nLocal.incrementAndGet();
                    default -> nClaude.incrementAndGet();
                }
                return r;
            } catch (RuntimeException e) {
                ultimo = e;
                if ("gemini".equals(quien)) {
                    if (e instanceof GeminiApi.SinCuota) {
                        geminiHasta = System.currentTimeMillis() + DESCANSO_MS;
                    }
                    registro.accept("Gemini no ha podido (" + e.getMessage() + "); sigo con otra IA.");
                } else if ("local".equals(quien)) {
                    localHasta = System.currentTimeMillis() + DESCANSO_MS / 3;
                    registro.accept("El modelo local no ha podido (" + e.getMessage() + ").");
                } else if (segura) {
                    registro.accept("Claude no ha podido (" + e.getMessage() + "); pruebo con otra IA.");
                }
            }
        }
        throw ultimo != null ? ultimo : new IllegalStateException("no hay ninguna IA disponible ahora mismo");
    }

    @SuppressWarnings("unchecked")
    private static String todoElTexto(Map<String, Object> p) {
        StringBuilder b = new StringBuilder(GeminiApi.texto(p.get("system")));
        if (p.get("messages") instanceof List<?> l) {
            for (Object o : l) {
                b.append('\n').append(GeminiApi.texto(((Map<String, Object>) o).get("content")));
            }
        }
        return b.toString();
    }

    /** Para el diagnóstico: cuántas veces ha contestado cada una desde que arrancó. */
    public String resumen() {
        return "Claude " + nClaude.get() + (gemini != null ? " · Gemini " + nGemini.get() : " · Gemini no configurado")
                + (local != null ? " · local " + nLocal.get() : "");
    }
}
