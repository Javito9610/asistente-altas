package es.amagna.asistente.jarvis;

import java.util.Map;

/**
 * Un encargo y todo lo que se sabe de él. Es lo que se guarda en disco: si el programa se apaga, al volver sabe qué quedaba.
 * estado: pendiente · esperando-confirmacion · en-curso · hecho · fallo · rechazado
 */
public record Trabajo(String id, String origen, String quien, String peticion, String tarea, Map<String, String> params,
        String estado, String resultado, long creado, long terminado) {

    public static final String PENDIENTE = "pendiente";
    public static final String ESPERANDO = "esperando-confirmacion";
    public static final String EN_CURSO = "en-curso";
    public static final String HECHO = "hecho";
    public static final String FALLO = "fallo";
    public static final String RECHAZADO = "rechazado";

    public Trabajo con(String estadoNuevo, String resultadoNuevo) {
        boolean fin = HECHO.equals(estadoNuevo) || FALLO.equals(estadoNuevo) || RECHAZADO.equals(estadoNuevo);
        return new Trabajo(id, origen, quien, peticion, tarea, params, estadoNuevo, resultadoNuevo == null ? resultado : resultadoNuevo,
                creado, fin ? System.currentTimeMillis() : terminado);
    }

    public boolean acabado() {
        return HECHO.equals(estado) || FALLO.equals(estado) || RECHAZADO.equals(estado);
    }
}
