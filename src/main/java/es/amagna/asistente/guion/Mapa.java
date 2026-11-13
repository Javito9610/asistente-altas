package es.amagna.asistente.guion;

import es.amagna.asistente.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * El mapa de un programa: cómo se llama cada campo y cada botón de cada pantalla.
 *
 * Es la pieza que convierte al asistente en una herramienta de verdad. MN se cartografía UNA VEZ y a partir de ahí
 * rellenar una ficha deja de ser «que la inteligencia artificial se busque la vida cada noche» y pasa a ser un guion
 * que se ejecuta igual siempre, en segundos y sin gastar un céntimo. La IA solo entra cuando algo no cuadra con el
 * mapa, que es justo cuando hace falta alguien que piense.
 *
 * Cada control lleva VARIAS formas de encontrarlo, de la mejor a la peor:
 * <pre>
 * {
 *   "nombre": "Ficha de cliente de MN",
 *   "ventana": "MN program",
 *   "programa": {"ejecutable": "C:\\…\\mn.exe", "argumentos": []},
 *   "controles": {
 *     "nif":     ["#txtNif", "campo junto a NIF", "campo:NIF"],
 *     "guardar": ["#btnGuardar", "boton:Guardar"]
 *   }
 * }
 * </pre>
 * Si la primera deja de valer (le cambian el texto, cambian de versión), se prueba la siguiente y queda avisado en el
 * parte: eso es lo que hace que no se rompa el día que MN se actualice.
 *
 * @package Amagna\Altas
 */
public record Mapa(String nombre, String ventana, String ejecutable, List<String> argumentos, Map<String, List<String>> controles,
        Path entradaMapa, Path entradaGuion) {

    @SuppressWarnings("unchecked")
    public static Mapa leer(Path fichero) throws IOException {
        Map<String, Object> m = Json.objeto(Files.readString(fichero, StandardCharsets.UTF_8));
        String ventana = texto(m.get("ventana"));
        if (ventana.isBlank()) {
            throw new IllegalArgumentException("El mapa " + fichero.getFileName() + " no dice en qué «ventana» está.");
        }
        String exe = "";
        List<String> args = new ArrayList<>();
        if (m.get("programa") instanceof Map<?, ?> p) {
            exe = texto(p.get("ejecutable"));
            if (p.get("argumentos") instanceof List<?> l) {
                l.forEach(a -> args.add(String.valueOf(a)));
            }
        }
        Map<String, List<String>> controles = new LinkedHashMap<>();
        if (m.get("controles") instanceof Map<?, ?> c) {
            for (Map.Entry<?, ?> e : c.entrySet()) {
                List<String> apodos = new ArrayList<>();
                if (e.getValue() instanceof List<?> l) {
                    l.forEach(a -> apodos.add(String.valueOf(a)));
                } else {
                    apodos.add(String.valueOf(e.getValue()));
                }
                if (apodos.isEmpty()) {
                    throw new IllegalArgumentException("El control «" + e.getKey() + "» del mapa no dice cómo encontrarlo.");
                }
                controles.put(String.valueOf(e.getKey()), List.copyOf(apodos));
            }
        }
        if (controles.isEmpty()) {
            throw new IllegalArgumentException("El mapa " + fichero.getFileName() + " no tiene ningún control.");
        }
        // La pantalla de entrada: su propio mapa y el guion que la rellena. Se apuntan aquí para que una sola orden
        // («ábrelo y déjalo listo») sepa también volver a entrar si el programa pide usuario y contraseña.
        Path base = fichero.toAbsolutePath().getParent();
        Path eMapa = null;
        Path eGuion = null;
        if (m.get("entrada") instanceof Map<?, ?> e) {
            eMapa = e.get("mapa") == null ? null : base.resolve(String.valueOf(e.get("mapa"))).normalize();
            eGuion = e.get("guion") == null ? null : base.resolve(String.valueOf(e.get("guion"))).normalize();
        }
        return new Mapa(texto(m.getOrDefault("nombre", fichero.getFileName().toString())), ventana, exe, List.copyOf(args),
                Map.copyOf(controles), eMapa, eGuion);
    }

    /** Las formas de encontrar un control, de la mejor a la peor. */
    public List<String> apodos(String control) {
        List<String> l = controles.get(control);
        if (l == null) {
            throw new IllegalArgumentException("El mapa no conoce ningún control que se llame «" + control + "». Los que hay: "
                    + String.join(", ", controles.keySet()));
        }
        return l;
    }

    private static String texto(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
