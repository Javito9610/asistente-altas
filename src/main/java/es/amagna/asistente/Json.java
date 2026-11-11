package es.amagna.asistente;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON mínimo, sin dependencias: objetos → LinkedHashMap, listas → ArrayList, números → Long o Double.
 * Suficiente para hablar con la API de Claude. (Sin librerías externas para que el programa sea un solo .jar.)
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object leer(String texto) {
        Json j = new Json(texto);
        j.blancos();
        Object v = j.valor();
        j.blancos();
        if (j.i != j.s.length()) {
            throw new IllegalArgumentException("JSON con texto de más en la posición " + j.i);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> objeto(String texto) {
        return (Map<String, Object>) leer(texto);
    }

    private Object valor() {
        if (i >= s.length()) {
            throw new IllegalArgumentException("JSON cortado");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{': return objeto();
            case '[': return lista();
            case '"': return cadena();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default: return numero();
        }
    }

    private Map<String, Object> objeto() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        blancos();
        if (s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            blancos();
            String k = cadena();
            blancos();
            esperar(':');
            blancos();
            m.put(k, valor());
            blancos();
            if (s.charAt(i) == ',') {
                i++;
                continue;
            }
            esperar('}');
            return m;
        }
    }

    private List<Object> lista() {
        List<Object> l = new ArrayList<>();
        i++;
        blancos();
        if (s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            blancos();
            l.add(valor());
            blancos();
            if (s.charAt(i) == ',') {
                i++;
                continue;
            }
            esperar(']');
            return l;
        }
    }

    private String cadena() {
        esperar('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: throw new IllegalArgumentException("Escape JSON no válido: \\" + e);
            }
        }
    }

    private Object numero() {
        int ini = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        String n = s.substring(ini, i);
        if (n.isEmpty()) {
            throw new IllegalArgumentException("JSON no válido en la posición " + ini);
        }
        return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
    }

    private void literal(String l) {
        if (!s.startsWith(l, i)) {
            throw new IllegalArgumentException("JSON no válido en la posición " + i);
        }
        i += l.length();
    }

    private void esperar(char c) {
        if (i >= s.length() || s.charAt(i) != c) {
            throw new IllegalArgumentException("Se esperaba '" + c + "' en la posición " + i);
        }
        i++;
    }

    private void blancos() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    // ---------- escribir ----------

    public static String escribir(Object v) {
        StringBuilder b = new StringBuilder();
        escribir(b, v);
        return b.toString();
    }

    private static void escribir(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String t) {
            b.append('"');
            for (int k = 0; k < t.length(); k++) {
                char c = t.charAt(k);
                switch (c) {
                    case '"': b.append("\\\""); break;
                    case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n"); break;
                    case '\r': b.append("\\r"); break;
                    case '\t': b.append("\\t"); break;
                    default:
                        if (c < 0x20) {
                            b.append(String.format("\\u%04x", (int) c));
                        } else {
                            b.append(c);
                        }
                }
            }
            b.append('"');
        } else if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean primero = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!primero) {
                    b.append(',');
                }
                primero = false;
                escribir(b, String.valueOf(e.getKey()));
                b.append(':');
                escribir(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof List<?> l) {
            b.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) {
                    b.append(',');
                }
                escribir(b, l.get(k));
            }
            b.append(']');
        } else if (v instanceof Boolean || v instanceof Number) {
            b.append(v);
        } else {
            throw new IllegalArgumentException("No se sabe escribir en JSON: " + v.getClass());
        }
    }

    /** Atajo para construir objetos: Json.obj("a", 1, "b", "x"). */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k < kv.length; k += 2) {
            m.put((String) kv[k], kv[k + 1]);
        }
        return m;
    }
}
