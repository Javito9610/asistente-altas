package es.amagna.asistente.manos;

/**
 * Las manos del agente: lo único que toca el ordenador. No deciden nada; hacen al pie de la letra lo que dice Claude.
 * Todas las coordenadas están en píxeles de la captura (las que ve Claude); la traducción a la pantalla es cosa de cada
 * implementación (Escala).
 */
public interface Manos {

    /** Captura de la pantalla en PNG, ya al tamaño que ve Claude. */
    byte[] captura();

    /** Una zona de la pantalla a más resolución (para leer letra pequeña). Región en coordenadas de la captura. */
    byte[] zoom(int x0, int y0, int x1, int y1);

    Escala escala();

    void mover(int x, int y);

    /** boton: 1 izquierdo, 2 central, 3 derecho. veces: 1, 2 (doble) o 3 (triple). modificadores: «ctrl», «shift+alt»… o null. */
    void clic(Integer x, Integer y, int boton, int veces, String modificadores);

    void arrastrar(int x0, int y0, int x1, int y1, String modificadores);

    void bajarBotonIzquierdo();

    void subirBotonIzquierdo();

    /** Escribe cualquier texto (ñ, acentos, símbolos) tal cual. */
    void escribir(String texto);

    void tecla(String combinacion, int repeticiones);

    void mantenerTecla(String combinacion, double segundos);

    void rueda(String direccion, int cantidad, Integer x, Integer y, String modificadores);

    void esperar(double segundos);

    /** Dónde está el ratón, en coordenadas de la captura. */
    int[] posicion();

    /**
     * Lee el texto EXACTO del campo que tiene el foco (Ctrl+A, Ctrl+C y el portapapeles, que luego se deja como estaba).
     * Para comprobar letra a letra lo escrito, sin depender de lo que se ve en una captura.
     */
    default String leerCampo() {
        throw new UnsupportedOperationException("Estas manos no saben leer campos");
    }
}
