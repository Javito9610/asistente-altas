package es.amagna.asistente.estados;

import es.amagna.asistente.cerebro.Receta;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dónde estoy: mira las ventanas abiertas y dice en qué estado del mapa está el programa. */
public final class Reconocedor {
    private static final Pattern LINEA = Pattern.compile("^- «([^»]*)»(.*)$");

    private Reconocedor() {
    }

    /** Una ventana de la lista que da {@code ventanas_abiertas}. */
    public record Ventana(String titulo, boolean bloqueada) {
    }

    public static List<Ventana> leer(String abiertas) {
        List<Ventana> v = new ArrayList<>();
        if (abiertas == null) {
            return v;
        }
        for (String l : abiertas.split("\\R")) {
            Matcher m = LINEA.matcher(l.strip());
            if (m.find()) {
                String resto = m.group(2);
                // Un diálogo que cuelga de otra ventana no sale en su propia línea, sino en la de su dueña: delante de ella.
                int i = resto.indexOf("diálogos abiertos dentro:");
                if (i >= 0) {
                    Matcher d = Pattern.compile("«([^»]*)»").matcher(resto.substring(i));
                    while (d.find()) {
                        v.add(new Ventana(d.group(1), false));
                    }
                }
                v.add(new Ventana(m.group(1), resto.contains("BLOQUEADA") || resto.contains("minimizada")));
            }
        }
        return v;
    }

    /**
     * El estado. Se mira de delante hacia atrás y gana la primera ventana conocida que no esté tapada por un diálogo.
     * Si no hay ninguna conocida pero sí la principal del programa: «inicio». Si no hay ni eso: «fuera».
     */
    public static String estado(Mapa mapa, String abiertas) {
        return estado(mapa, abiertas, t -> "");
    }

    /** Igual, pero un estado puede pedir que la ventana contenga cierto texto (no es lo mismo «Atención: ¿salir?» que otro aviso). */
    public static String estado(Mapa mapa, String abiertas, java.util.function.Function<String, String> textoDe) {
        List<Ventana> ventanas = leer(abiertas);
        for (Ventana w : ventanas) {
            if (w.bloqueada()) {
                continue;
            }
            String n = Receta.norm(w.titulo());
            for (Mapa.Estado e : mapa.estados()) {
                if (coincide(n, e.ventana()) && (e.contiene().isBlank()
                        || llano(textoDe.apply(w.titulo())).contains(llano(e.contiene())))) {
                    return e.nombre();
                }
            }
        }
        for (Ventana w : ventanas) {
            if (!mapa.principal().isBlank() && w.titulo().contains(mapa.principal()) && !w.titulo().contains("Explorador")) {
                return "inicio";
            }
        }
        return "fuera";
    }

    static String llano(String t) {
        return java.text.Normalizer.normalize(t == null ? "" : t, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** Igual, o «Cargando...» vale para «Cargando…» y «Cargando datos...»: un título que acaba en puntos suspensivos es un prefijo. */
    static boolean coincide(String titulo, String patron) {
        if (titulo.equals(patron)) {
            return true;
        }
        String base = patron.replaceAll("(\\.\\.\\.|…)$", "").strip();
        return !base.equals(patron) && !base.isEmpty() && titulo.startsWith(base);
    }

    /** El título real de la ventana (con su número) que está en ese estado, o null. */
    public static String tituloDe(Mapa mapa, String abiertas, String estado) {
        for (Mapa.Estado e : mapa.estados()) {
            if (e.nombre().equals(estado)) {
                for (Ventana w : leer(abiertas)) {
                    if (!w.bloqueada() && coincide(Receta.norm(w.titulo()), e.ventana())) {
                        return w.titulo();
                    }
                }
            }
        }
        return null;
    }
}
