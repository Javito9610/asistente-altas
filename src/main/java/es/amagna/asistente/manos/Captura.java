package es.amagna.asistente.manos;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * El dibujo de UNA ventana, pidiéndoselo a la ventana.
 *
 * <h2>Por qué hace falta, además de la foto de pantalla</h2>
 * La foto de pantalla ({@link ManosRobot#captura()}) copia lo que hay en el monitor. Eso es exactamente lo que no
 * existe en dos situaciones que van a ser el día a día en el despacho:
 * <ul>
 *   <li><b>Un servidor al que se entra por escritorio remoto y del que te desconectas.</b> Windows deja la sesión
 *       viva pero deja de dibujarla: la foto sale negra.</li>
 *   <li><b>Una ventana tapada por otra</b>, o minimizada: en el monitor no está, y la foto enseña lo que la tapa.</li>
 * </ul>
 * Aquí no se copia el monitor. Se le dice a la propia ventana «dibújate en este lienzo» ({@code PrintWindow}), y ella
 * lo hace igual esté a la vista o no. Es la misma razón por la que el asistente maneja los controles por dentro y no
 * a golpe de ratón: lo que importa está en el programa, no en la pantalla.
 *
 * <p>{@code PW_RENDERFULLCONTENT} (2) hace que también salgan las ventanas que se dibujan con la tarjeta gráfica
 * (las de WPF, Chrome, Office…). Sin él, esas salen en blanco. MN es WinForms y no lo necesita, pero el asistente va
 * a ver más programas que MN.
 *
 * <h2>Lo que nunca hace</h2>
 * No devuelve un lienzo vacío como si fuera una foto. Una ventana que se niega a dibujarse da un lienzo de un solo
 * color, y mandárselo a la IA como «lo que hay en pantalla» la lleva a decidir sobre nada. Si sale uniforme, se dice
 * ({@link Resultado#enBlanco()}) y quien lo use sabe que no ha visto nada.
 *
 * @package Amagna\Manos
 */
public final class Captura {
    private Captura() {
    }

    private static final int PW_RENDERFULLCONTENT = 2;
    private static final int BI_RGB = 0;
    private static final int DIB_RGB_COLORS = 0;
    /** Más grande que esto no es una ventana, es un error de lectura del tamaño. */
    private static final int LADO_MAX = 16384;

    public interface Gdi32 extends StdCallLibrary {
        Gdi32 I = Native.load("gdi32", Gdi32.class);

        Pointer CreateCompatibleDC(Pointer hdc);

        Pointer CreateCompatibleBitmap(Pointer hdc, int ancho, int alto);

        Pointer SelectObject(Pointer hdc, Pointer objeto);

        boolean DeleteObject(Pointer objeto);

        boolean DeleteDC(Pointer hdc);

        int GetDIBits(Pointer hdc, Pointer bitmap, int primeraLinea, int lineas, Pointer bits, BitmapInfo info, int uso);
    }

    /** BITMAPINFO con la cabecera y sitio para la paleta (que en 32 bits no se usa pero la estructura la reserva). */
    @Structure.FieldOrder({"biSize", "biWidth", "biHeight", "biPlanes", "biBitCount", "biCompression", "biSizeImage",
            "biXPelsPerMeter", "biYPelsPerMeter", "biClrUsed", "biClrImportant", "paleta"})
    public static class BitmapInfo extends Structure {
        public int biSize = 40;
        public int biWidth;
        public int biHeight;
        public short biPlanes = 1;
        public short biBitCount = 32;
        public int biCompression = BI_RGB;
        public int biSizeImage;
        public int biXPelsPerMeter;
        public int biYPelsPerMeter;
        public int biClrUsed;
        public int biClrImportant;
        public int paleta;
    }

    /** Lo que se ha podido ver de la ventana. */
    public record Resultado(BufferedImage imagen, int ancho, int alto, boolean enBlanco, String problema) {
        public boolean valido() {
            return imagen != null && problema == null;
        }

        /** PNG listo para mandar, o null si no hay nada que mandar. */
        public byte[] png() {
            return aPng(imagen);
        }

        /**
         * PNG que cabe en el recuadro dado, sin deformar. Una ventana a pantalla completa en un monitor 4K son
         * 3840x2160 píxeles y varios megas; a Claude le basta con lo mismo que ve de la pantalla entera.
         */
        public byte[] pngQueCabe(int anchoMax, int altoMax) {
            if (imagen == null) {
                return null;
            }
            return aPng(reducida(imagen, anchoMax, altoMax));
        }
    }

    /** Lo mismo que {@link #deVentana}, pero para cuando ni siquiera se encuentra la ventana. */
    public static Resultado noHay(String problema) {
        return fallo(problema);
    }

    /** Cabe en el recuadro, conservando la proporción. Si ya cabe, se devuelve tal cual (nunca se agranda). */
    public static BufferedImage reducida(BufferedImage origen, int anchoMax, int altoMax) {
        double f = Math.min(1.0, Math.min((double) anchoMax / origen.getWidth(), (double) altoMax / origen.getHeight()));
        if (f >= 1.0) {
            return origen;
        }
        int w = Math.max(1, (int) Math.round(origen.getWidth() * f));
        int h = Math.max(1, (int) Math.round(origen.getHeight() * f));
        BufferedImage r = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = r.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(origen, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return r;
    }

    private static byte[] aPng(BufferedImage img) {
        if (img == null) {
            return null;
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** Dibuja la ventana. Nunca lanza: si no se puede, el resultado dice por qué. */
    public static Resultado deVentana(Pointer hwnd) {
        if (hwnd == null || !Win32.User32.I.IsWindow(hwnd)) {
            return fallo("La ventana ya no existe.");
        }
        Win32.RECT rc = new Win32.RECT();
        if (!Win32.User32.I.GetWindowRect(hwnd, rc)) {
            return fallo("Windows no da el tamaño de la ventana.");
        }
        int ancho = rc.right - rc.left;
        int alto = rc.bottom - rc.top;
        if (ancho <= 0 || alto <= 0 || ancho > LADO_MAX || alto > LADO_MAX) {
            // Minimizada: Windows da 160x28 o coordenadas negativas enormes. No hay nada que dibujar.
            return fallo("La ventana no tiene un tamaño que dibujar (" + ancho + "x" + alto + "); ¿está minimizada?");
        }
        Pointer dcVentana = null;
        Pointer dc = null;
        Pointer lienzo = null;
        Pointer anterior = null;
        try {
            dcVentana = Win32.User32.I.GetWindowDC(hwnd);
            if (dcVentana == null) {
                return fallo("Windows no da el contexto de dibujo de la ventana.");
            }
            dc = Gdi32.I.CreateCompatibleDC(dcVentana);
            lienzo = Gdi32.I.CreateCompatibleBitmap(dcVentana, ancho, alto);
            if (dc == null || lienzo == null) {
                return fallo("No se ha podido crear el lienzo de " + ancho + "x" + alto + ".");
            }
            anterior = Gdi32.I.SelectObject(dc, lienzo);
            if (!Win32.User32.I.PrintWindow(hwnd, dc, PW_RENDERFULLCONTENT)) {
                return fallo("La ventana se ha negado a dibujarse.");
            }
            BitmapInfo info = new BitmapInfo();
            info.biWidth = ancho;
            info.biHeight = -alto; // negativo: las filas vienen de arriba abajo, como en una imagen
            info.biSizeImage = ancho * alto * 4;
            Memory bits = new Memory((long) ancho * alto * 4);
            // GetDIBits exige que el lienzo NO esté seleccionado en ningún contexto mientras se lee.
            Gdi32.I.SelectObject(dc, anterior);
            anterior = null;
            int lineas = Gdi32.I.GetDIBits(dc, lienzo, 0, alto, bits, info, DIB_RGB_COLORS);
            if (lineas != alto) {
                return fallo("Solo se han podido leer " + lineas + " de " + alto + " filas.");
            }
            int[] pixeles = new int[ancho * alto];
            bits.read(0, pixeles, 0, pixeles.length);
            BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
            for (int i = 0; i < pixeles.length; i++) {
                pixeles[i] &= 0x00FFFFFF; // el cuarto byte no es transparencia: es relleno, y suele venir a 0
            }
            img.setRGB(0, 0, ancho, alto, pixeles, 0, ancho);
            return new Resultado(img, ancho, alto, esUniforme(pixeles), null);
        } catch (Throwable e) {
            return fallo("No se ha podido dibujar la ventana: " + e);
        } finally {
            if (dc != null && anterior != null) {
                Gdi32.I.SelectObject(dc, anterior);
            }
            if (lienzo != null) {
                Gdi32.I.DeleteObject(lienzo);
            }
            if (dc != null) {
                Gdi32.I.DeleteDC(dc);
            }
            if (dcVentana != null) {
                Win32.User32.I.ReleaseDC(hwnd, dcVentana);
            }
        }
    }

    /**
     * ¿Es de un solo color? Una ventana de verdad siempre tiene al menos fondo y texto; una que se ha negado a
     * dibujarse sale toda negra o toda blanca.
     *
     * <p>Se miran TODOS los píxeles, con salida en cuanto aparece uno distinto. Muestrear un reparto de unos
     * cuantos miles parecía más barato, pero una ventana casi toda gris con una línea de texto pequeña se quedaba
     * sin ningún punto de la muestra sobre el texto, y se declaraba en blanco una ventana que no lo estaba. Recorrer
     * ocho millones de enteros (una pantalla 4K) son unos pocos milisegundos; equivocarse aquí es decirle a la IA
     * «no veo nada» con la pantalla delante.
     */
    public static boolean esUniforme(int[] pixeles) {
        if (pixeles == null || pixeles.length == 0) {
            return true;
        }
        int primero = pixeles[0] & 0x00FFFFFF;
        for (int i = 1; i < pixeles.length; i++) {
            if ((pixeles[i] & 0x00FFFFFF) != primero) {
                return false;
            }
        }
        return true;
    }

    private static Resultado fallo(String problema) {
        return new Resultado(null, 0, 0, true, problema);
    }
}
