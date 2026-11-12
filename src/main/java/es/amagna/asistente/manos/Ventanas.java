package es.amagna.asistente.manos;

/**
 * Las ventanas de Windows: abrir un programa, ponerlo delante y saber cuál está delante. Sirve para que el teclado
 * vaya SIEMPRE a la ventana que toca (primera prueba real, 12/11: los Ctrl+N iban a la consola y no al Bloc de notas).
 */
public interface Ventanas {

    /** Abre un programa (ruta o nombre, p. ej. «notepad.exe») con sus argumentos. */
    void abrir(String programa, java.util.List<String> argumentos);

    /** Pone delante la ventana cuyo título contiene el texto. true si lo ha conseguido. */
    boolean activar(String titulo);

    /** Título de la ventana que está delante (la que recibe el teclado). */
    String activa();

    /**
     * Todas las ventanas abiertas, también las que están detrás o minimizadas y los diálogos que tienen dentro
     * (lo que una captura no enseña). Texto listo para Claude.
     */
    default String abiertas() {
        throw new UnsupportedOperationException("No disponible");
    }

    /** El control que tiene el foco del teclado, leído de Windows (no de la imagen): nombre, tipo, valor exacto y ventana. */
    default Campo campoConFoco() {
        throw new UnsupportedOperationException("No disponible");
    }

    /**
     * Cancela un diálogo abierto (como pulsar su aspa o Cancelar: no guarda ni confirma nada). Busca por título entre
     * los diálogos; devuelve el título del que ha cerrado, o null si no había ninguno.
     */
    default String cancelarDialogo(String titulo) {
        throw new UnsupportedOperationException("No disponible");
    }

    /** valor == null: ese control no deja leer su texto. */
    record Campo(String nombre, String tipo, String valor, String ventana) {
    }
}
