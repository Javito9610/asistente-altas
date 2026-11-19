package es.amagna.asistente.estados;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lo que haría una persona al ver la pantalla revuelta, antes de dar nada por perdido: devolver el teclado a MN
 * cuando se lo ha quedado otra ventana (el recortes de pantalla, el bloc de notas que MN abre con cada documento
 * importado, la consola) y cerrar los visores que MN ha abierto con NUESTROS documentos.
 * Es una lista cerrada y segura: no cierra nada que no se reconozca por su nombre, nunca toca un documento con
 * cambios sin guardar (título con «*») y no sabe de datos de nadie. Cada cosa que hace se devuelve para el registro.
 */
public final class Recuperador {
    /** Lo que el recuperador puede hacer sobre las ventanas (en los tests, un mundo de mentira). */
    public interface Mundo {
        boolean activar(String titulo);

        boolean cerrar(String titulo);
    }

    private static final Pattern LINEA = Pattern.compile("^- «([^»]+)» \\(([^)]*)\\)(.*)$");
    private static final Pattern DENTRO = Pattern.compile("diálogos abiertos dentro: «([^»]+)»");

    private Recuperador() {
    }

    private static boolean esMn(String programa) {
        return "GestorConsultas".equals(programa) || "MNprogram".equals(programa);
    }

    /** Qué se ha hecho (vacío si no hacía falta nada). */
    public static List<String> revisar(String ventanas, Map<String, String> params, Mundo m) {
        List<String> hecho = new ArrayList<>();
        if (ventanas == null) {
            return hecho;
        }
        List<String> nombres = new ArrayList<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getKey().startsWith("adjunto") && e.getValue() != null && !e.getValue().isBlank()
                    && !e.getKey().equals("adjuntos_total") && !e.getKey().equals("adjuntos_inicios")) {
                String f = e.getValue().replace('\\', '/');
                f = f.substring(f.lastIndexOf('/') + 1);
                int p = f.lastIndexOf('.');
                nombres.add((p > 0 ? f.substring(0, p) : f).toLowerCase(Locale.ROOT));
            }
        }
        String delante = null;
        String delanteProg = "";
        String destino = null; // a quién se le devuelve el teclado: el diálogo de MN de delante, o su ventana
        for (String linea : ventanas.split("\\R")) {
            Matcher l = LINEA.matcher(linea);
            if (!l.find()) {
                continue;
            }
            String titulo = l.group(1);
            String prog = l.group(2);
            String resto = l.group(3);
            if (linea.contains("[delante, con el foco]")) {
                delante = titulo;
                delanteProg = prog;
            }
            if (esMn(prog) && destino == null && !resto.contains("[minimizada]")) {
                Matcher d = DENTRO.matcher(resto);
                destino = d.find() ? d.group(1) : titulo;
            }
            if ("Notepad".equals(prog) && !titulo.startsWith("*") && !resto.contains("[minimizada]")) {
                String t = titulo.toLowerCase(Locale.ROOT);
                for (String n : nombres) {
                    if (t.contains(n) && m.cerrar(titulo)) {
                        hecho.add("cerrado el visor «" + titulo + "» que MN abrió con un documento nuestro");
                        break;
                    }
                }
            }
        }
        if (delante != null && !esMn(delanteProg) && destino != null) {
            if (m.activar(destino)) {
                hecho.add("el teclado lo tenía «" + delante + "» (" + delanteProg + "): devuelto a «" + destino + "»");
            }
        }
        return hecho;
    }
}
