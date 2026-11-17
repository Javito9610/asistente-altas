package es.amagna.asistente.manos;

import java.awt.image.BufferedImage;

/**
 * Encontrar un botón o un campo por su dibujo, no por su posición.
 *
 * <p>Un clic «a 357 px del borde» deja de valer si MN cambia de diseño, si la ventana sale en otro sitio o si la
 * pantalla va a otra escala. El dibujo del botón, en cambio, sigue siendo el mismo. Se guarda un recorte pequeño
 * alrededor del punto donde se hizo clic (cuando la cosa salió bien) y luego se busca ese recorte en el dibujo
 * actual de la ventana. Java puro: sin IA y en milisegundos.
 *
 * <p>La búsqueda es prudente: primero cerca de donde se esperaba, y solo si no está allí, en toda la ventana. Y solo
 * da una posición si es clara: parecido casi exacto y sin otro sitio que se le parezca igual. Si duda, dice que no
 * lo ha encontrado (y quien llama usa la posición de siempre o llama a la IA); nunca se inventa un sitio.
 */
public final class Ancla {
    private Ancla() {
    }

    /** Diferencia media por píxel (0-255, en gris) hasta la que se acepta como «es el mismo dibujo». */
    public static final double UMBRAL = 6.0;
    /** El segundo mejor sitio (lejos del primero) tiene que ser al menos así de peor, o se considera ambiguo. */
    public static final double MARGEN = 4.0;
    /** Desviación mínima del recorte: uno liso (todo gris) casaría en cualquier parte. */
    public static final double CONTRASTE_MIN = 12.0;
    public static final int ANCHO = 56;
    public static final int ALTO = 36;
    /** Hasta dónde se mira «cerca» de lo esperado, en píxeles. */
    public static final int RADIO = 90;

    /** Dónde está el centro del recorte en la ventana, y lo bien que casa (0 = idéntico). */
    public record Hallazgo(int x, int y, double diferencia) {
    }

    /** Recorte centrado en (cx, cy), pegado al borde si no cabe. Null si la imagen es más pequeña que el recorte. */
    public static BufferedImage recortar(BufferedImage ventana, int cx, int cy) {
        if (ventana == null || ventana.getWidth() < ANCHO || ventana.getHeight() < ALTO) {
            return null;
        }
        int x0 = Math.max(0, Math.min(ventana.getWidth() - ANCHO, cx - ANCHO / 2));
        int y0 = Math.max(0, Math.min(ventana.getHeight() - ALTO, cy - ALTO / 2));
        BufferedImage r = new BufferedImage(ANCHO, ALTO, BufferedImage.TYPE_INT_RGB);
        r.setRGB(0, 0, ANCHO, ALTO, ventana.getRGB(x0, y0, ANCHO, ALTO, null, 0, ANCHO), 0, ANCHO);
        return r;
    }

    /** Dónde cae el punto de clic dentro del recorte (lo normal es el centro; pegado al borde cambia). */
    public static int[] puntoEnRecorte(BufferedImage ventana, int cx, int cy) {
        int x0 = Math.max(0, Math.min(ventana.getWidth() - ANCHO, cx - ANCHO / 2));
        int y0 = Math.max(0, Math.min(ventana.getHeight() - ALTO, cy - ALTO / 2));
        return new int[] {cx - x0, cy - y0};
    }

    /** ¿Tiene dibujo suficiente para reconocerse? */
    public static boolean hayContraste(BufferedImage r) {
        if (r == null) {
            return false;
        }
        int n = r.getWidth() * r.getHeight();
        double suma = 0;
        double suma2 = 0;
        for (int y = 0; y < r.getHeight(); y++) {
            for (int x = 0; x < r.getWidth(); x++) {
                int g = gris(r.getRGB(x, y));
                suma += g;
                suma2 += (double) g * g;
            }
        }
        double media = suma / n;
        return Math.sqrt(Math.max(0, suma2 / n - media * media)) >= CONTRASTE_MIN;
    }

    /**
     * Busca el recorte en la ventana. {@code esperadoX/Y} es la esquina superior izquierda donde se esperaba el
     * recorte (la posición de siempre). Devuelve la esquina donde está, o null si no se encuentra con claridad.
     */
    public static Hallazgo buscar(BufferedImage ventana, BufferedImage recorte, int esperadoX, int esperadoY) {
        if (ventana == null || recorte == null || !hayContraste(recorte)
                || ventana.getWidth() < recorte.getWidth() || ventana.getHeight() < recorte.getHeight()) {
            return null;
        }
        int[][] v = grises(ventana);
        int[][] t = grises(recorte);
        int maxX = ventana.getWidth() - recorte.getWidth();
        int maxY = ventana.getHeight() - recorte.getHeight();
        // 1) cerca de lo esperado
        Hallazgo cerca = mejor(v, t, Math.max(0, esperadoX - RADIO), Math.min(maxX, esperadoX + RADIO),
                Math.max(0, esperadoY - RADIO), Math.min(maxY, esperadoY + RADIO));
        if (cerca != null && cerca.diferencia() <= UMBRAL && claro(v, t, cerca, maxX, maxY)) {
            return cerca;
        }
        // 2) en toda la ventana
        Hallazgo todo = mejor(v, t, 0, maxX, 0, maxY);
        if (todo != null && todo.diferencia() <= UMBRAL && claro(v, t, todo, maxX, maxY)) {
            return todo;
        }
        return null;
    }

    /** Sin otro sitio, a más de medio recorte de distancia, que case casi igual de bien. */
    private static boolean claro(int[][] v, int[][] t, Hallazgo h, int maxX, int maxY) {
        double otro = Double.MAX_VALUE;
        int ax = t[0].length / 2;
        int ay = t.length / 2;
        for (int y = 0; y <= maxY; y += 1) {
            for (int x = 0; x <= maxX; x += 1) {
                if (Math.abs(x - h.x()) < ax && Math.abs(y - h.y()) < ay) {
                    continue;
                }
                double d = diferencia(v, t, x, y, Math.min(otro, UMBRAL + MARGEN));
                if (d < otro) {
                    otro = d;
                }
            }
        }
        return otro - h.diferencia() >= MARGEN;
    }

    private static Hallazgo mejor(int[][] v, int[][] t, int x0, int x1, int y0, int y1) {
        double mejor = Double.MAX_VALUE;
        int bx = -1;
        int by = -1;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double d = diferencia(v, t, x, y, mejor);
                if (d < mejor) {
                    mejor = d;
                    bx = x;
                    by = y;
                }
            }
        }
        return bx < 0 ? null : new Hallazgo(bx, by, mejor);
    }

    /** Diferencia media por píxel; corta en cuanto ya no puede mejorar el tope. */
    private static double diferencia(int[][] v, int[][] t, int x0, int y0, double tope) {
        int alto = t.length;
        int ancho = t[0].length;
        long max = tope >= 255 ? Long.MAX_VALUE : (long) (tope * alto * ancho) + 1;
        long suma = 0;
        for (int y = 0; y < alto; y++) {
            int[] fv = v[y0 + y];
            int[] ft = t[y];
            for (int x = 0; x < ancho; x++) {
                suma += Math.abs(fv[x0 + x] - ft[x]);
            }
            if (suma > max) {
                return Double.MAX_VALUE;
            }
        }
        return (double) suma / (alto * ancho);
    }

    private static int[][] grises(BufferedImage im) {
        int[][] g = new int[im.getHeight()][im.getWidth()];
        for (int y = 0; y < im.getHeight(); y++) {
            for (int x = 0; x < im.getWidth(); x++) {
                g[y][x] = gris(im.getRGB(x, y));
            }
        }
        return g;
    }

    private static int gris(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }
}
