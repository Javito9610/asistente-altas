package es.amagna.asistente.altas;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * De lo que dicen los papeles a lo que hay que crear en MN: la ficha y UN expediente por sociedad prestamista, con
 * su descripción, su referencia propia y los documentos que lleva cada cosa.
 *
 * <p>Aquí no se toca MN ni se llama a la IA: es pura decisión, y por eso se puede comprobar entera sin Windows.
 * Las reglas son las del despacho (Miguel), tal como están en {@code docs/lo-que-hace-el-agente-de-miguel.md}.
 *
 * @package Amagna\Altas
 */
public final class Plan {
    private Plan() {
    }

    /** Las únicas descripciones válidas. Cualquier otra cosa se deja vacía y se avisa: no se inventa una. */
    public static final List<String> DESCRIPCIONES =
            List.of("MICRO", "PRESTAMO (T)", "REVOLVING (T)", "REVOLVING (T) + DILIGENCIAS");

    /** Lo que se crea para una entidad. */
    public record Expediente(String entidad, String marca, String descripcion, String refPropia,
            List<String> contratos, List<String> adjuntos, List<String> avisos) {
    }

    /** Todo el plan de un cliente. */
    public record Resultado(List<String> adjuntosFicha, List<Expediente> expedientes, List<String> avisos) {
    }

    /** (AND), (CHA) o (AIN) según de quién sea el bloque, por el nombre del ZIP. Vacía si no se reconoce. */
    public static String refPropia(String nombreZip) {
        String n = sinAcentos(nombreZip == null ? "" : nombreZip).toUpperCase(Locale.ROOT);
        if (n.contains("AINHOA")) {
            return "(AIN)";
        }
        if (n.contains("ANDREA")) {
            return "(AND)";
        }
        if (n.contains("CHAMORRO")) {
            return "(CHA)";
        }
        return "";
    }

    /** Solo vale una de las cuatro. Devuelve la canónica o cadena vacía. */
    public static String descripcionValida(String d) {
        if (d == null) {
            return "";
        }
        String n = d.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        for (String v : DESCRIPCIONES) {
            if (v.equals(n)) {
                return v;
            }
        }
        return "";
    }

    public static Resultado de(Lectura.Resultado l, String nombreZip) {
        List<String> avisos = new ArrayList<>();
        String ref = refPropia(nombreZip);
        if (ref.isEmpty()) {
            avisos.add("No sé de qué comercial es el bloque («" + nombreZip + "»): Ref. Propia va vacía.");
        }
        List<String> ficha = new ArrayList<>();
        List<String> paraTodos = new ArrayList<>(); // identidad y notas van también a cada expediente
        String autorizacion = null;
        for (Object o : l.ficheros()) {
            if (!(o instanceof Map<?, ?> f)) {
                continue;
            }
            String ruta = txt(f.get("ruta"));
            String que = txt(f.get("que_es"));
            if (ruta.isEmpty()) {
                continue;
            }
            switch (que) {
                case "identidad", "notas" -> {
                    ficha.add(ruta);
                    paraTodos.add(ruta);
                }
                case "justificante" -> ficha.add(ruta);
                case "hoja_encargo" -> {
                    ficha.add(ruta);
                    autorizacion = ruta;
                }
                default -> {
                }
            }
        }
        if (autorizacion == null) {
            avisos.add("No hay hoja de encargo: no hay autorización que adjuntar a los expedientes.");
        }

        // Una entidad = un expediente (aunque las marcas sean distintas), salvo tarjetas revolving distintas.
        Map<String, Expediente> porEntidad = new LinkedHashMap<>();
        int n = 0;
        for (Object o : l.expedientes()) {
            if (!(o instanceof Map<?, ?> e)) {
                continue;
            }
            n++;
            String entidad = txt(e.get("entidad"));
            String marca = txt(e.get("marca"));
            String desc = descripcionValida(txt(e.get("descripcion")));
            List<String> av = new ArrayList<>();
            if (entidad.isEmpty()) {
                av.add("Un expediente no dice de qué entidad es: no se crea.");
                avisos.addAll(av);
                continue;
            }
            if (desc.isEmpty()) {
                av.add("Sin descripción válida para " + entidad + ": se deja vacía.");
            }
            List<String> contratos = new ArrayList<>();
            List<String> docs = new ArrayList<>();
            if (e.get("documentos") instanceof List<?> ds) {
                for (Object d : ds) {
                    contratos.add(String.valueOf(d));
                    docs.add(String.valueOf(d));
                }
            }
            docs.addAll(paraTodos);
            if (autorizacion != null) {
                docs.add(autorizacion);
                av.add("La autorización es la página 2 de la hoja de encargo: hay que separarla.");
            }
            boolean revolving = desc.startsWith("REVOLVING");
            String clave = sinAcentos(entidad).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "")
                    + (revolving ? "#" + n : "");
            Expediente nuevo = new Expediente(entidad, marca, desc, ref, sinRepetir(contratos), sinRepetir(docs), av);
            Expediente previo = porEntidad.get(clave);
            if (previo == null) {
                porEntidad.put(clave, nuevo);
            } else {
                List<String> c = new ArrayList<>(previo.contratos());
                c.addAll(nuevo.contratos());
                List<String> d = new ArrayList<>(previo.adjuntos());
                d.addAll(nuevo.adjuntos());
                List<String> a = new ArrayList<>(previo.avisos());
                if (!previo.descripcion().equals(nuevo.descripcion())) {
                    a.add("Los contratos de " + entidad + " no coinciden en descripción («" + previo.descripcion()
                            + "» / «" + nuevo.descripcion() + "»): se deja la primera.");
                }
                porEntidad.put(clave, new Expediente(previo.entidad(),
                        previo.marca().isEmpty() ? nuevo.marca() : previo.marca(),
                        previo.descripcion().isEmpty() ? nuevo.descripcion() : previo.descripcion(), ref,
                        sinRepetir(c), sinRepetir(d), a));
            }
        }
        return new Resultado(sinRepetir(ficha), new ArrayList<>(porEntidad.values()), avisos);
    }

    private static List<String> sinRepetir(List<String> l) {
        return new ArrayList<>(new java.util.LinkedHashSet<>(l));
    }

    private static String txt(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    static String sinAcentos(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }
}
