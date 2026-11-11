package es.amagna.asistente.manos;

/**
 * Traduce entre la pantalla de Windows y la imagen que ve Claude.
 *
 * Claude da los clics en píxeles de la captura que recibe. La captura se reduce (para que lea bien y gaste poco) y Windows,
 * además, puede tener la pantalla al 125 % o 150 %. Aquí se hace la cuenta en un solo sitio para que el clic caiga
 * EXACTAMENTE donde Claude ha dicho:
 *   punto de Claude  ÷ factor  =  punto lógico de Windows (el que usa el robot de Java para mover el ratón).
 *
 * Tamaño de la captura: como mucho 1280 × 720, el que la guía de Anthropic da como base para la mejor precisión. Nunca se
 * amplía.
 */
public record Escala(int anchoLogico, int altoLogico, int anchoImagen, int altoImagen, double factor) {

    public static final int ANCHO_MAX = 1280;
    public static final int ALTO_MAX = 720;

    public static Escala para(int anchoLogico, int altoLogico) {
        return para(anchoLogico, altoLogico, ANCHO_MAX, ALTO_MAX);
    }

    public static Escala para(int anchoLogico, int altoLogico, int anchoMax, int altoMax) {
        double f = Math.min(1.0, Math.min((double) anchoMax / anchoLogico, (double) altoMax / altoLogico));
        int w = (int) Math.round(anchoLogico * f);
        int h = (int) Math.round(altoLogico * f);
        return new Escala(anchoLogico, altoLogico, w, h, f);
    }

    /** Punto de Claude (en la captura) → punto lógico de Windows, dentro de la pantalla. */
    public int[] aPantalla(int x, int y) {
        if (x < 0 || y < 0 || x >= anchoImagen || y >= altoImagen) {
            throw new IllegalArgumentException("Coordenada fuera de la captura: (" + x + ", " + y + ") en " + anchoImagen + "×" + altoImagen);
        }
        int px = (int) Math.round((x + 0.5) / factor - 0.5);
        int py = (int) Math.round((y + 0.5) / factor - 0.5);
        return new int[] {Math.min(anchoLogico - 1, Math.max(0, px)), Math.min(altoLogico - 1, Math.max(0, py))};
    }

    /** Punto lógico de Windows → punto en la captura (para decirle a Claude dónde está el ratón). */
    public int[] aImagen(int px, int py) {
        return new int[] {(int) Math.round((px + 0.5) * factor - 0.5), (int) Math.round((py + 0.5) * factor - 0.5)};
    }
}
