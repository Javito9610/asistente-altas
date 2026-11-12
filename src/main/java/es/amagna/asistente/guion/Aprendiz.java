package es.amagna.asistente.guion;

import es.amagna.asistente.Json;
import es.amagna.asistente.cerebro.Cerebro;
import es.amagna.asistente.manos.Controles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * El aprendiz: la inteligencia artificial MIRA un programa y escribe su mapa; el programa COMPRUEBA, una por una, que
 * todas las formas que ha propuesto funcionan de verdad, y tira las que no.
 *
 * Aquí se ve para qué sirve cada uno. Entender una pantalla que no se ha visto nunca —saber que ese cuadro de ahí es
 * el NIF y ese otro el importe, aunque se llamen «1» y «2»— eso no lo hace un programa: eso es cabeza. Y comprobar
 * cien veces sin equivocarse ni cansarse que cada cosa está donde se dijo, eso no lo hace una cabeza: eso es un
 * programa. El mapa que sale de aquí es lo mejor de los dos: lo ha pensado la IA y está verificado uno por uno.
 *
 * Cartografiar MN se hace así UNA VEZ. Después, las altas de todas las noches ya no necesitan a nadie pensando.
 *
 * @package Amagna\Altas
 */
public final class Aprendiz {
    private Aprendiz() {
    }

    public record Propuesta(Map<String, List<String>> controles, List<String> avisos) {
    }

    private static final String ENCARGO = """
            Eres el Asistente Altas de Amagna. Te paso los controles de una ventana de Windows, leídos por dentro.
            Tu trabajo es ponerle nombre a cada campo y a cada botón QUE SIRVA PARA RELLENAR O MANEJAR ESTA PANTALLA,
            y decir cómo encontrarlo.

            Contesta SOLO con un JSON, sin explicaciones ni ```:
            {"controles": {"nombre_corto": ["forma 1", "forma 2"], ...}}

            - «nombre_corto»: en español, en minúsculas y con guiones bajos, lo que es ese dato para una persona
              (nif, nombre, apellidos, fecha_nacimiento, importe, guardar, buscar, aceptar…). Nunca el texto literal
              del programa si es raro, ni «campo1».
            - Para cada uno, de 1 a 3 formas de encontrarlo, DE LA MEJOR A LA PEOR:
                1) "#identificador"        si lo tiene (es la que no cambia nunca: ponla siempre la primera)
                2) "campo junto a Etiqueta" si el cuadro no tiene nombre propio pero tiene una etiqueta al lado
                3) "tipo:Nombre"            por ejemplo "boton:Guardar"
            - No inventes nada que no esté en la lista. Si dudas de qué es un control, no lo pongas.
            - Deja fuera lo que no sirve para trabajar: barras de título, minimizar, maximizar, cerrar, barras de
              desplazamiento, textos de la barra de estado.
            """;

    /** La IA mira los controles de la ventana y propone cómo se llama cada cosa. */
    @SuppressWarnings("unchecked")
    public static Propuesta proponer(Cerebro cerebro, String modelo, String arbol) {
        Map<String, Object> r = cerebro.pensar(Json.obj(
                "model", modelo,
                "max_tokens", 4096,
                "system", ENCARGO,
                "messages", List.of(Json.obj("role", "user", "content", arbol))));
        StringBuilder texto = new StringBuilder();
        for (Object o : (List<Object>) r.getOrDefault("content", List.of())) {
            if (o instanceof Map<?, ?> b && "text".equals(b.get("type"))) {
                texto.append(b.get("text"));
            }
        }
        String t = texto.toString().strip();
        int a = t.indexOf('{');
        int z = t.lastIndexOf('}');
        if (a < 0 || z <= a) {
            throw new IllegalStateException("La respuesta no trae ningún mapa: " + (t.length() > 200 ? t.substring(0, 200) + "…" : t));
        }
        Map<String, Object> j = Json.objeto(t.substring(a, z + 1));
        Map<String, List<String>> controles = new LinkedHashMap<>();
        List<String> avisos = new ArrayList<>();
        if (j.get("controles") instanceof Map<?, ?> c) {
            for (Map.Entry<?, ?> e : c.entrySet()) {
                List<String> apodos = new ArrayList<>();
                if (e.getValue() instanceof List<?> l) {
                    l.forEach(x -> apodos.add(String.valueOf(x)));
                } else if (e.getValue() != null) {
                    apodos.add(String.valueOf(e.getValue()));
                }
                if (apodos.isEmpty()) {
                    avisos.add("«" + e.getKey() + "»: no dijo cómo encontrarlo; fuera");
                } else {
                    controles.put(String.valueOf(e.getKey()), apodos);
                }
            }
        }
        if (controles.isEmpty()) {
            throw new IllegalStateException("No ha salido ningún control del mapa.");
        }
        return new Propuesta(controles, avisos);
    }

    /**
     * Y ahora el programa comprueba a la IA: cada forma propuesta se prueba contra la ventana de verdad. La que no
     * encuentra nada, o encuentra varias cosas a la vez, se tira. Lo que queda está verificado, no imaginado.
     */
    public static Propuesta verificar(Controles ctrl, String ventana, Propuesta propuesta, Consumer<String> registro) {
        Map<String, List<String>> buenos = new LinkedHashMap<>();
        List<String> avisos = new ArrayList<>(propuesta.avisos());
        for (Map.Entry<String, List<String>> e : propuesta.controles().entrySet()) {
            List<String> valen = new ArrayList<>();
            for (String apodo : e.getValue()) {
                Controles.Encontrado f = ctrl.buscar(ventana, apodo);
                if (f.elemento() == null) {
                    avisos.add("«" + e.getKey() + "» → «" + apodo + "»: " + primeraFrase(f.descripcion()) + "; fuera");
                } else {
                    f.elemento().close();
                    valen.add(apodo);
                }
            }
            if (valen.isEmpty()) {
                avisos.add("«" + e.getKey() + "»: ninguna de sus formas encuentra nada; fuera del mapa");
            } else {
                buenos.put(e.getKey(), valen);
            }
        }
        avisos.forEach(registro);
        registro.accept("Comprobadas una a una: " + buenos.size() + " de " + propuesta.controles().size()
                + " controles quedan en el mapa, con " + buenos.values().stream().mapToInt(List::size).sum() + " formas de encontrarlos.");
        if (buenos.isEmpty()) {
            throw new IllegalStateException("Ninguno de los controles propuestos se encuentra de verdad: el mapa no vale.");
        }
        return new Propuesta(buenos, avisos);
    }

    /** Guarda el mapa, listo para usar y para repasarlo a mano. */
    public static void guardar(Path fichero, String nombre, String ventana, String ejecutable, List<String> argumentos,
            Propuesta p) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nombre", nombre);
        m.put("ventana", ventana);
        if (ejecutable != null && !ejecutable.isBlank()) {
            m.put("programa", Json.obj("ejecutable", ejecutable, "argumentos", argumentos == null ? List.of() : argumentos));
        }
        m.put("controles", new LinkedHashMap<String, Object>(p.controles()));
        Files.writeString(fichero, Json.escribir(m), StandardCharsets.UTF_8);
    }

    private static String primeraFrase(String s) {
        int p = s.indexOf(". ");
        return p > 0 ? s.substring(0, p) : s;
    }
}
