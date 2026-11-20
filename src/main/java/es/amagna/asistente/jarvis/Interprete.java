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
        return entenderVarios(mensaje, contexto).get(0);
    }

    /** Un mensaje puede pedir varias cosas: devuelve una por cada una (máximo 4), en orden. */
    @SuppressWarnings("unchecked")
    public List<Entendido> entenderVarios(String mensaje, String contexto) {
        String crudo = pensar(mensaje, contexto);
        return leerVarios(crudo);
    }

    public static List<Entendido> leerVarios(String texto) {
        int a = texto.indexOf('[');
        int z = texto.lastIndexOf(']');
        int llave = texto.indexOf('{');
        if (a >= 0 && z > a && (llave < 0 || a < llave)) {
            try {
                Map<String, Object> w = Json.objeto("{\"l\":" + texto.substring(a, z + 1) + "}");
                List<Entendido> r = new java.util.ArrayList<>();
                for (Object o : (List<Object>) w.get("l")) {
                    if (o instanceof Map<?, ?> m && r.size() < 4) {
                        r.add(leer(Json.escribir(m)));
                    }
                }
                if (!r.isEmpty()) {
                    return r;
                }
            } catch (RuntimeException e) {
                // cae a leer una sola
            }
        }
        return List.of(leer(texto));
    }

    @SuppressWarnings("unchecked")
    private String pensar(String mensaje, String contexto) {
        StringBuilder sistema = new StringBuilder("""
                Eres el intérprete de Jarvis, el asistente de oficina de un despacho de abogados. Recibes el mensaje de una persona y
                decides qué hay que hacer. NO haces nada tú: solo respondes con UN objeto JSON, sin texto alrededor:
                {"tipo":"tarea|consultar|libre|programar|recordar|recordatorio|oficina|correo|abrir|preguntar|responder","tarea":"nombre","params":{"dato":"valor"},"texto":"..."}
                - tipo «tarea»: es una de estas tareas que Jarvis hace solo (poner en «tarea» su nombre y en «params» los datos que
                  la persona ha dado, con los nombres de dato exactos). Tareas:
                """);
        for (var e : CATALOGO.entrySet()) {
            sistema.append("  · ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        sistema.append("""
                  Si falta un dato obligatorio, tipo «preguntar» con la pregunta en «texto». NUNCA inventes ni completes datos.
                - tipo «consultar»: una pregunta cuya respuesta está en lo que se ve ahora en MN (cuántos expedientes hay en la lista,
                  qué número tiene el último, qué cliente aparece…). Se contesta MIRANDO, sin tocar nada. En «texto» la pregunta.
                - tipo «oficina»: trabajo con documentos, ficheros o internet que NO necesita manejar ninguna ventana ni programa: crear un
                  PDF, Word o Excel, rellenar una plantilla, leer o resumir un documento, preparar una cita o un borrador de correo,
                  buscar algo en internet. Se hace directamente, sin pedir confirmación (no toca nada fuera de su carpeta). En «texto»
                  la orden COMPLETA con todos los datos que dio la persona (nada inventado); si falta un dato imprescindible, «preguntar».
                - tipo «correo»: ENVIAR un correo electrónico (la persona lo confirmará leyéndolo entero antes de que salga). En «params»:
                  {"para":"a@x.com, b@y.es","asunto":"…","adjuntos":"ruta1; ruta2"} (adjuntos solo si los pide) y en «texto» el CUERPO
                  completo, escrito como lo diría la persona, educado y claro, solo con los datos que ella ha dado (nada inventado).
                  Si falta el destinatario (una dirección completa) o no se sabe qué decir, tipo «preguntar». Para LEER o resumir los
                  correos que han llegado, usa «oficina», no «correo».
                - tipo «abrir»: abrir una página web, un programa o una carpeta/documento (sin más). En «params»:
                  {"que":"web|programa|carpeta|documento","valor":"…"}: para «web» la dirección COMPLETA (https://…; si dicen «abre google»,
                  https://www.google.com); para «programa» el nombre que dice la persona (calculadora, bloc de notas, word, excel, chrome,
                  edge, outlook…); para «carpeta»/«documento» la ruta. «texto» = qué se abre. Si además hay que hacer algo DENTRO de lo que
                  se abre (escribir, pulsar, buscar), no es «abrir»: es «libre».
                - tipo «libre»: otra cosa que haya que hacer MANEJANDO programas en pantalla (si sirve «oficina», usa «oficina») (abrir un programa, buscar algo, mover ficheros, mirar
                  una web…). En «texto» una descripción corta y clara de lo que se va a hacer. En «tarea» un nombre corto y
                  REUTILIZABLE en minúsculas con guiones (p. ej. «abrir-programa», «buscar-en-web»: sin datos concretos dentro) y
                  en «params» los datos que cambian de una vez a otra (p. ej. {"programa":"calc.exe"}, {"consulta":"horario tren"}).
                  Así, la segunda vez que se pida algo igual, Jarvis lo repite solo sin gastar IA. Si en el Contexto aparece una
                  «tarea libre ya aprendida» que sirve para lo que piden, usa EXACTAMENTE su nombre y los nombres de sus datos.
                - tipo «programar»: algo que se repite o se hace a una hora («cada día a las 9…», «los lunes a las 10…»). En «params»:
                  {"hora":"HH:mm","dias":"todos|lun-vie|finde|lun,mie…|hoy|mañana|AAAA-MM-DD"} («hoy», «mañana» o una fecha = se hace UNA sola vez) y en «texto» lo que hay que hacer, escrito como la orden que
                  daría la persona (sin la hora ni los días). Si falta la hora, tipo «preguntar».
                - tipo «recordatorio»: que Jarvis AVISE a la persona de algo («recuérdame mañana a las 9 llamar a Ana»). En «params»
                  lo mismo que «programar» ({"hora":"HH:mm","dias":"…|hoy|mañana|AAAA-MM-DD"}) y en «texto» lo que hay que recordarle.
                  Sin hora, tipo «preguntar».
                - tipo «recordar»: la persona le cuenta algo que Jarvis debe SABER de ahora en adelante («apunta que el despacho
                  cierra los viernes a las 14», «recuerda que Ana lleva los concursos»). En «texto» el dato, en una frase corta.
                  Nunca claves, tarjetas ni cuentas bancarias.
                - tipo «preguntar»: no está claro qué quiere o falta algo. «texto» = una sola pregunta corta.
                - tipo «responder»: es una pregunta o charla que se contesta con palabras (sin tocar nada). «texto» = la respuesta.
                Si el mensaje pide VARIAS cosas distintas, responde con un array JSON de objetos (máximo 4), en el orden pedido; si es una sola, un objeto.
                El «Contexto» solo sirve para entender mejor lo que la persona dice ahora: NUNCA copies de él nombres, NIF ni datos a
                lo que te pide, y no cambies lo que pregunta. Si pide algo general, «texto» es su pregunta tal cual.
                Pagos, transferencias, compras, envío de mensajes o correos, y borrar cosas: tipo «libre» solo si la persona lo pide
                de forma explícita; siempre se le pide confirmación después. Español, tuteo, breve.
                """);
        Map<String, Object> pet = new LinkedHashMap<>();
        pet.put("model", modelo);
        pet.put("max_tokens", 1000);
        pet.put("system", sistema.toString() + (contexto == null || contexto.isBlank() ? "" : "\nContexto: " + contexto));
        pet.put("messages", List.of(Json.obj("role", "user", "content", mensaje)));
        // Lo único que cambia de una vez a otra (y que el enrutador revisa para saber si puede salir a una IA gratuita).
        pet.put(es.amagna.asistente.cerebro.Enrutador.TEXTO_VARIABLE, mensaje + "\n" + (contexto == null ? "" : contexto));
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
            return "{\"tipo\":\"responder\",\"texto\":\"Ahora mismo no puedo pensar (" + String.valueOf(e.getMessage()).replace("\\", " ").replace("\"", "'").replace("\n", " ")
                    + "). Inténtalo en un rato.\"}";
        }
        return texto;
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
                case Entendido.LIBRE -> {
                    String slug = tarea.toLowerCase(java.util.Locale.ROOT).strip();
                    return new Entendido(tipo, slug.matches("[a-z0-9]+(-[a-z0-9]+){0,5}") && slug.length() <= 40 ? slug : "", params,
                            t.isBlank() ? "¿Puedes explicarme mejor qué quieres?" : t);
                }
                case Entendido.OFICINA -> {
                    return t.isBlank() ? new Entendido(Entendido.PREGUNTAR, "", Map.of(), "¿Qué quieres que prepare?") : new Entendido(tipo, "", Map.of(), t);
                }
                case Entendido.CORREO -> {
                    if (Correo.direcciones(params.getOrDefault("para", "")) == null) {
                        return new Entendido(Entendido.PREGUNTAR, "", Map.of(), "¿A qué dirección de correo lo envío? (una dirección completa, por ejemplo nombre@dominio.com)");
                    }
                    if (params.getOrDefault("asunto", "").isBlank() || t.isBlank()) {
                        return new Entendido(Entendido.PREGUNTAR, "", Map.of(), "¿Qué asunto y qué texto quieres que lleve el correo?");
                    }
                    return new Entendido(tipo, "", params, t);
                }
                case Entendido.ABRIR -> {
                    String que = params.getOrDefault("que", "");
                    if (!java.util.Set.of("web", "programa", "carpeta", "documento").contains(que) || params.getOrDefault("valor", "").isBlank()) {
                        return new Entendido(Entendido.PREGUNTAR, "", Map.of(), "¿Qué quieres que abra? (una página, un programa o una carpeta)");
                    }
                    return new Entendido(tipo, "", params, t);
                }
                case Entendido.CONSULTAR -> {
                    return new Entendido(tipo, "", params, t.isBlank() ? "¿Qué quieres que mire?" : t);
                }
                case Entendido.PROGRAMAR -> {
                    if (params.getOrDefault("hora", "").isBlank() || t.isBlank()) {
                        return new Entendido(Entendido.PREGUNTAR, "", Map.of(), "¿A qué hora (y qué días) quieres que lo haga?");
                    }
                    return new Entendido(tipo, "", params, t);
                }
                case Entendido.RECORDATORIO -> {
                    if (params.getOrDefault("hora", "").isBlank() || t.isBlank()) {
                        return new Entendido(Entendido.PREGUNTAR, "", Map.of(), "¿A qué hora quieres que te lo recuerde?");
                    }
                    return new Entendido(tipo, "", params, t);
                }
                case Entendido.RECORDAR -> {
                    return t.isBlank() ? new Entendido(Entendido.PREGUNTAR, "", Map.of(), "¿Qué quieres que recuerde?") : new Entendido(tipo, "", Map.of(), t);
                }
                case Entendido.PREGUNTAR, Entendido.RESPONDER -> {
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
