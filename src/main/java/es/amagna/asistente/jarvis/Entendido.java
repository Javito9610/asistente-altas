package es.amagna.asistente.jarvis;

import java.util.Map;

/** Lo que Jarvis ha entendido de un mensaje. tipo: tarea (algo que el mapa sabe hacer) · libre (otra cosa en el PC) · preguntar · responder. */
public record Entendido(String tipo, String tarea, Map<String, String> params, String texto) {
    public static final String TAREA = "tarea";
    public static final String LIBRE = "libre";
    public static final String PREGUNTAR = "preguntar";
    public static final String RESPONDER = "responder";
    public static final String PROGRAMAR = "programar";
    public static final String CONSULTAR = "consultar";
    public static final String RECORDAR = "recordar";
    public static final String OFICINA = "oficina";
    public static final String RECORDATORIO = "recordatorio";
}
