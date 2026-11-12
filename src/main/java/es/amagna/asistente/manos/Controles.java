package es.amagna.asistente.manos;

import com.sun.jna.Pointer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Los controles de un programa, por dentro (Uia): verlos, leerlos, escribir en ellos y pulsarlos POR SU NOMBRE, sin
 * coordenadas ni teclear a ciegas. Es la forma profesional de manejar un programa de Windows, y la que hará que las
 * altas en MN se rellenen campo a campo y se puedan comprobar una a una.
 *
 * Un control se busca con un «apodo»: un trozo de su nombre, y si hace falta su tipo delante, separado por dos puntos:
 *   «Guardar»            el primer control que se llame así
 *   «boton:Guardar»      solo botones
 *   «campo:NIF»          solo cuadros de texto
 * Si el apodo vale para varios, se dice y no se toca nada: antes parar que equivocarse.
 *
 * @package Amagna\Altas
 */
public final class Controles {
    /** Cuántos controles se enseñan como mucho: una ventana de gestión puede tener cientos. */
    private static final int TOPE = 300;

    private final VentanasNativas ventanas;

    public Controles(VentanasNativas ventanas) {
        this.ventanas = ventanas;
    }

    public record Encontrado(Uia.Elemento elemento, String descripcion) {
    }

    /** Lo que el asistente «ve por dentro» de una ventana, en texto. */
    public String arbol(String tituloVentana, int profundidad) {
        Pointer hwnd = ventanas.handleDe(tituloVentana);
        if (hwnd == null) {
            return "No hay ninguna ventana cuyo título contenga «" + tituloVentana + "».";
        }
        Uia.Elemento raiz = Uia.deVentana(hwnd);
        if (raiz == null) {
            return "No se pueden leer los controles de esa ventana" + (Uia.noDisponible().isEmpty() ? "" : " (" + Uia.noDisponible() + ")")
                    + ". Con este programa hay que mirar la pantalla y usar el ratón y el teclado.";
        }
        StringBuilder b = new StringBuilder("Controles de «" + Win32.titulo(hwnd) + "»:\n");
        int[] cuenta = {0};
        try {
            pintar(raiz, b, "", Math.max(1, profundidad), cuenta);
        } finally {
            raiz.close();
        }
        if (cuenta[0] >= TOPE) {
            b.append("… (hay más; busca uno concreto por su nombre)\n");
        }
        return b.toString().strip();
    }

    private void pintar(Uia.Elemento e, StringBuilder b, String sangria, int quedan, int[] cuenta) {
        for (Uia.Elemento h : e.hijos()) {
            try {
                if (cuenta[0]++ >= TOPE) {
                    return;
                }
                String nombre = h.nombre();
                String valor = h.valor();
                boolean interesa = !nombre.isBlank() || valor != null;
                if (interesa) {
                    b.append(sangria).append("- ").append(h.tipo());
                    if (!nombre.isBlank()) {
                        b.append(" «").append(nombre).append("»");
                    }
                    if (!h.automationId().isBlank() && !h.automationId().equals(nombre)) {
                        b.append(" (id ").append(h.automationId()).append(")");
                    }
                    if (valor != null) {
                        b.append(" = «").append(valor.length() > 120 ? valor.substring(0, 120) + "…" : valor).append("»");
                    }
                    if (!h.activo()) {
                        b.append(" [desactivado]");
                    }
                    b.append('\n');
                }
                if (quedan > 1) {
                    pintar(h, b, interesa ? sangria + "   " : sangria, quedan - 1, cuenta);
                }
            } finally {
                h.close();
            }
        }
    }

    /**
     * Busca un control por su apodo dentro de una ventana. Si hay más de uno, no devuelve ninguno y lo explica: el
     * asistente tiene que afinar el apodo en vez de tocar el que no es.
     */
    public Encontrado buscar(String tituloVentana, String apodo) {
        Pointer hwnd = ventanas.handleDe(tituloVentana);
        if (hwnd == null) {
            return new Encontrado(null, "No hay ninguna ventana cuyo título contenga «" + tituloVentana + "».");
        }
        Uia.Elemento raiz = Uia.deVentana(hwnd);
        if (raiz == null) {
            return new Encontrado(null, "No se pueden leer los controles de esa ventana" + (Uia.noDisponible().isEmpty() ? "" : " (" + Uia.noDisponible() + ")") + ".");
        }
        String tipo = null;
        String nombre = apodo;
        int dosPuntos = apodo.indexOf(':');
        if (dosPuntos > 0) {
            tipo = apodo.substring(0, dosPuntos).trim().toLowerCase(Locale.ROOT);
            nombre = apodo.substring(dosPuntos + 1).trim();
        }
        List<Uia.Elemento> hallados = new ArrayList<>();
        List<String> comoSonLlamados = new ArrayList<>();
        try {
            rebuscar(raiz, tipo, nombre.toLowerCase(Locale.ROOT), 12, hallados, comoSonLlamados);
        } finally {
            raiz.close();
        }
        if (hallados.isEmpty()) {
            return new Encontrado(null, "No hay ningún control que encaje con «" + apodo + "» en esa ventana. Mira los que hay con ver_controles.");
        }
        if (hallados.size() > 1) {
            hallados.forEach(Uia.Elemento::close);
            return new Encontrado(null, "«" + apodo + "» encaja con " + hallados.size() + " controles (" + String.join("; ", comoSonLlamados.subList(0, Math.min(6, comoSonLlamados.size())))
                    + "). Afina el apodo (por ejemplo «boton:Guardar»): no se toca ninguno.");
        }
        return new Encontrado(hallados.get(0), comoSonLlamados.get(0));
    }

    private void rebuscar(Uia.Elemento e, String tipo, String nombre, int quedan, List<Uia.Elemento> hallados, List<String> como) {
        for (Uia.Elemento h : e.hijos()) {
            boolean guardado = false;
            try {
                if (hallados.size() > 8) {
                    return;
                }
                String n = h.nombre();
                String id = h.automationId();
                boolean coincide = n.toLowerCase(Locale.ROOT).contains(nombre) || id.toLowerCase(Locale.ROOT).contains(nombre);
                if (coincide && (tipo == null || h.tipo().equals(tipo)) && h.activo()) {
                    hallados.add(h);
                    como.add(h.tipo() + " «" + (n.isBlank() ? id : n) + "»");
                    guardado = true;
                } else if (quedan > 1) {
                    rebuscar(h, tipo, nombre, quedan - 1, hallados, como);
                }
            } finally {
                if (!guardado) {
                    h.close();
                }
            }
        }
    }

    /** Escribe el valor exacto en un control y lo relee para comprobarlo. */
    public String escribir(String ventana, String apodo, String texto) {
        Encontrado f = buscar(ventana, apodo);
        if (f.elemento() == null) {
            return f.descripcion();
        }
        try (Uia.Elemento e = f.elemento()) {
            e.enfocar();
            if (!e.escribir(texto)) {
                return "El control " + f.descripcion() + " no deja escribir directamente: hazlo con el teclado (haz clic en él, bórralo y escribe).";
            }
            String ahora = e.valor();
            if (ahora == null) {
                return "Escrito en " + f.descripcion() + ", pero no se puede releer para comprobarlo.";
            }
            return ahora.equals(texto) ? "IGUAL: " + f.descripcion() + " vale ahora exactamente «" + ahora + "»."
                    : "DISTINTO: en " + f.descripcion() + " se quería «" + texto + "» y ha quedado «" + ahora + "». Corrígelo.";
        }
    }

    public String leer(String ventana, String apodo) {
        Encontrado f = buscar(ventana, apodo);
        if (f.elemento() == null) {
            return f.descripcion();
        }
        try (Uia.Elemento e = f.elemento()) {
            String v = e.valor();
            return f.descripcion() + (v == null ? " no tiene un valor que leer." : " vale «" + v + "»" + (e.activo() ? "" : " [desactivado]") + ".");
        }
    }

    public String pulsar(String ventana, String apodo) {
        Encontrado f = buscar(ventana, apodo);
        if (f.elemento() == null) {
            return f.descripcion();
        }
        try (Uia.Elemento e = f.elemento()) {
            if (e.pulsar()) {
                return "Pulsado " + f.descripcion() + ". Comprueba con una captura o con ver_controles qué ha pasado.";
            }
            if (e.desplegar()) {
                return "Desplegado " + f.descripcion() + ".";
            }
            int[] r = e.recuadro();
            return r == null ? "El control " + f.descripcion() + " no se deja pulsar por dentro y no se sabe dónde está: usa el ratón mirando la captura."
                    : "El control " + f.descripcion() + " no se deja pulsar por dentro. Está en la pantalla real en x=" + r[0] + ", y=" + r[1]
                      + ", de " + r[2] + "×" + r[3] + " píxeles: haz clic ahí con el ratón.";
        }
    }
}
