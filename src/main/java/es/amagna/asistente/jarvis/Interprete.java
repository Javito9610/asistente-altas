package es.amagna.asistente.jarvis;

import es.amagna.asistente.Json;
import es.amagna.asistente.cerebro.Cerebro;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Convierte lo que dice una persona («dame de alta a Pepe García, NIF …») en algo que Jarvis puede hacer. Usa la IA solo para
 * ENTENDER (no toca nada): devuelve un JSON con la tarea y sus datos, o una pregunta si falta algo. Lo que no se entiende
 * nunca se ejecuta: se pregunta. Los datos que faltan NUNCA se inventan.
 */
public final class Interprete {
    /** Las tareas que el mapa sabe hacer sin IA y los datos que necesitan (obligatorios entre corchetes). */
    public static final Map<String, String> CATALOGO = Map.of(
            "crear-cliente", "Alta de un cliente en MN. Datos: [cliente_nombre] [cliente_nif] cliente_telefono cliente_movil cliente_correo "
                    + "cliente_direccion cliente_cp cliente_poblacion. Los adjuntos los pone el sistema, no los pidas.",
            "crear-expediente", "Alta de un expediente en MN. Datos: [descripcion] [cliente] (NIF del cliente) contrario (NIF/CIF) "
                    + "contrario_nombre.");

    private final Cerebro cerebro;
    private final String modelo;

    public Interprete(Cerebro cerebro, String modelo) {
        this.cerebro = cerebro;
        this.modelo = modelo;
    }

    @SuppressWarnings("unchecked")
    public Entendido entender(String mensaje, String contexto) {
        StringBuilder sistema = new StringBuilder("""
                Eres el intérprete de Jarvis, el asistente de oficina de un despacho de abogados. Recibes el mensaje de una persona y
                decides qué hay que hacer. NO haces nada tú: solo respondes con UN objeto JSON, sin texto alrededor:
                {"tipo":"tarea|libre|preguntar|responder","tarea":"nombre","params":{"dato":"valor"},"texto":"..."}
                - tipo «tarea»: es una de estas tareas que Jarvis hace solo (poner en «tarea» su nombre y en «params» los datos que
                  la persona ha dado, con los nombres de dato exactos). Tareas:
                """);
        for (var e : CATALOGO.entrySet()) {
            sistema.append("  · ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        sistema.append("""
                  Si falta un dato obligatorio, tipo «preguntar» con la pregunta en «texto». NUNCA inventes ni completes datos.
                - tipo «libre»: otra cosa que se pueda hacer en el ordenador (abrir un programa, buscar algo, mover ficheros, mirar
                  una web…). En «texto» una descripción corta y clara de lo que se va a hacer.
                - tipo «preguntar»: no está claro qué quiere o falta algo. «texto» = una sola pregunta corta.
                - tipo «responder»: es una pregunta o charla que se contesta con palabras (sin tocar nada). «texto» = la respuesta.
                Pagos, transferencias, compras, envío de mensajes o correos, y borrar cosas: tipo «libre» solo si la persona lo pide
                de forma explícita; siempre se le pide confirmación después. Español, tuteo, breve.
                """);
        Map<String, Object> pet = new LinkedHashMap<>();
        pet.put("model", modelo);
        pet.put("max_tokens", 1000);
        pet.put("system", sistema.toString() + (contexto == null || contexto.isBlank() ? "" : "\nContexto: " + contexto));
        pet.put("messages", List.of(Json.obj("role", "user", "content", mensaje)));
        String texto;
        try {
            Map<String, Object> r = cerebro.pensar(pet);
            StringBuilder b = new StringBuilder();
            for (Object c : (List<Object>) r.getOrDefault("content", List.of())) {
                Map<String, Object> m = (Map<String, Object>) c;
                if ("text".equals(m.get("type"))) {
                    b.append(m.get("text"));
                }
            }
            texto = b.toString();
        } catch (RuntimeException e) {
            return new Entendido(Entendido.RESPONDER, "", Map.of(), "Ahora mismo no puedo pensar (" + e.getMessage() + "). Inténtalo en un rato.");
        }
        return leer(texto);
    }

    /** Lee la respuesta de la IA con desconfianza: lo que no cuadra con el catálogo se convierte en pregunta. */
    @SuppressWarnings("unchecked")
    public static Entendido leer(String texto) {
        int a = texto.indexOf('{');
        int z = texto.lastIndexOf('}');
        if (a < 0 || z < a) {
            return new Entendido(Entendido.PREGUNTAR, "", Map.of(), "No te he entendido bien. ¿Me lo dices con otras palabras?");
        }
        try {
            Map<String, Object> m = Json.objeto(texto.substring(a, z + 1));
            String tipo = String.valueOf(m.getOrDefault("tipo", ""));
            String tarea = String.valueOf(m.getOrDefault("tarea", ""));
            String t = String.valueOf(m.getOrDefault("texto", ""));
            Map<String, String> params = new LinkedHashMap<>();
            if (m.get("params") instanceof Map<?, ?> pm) {
                pm.forEach((k, v) -> {
                    if (v != null && !String.valueOf(v).isBlank()) {
                        params.put(String.valueOf(k), String.valueOf(v).strip());
                    }
                });
            }
            switch (tipo) {
                case Entendido.TAREA -> {
                    if (!CATALOGO.containsKey(tarea)) {
                        return new Entendido(Entendido.PREGUNTAR, "", Map.of(), "No conozco esa tarea. ¿Qué quieres que haga exactamente?");
                    }
                    String falta = obligatoriosQueFaltan(tarea, params);
                    if (!falta.isEmpty()) {
                        return new Entendido(Entendido.PREGUNTAR, "", Map.of(), "Me falta: " + falta + ". ¿Me lo dices?");
                    }
                    return new Entendido(Entendido.TAREA, tarea, params, t);
                }
                case Entendido.LIBRE, Entendido.PREGUNTAR, Entendido.RESPONDER -> {
                    return new Entendido(tipo, "", params, t.isBlank() ? "¿Puedes explicarme mejor qué quieres?" : t);
                }
                default -> {
                    return new Entendido(Entendido.PREGUNTAR, "", Map.of(), "No te he entendido bien. ¿Me lo dices con otras palabras?");
                }
            }
        } catch (RuntimeException e) {
            return new Entendido(Entendido.PREGUNTAR, "", Map.of(), "No te he entendido bien. ¿Me lo dices con otras palabras?");
        }
    }

    static String obligatoriosQueFaltan(String tarea, Map<String, String> params) {
        List<String> obligatorios = switch (tarea) {
            case "crear-cliente" -> List.of("cliente_nombre", "cliente_nif");
            case "crear-expediente" -> List.of("descripcion", "cliente");
            default -> List.of();
        };
        List<String> falta = obligatorios.stream().filter(k -> params.getOrDefault(k, "").isBlank()).toList();
        return String.join(", ", falta);
    }
}
