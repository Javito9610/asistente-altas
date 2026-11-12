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
}
