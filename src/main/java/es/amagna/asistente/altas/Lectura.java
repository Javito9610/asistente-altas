package es.amagna.asistente.altas;

import es.amagna.asistente.Json;
import es.amagna.asistente.Zips;
import es.amagna.asistente.cerebro.Cerebro;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Leer los papeles de un bloque y sacar los datos del alta.
 *
 * Aquí no hay nada que programar: hay que ENTENDER un DNI, un contrato y una hoja de encargo, y decir qué dato es
 * cada cosa. Eso es trabajo de la inteligencia artificial, y es justo para lo que está.
 *
 * <h2>Por qué no se analizan los PDF por nuestra cuenta</h2>
 * La tentación es sacar el texto del PDF con una librería y buscar el DNI con una expresión regular. Está mal por
 * dos motivos: muchos documentos de banco son fotos escaneadas y no llevan texto dentro (el justificante de pago
 * del bloque de prueba es una foto), y un texto medio extraído da datos medio buenos, que es lo peor que puede
 * pasar en un alta. Así que los documentos se mandan tal cual —el PDF es un PDF y la foto es una foto— y se leen
 * enteros, como los leería una persona.
 *
 * <h2>Lo que nunca hace</h2>
 * No inventa un dato que no esté en los papeles. Si falta algo de lo imprescindible o hay dos papeles que se
 * contradicen, lo deja escrito en «dudas» y el alta NO continúa: la mira una persona. Más vale un alta parada que
 * un cliente dado de alta con un NIF inventado.
 *
 * @package Amagna\Altas
 */
public final class Lectura {
    private Lectura() {
    }

    /** Lo imprescindible para poder dar un alta. Sin alguno de estos, no se sigue. */
    public static final List<String> IMPRESCINDIBLES = List.of("nombre", "apellidos", "nif");

    private static final long TOPE_BYTES = 28L * 1024 * 1024;

    /** Lo que ha salido de leer los papeles. */
    public record Resultado(Map<String, Object> cliente, List<Object> expedientes, List<Object> ficheros,
            List<String> dudas, String crudo) {

        /** ¿Se puede seguir? Solo si están los datos imprescindibles y nadie ha levantado la mano. */
        public boolean completo() {
            return dudas.isEmpty() && IMPRESCINDIBLES.stream().allMatch(c -> {
                Object v = cliente.get(c);
                return v != null && !String.valueOf(v).isBlank();
            });
        }

        public List<String> queFalta() {
            List<String> faltan = new ArrayList<>();
            for (String c : IMPRESCINDIBLES) {
                Object v = cliente.get(c);
                if (v == null || String.valueOf(v).isBlank()) {
                    faltan.add(c);
                }
            }
            return faltan;
        }
    }

    /** El tipo de contenido con el que se manda cada documento, o null si no se puede mandar. */
    public static String comoSeManda(String extension) {
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "txt" -> "texto";
            default -> null;
        };
    }

    /** Los bloques de contenido que se le enseñan a la IA: un rótulo y el documento, por orden. */
    public static List<Object> comoSeLeEnsena(Zips.Bloque bloque) {
        List<Object> partes = new ArrayList<>();
        partes.add(Json.obj("type", "text", "text",
                "Bloque documental «" + bloque.zip() + "». Carpeta del cliente: «" + bloque.cliente() + "».\n"
                + "A continuación van los documentos, cada uno precedido de su ruta dentro del bloque."));
        long total = 0;
        for (Zips.Doc d : bloque.documentos()) {
            String como = comoSeManda(d.extension());
            String rotulo = "--- documento: " + d.ruta() + (d.entidad().isBlank() ? "" : "   [entidad/expediente: "
                    + d.entidad() + "]") + " ---";
            if (como == null) {
                partes.add(Json.obj("type", "text", "text", rotulo + "\n(no se puede abrir un «" + d.extension()
                        + "»; queda sin leer)"));
                continue;
            }
            total += d.datos().length;
            if (total > TOPE_BYTES) {
                partes.add(Json.obj("type", "text", "text", rotulo + "\n(el bloque pesa demasiado; este queda sin leer)"));
                continue;
            }
            partes.add(Json.obj("type", "text", "text", rotulo));
            if ("texto".equals(como)) {
                partes.add(Json.obj("type", "text", "text", new String(d.datos(), java.nio.charset.StandardCharsets.UTF_8)));
            } else {
                String datos = Base64.getEncoder().encodeToString(d.datos());
                partes.add(Json.obj(
                        "type", como.startsWith("image/") ? "image" : "document",
                        "source", Json.obj("type", "base64", "media_type", como, "data", datos)));
            }
        }
        return partes;
    }

    /** Lee los papeles del bloque. */
    @SuppressWarnings("unchecked")
    public static Resultado leer(Cerebro cerebro, String modelo, String instrucciones, Zips.Bloque bloque) {
        if (!bloque.vale()) {
            throw new IllegalArgumentException("Ese bloque no se puede leer: " + bloque.problema());
        }
        Map<String, Object> r = cerebro.pensar(Json.obj(
                "model", modelo,
                "max_tokens", 8192,
                "system", instrucciones,
                "messages", List.of(Json.obj("role", "user", "content", comoSeLeEnsena(bloque)))));
        StringBuilder texto = new StringBuilder();
        for (Object o : (List<Object>) r.getOrDefault("content", List.of())) {
            if (o instanceof Map<?, ?> b && "text".equals(b.get("type"))) {
                texto.append(b.get("text"));
            }
        }
        return deJson(texto.toString());
    }

    /** Saca el resultado de lo que haya contestado, aunque venga con texto alrededor. */
    @SuppressWarnings("unchecked")
    public static Resultado deJson(String contestacion) {
        int a = contestacion.indexOf('{');
        int z = contestacion.lastIndexOf('}');
        if (a < 0 || z <= a) {
            throw new IllegalStateException("La lectura no ha devuelto ningún dato: "
                    + (contestacion.length() > 200 ? contestacion.substring(0, 200) + "…" : contestacion));
        }
        Map<String, Object> j = Json.objeto(contestacion.substring(a, z + 1));
        Map<String, Object> cliente = new LinkedHashMap<>();
        if (j.get("cliente") instanceof Map<?, ?> c) {
            c.forEach((k, v) -> cliente.put(String.valueOf(k).toLowerCase(Locale.ROOT),
                    v == null ? "" : String.valueOf(v).strip()));
        }
        List<Object> expedientes = j.get("expedientes") instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
        List<Object> ficheros = j.get("ficheros") instanceof List<?> l2 ? new ArrayList<>(l2) : new ArrayList<>();
        List<String> dudas = new ArrayList<>();
        if (j.get("dudas") instanceof List<?> l3) {
            l3.forEach(d -> {
                String t = String.valueOf(d).strip();
                if (!t.isEmpty()) {
                    dudas.add(t);
                }
            });
        }
        return new Resultado(cliente, expedientes, ficheros, dudas, contestacion);
    }

    /** El resumen que ve una persona. */
    @SuppressWarnings("unchecked")
    public static String comoSeCuenta(Zips.Bloque bloque, Resultado r) {
        StringBuilder b = new StringBuilder("Bloque «" + bloque.zip() + "» · " + bloque.documentos().size() + " documentos\n");
        b.append("\nCLIENTE\n");
        if (r.cliente().isEmpty()) {
            b.append("  (no se ha sacado ningún dato)\n");
        }
        r.cliente().forEach((k, v) -> b.append("  ").append(k).append(": ")
                .append(String.valueOf(v).isBlank() ? "(no consta)" : v).append('\n'));
        b.append("\nEXPEDIENTES (").append(r.expedientes().size()).append(")\n");
        for (Object e : r.expedientes()) {
            if (e instanceof Map<?, ?> m) {
                b.append("  · ");
                m.forEach((k, v) -> b.append(k).append('=').append(v).append("  "));
                b.append('\n');
            }
        }
        b.append("\nQUÉ ES CADA FICHERO\n");
        for (Object f : r.ficheros()) {
            if (f instanceof Map<?, ?> m) {
                Object ruta = m.get("ruta");
                Object queEs = m.get("que_es");
                b.append("  · ").append(ruta == null ? "?" : ruta).append("  →  ")
                        .append(queEs == null ? "?" : queEs);
                Object por = m.get("por_que");
                if (por != null && !String.valueOf(por).isBlank()) {
                    b.append("   (").append(por).append(')');
                }
                b.append('\n');
            }
        }
        if (r.completo()) {
            b.append("\nLISTO: están los datos imprescindibles y no hay dudas.\n");
        } else {
            b.append("\nNO SE SIGUE. Esto lo tiene que mirar una persona:\n");
            r.queFalta().forEach(f -> b.append("  · falta ").append(f).append('\n'));
            r.dudas().forEach(d -> b.append("  · ").append(d).append('\n'));
        }
        return b.toString();
    }
}
