package es.amagna.asistente.altas;

import es.amagna.asistente.Zips;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * De un bloque ya leído y planificado a los pedidos que entiende la tarea «crear-expediente» del mapa de estados:
 * una lista de datos (cliente, contrario, descripción, adjuntos) por cada expediente, con su clave contra duplicados.
 *
 * <p>Pura decisión, sin tocar MN ni disco. Regla de oro: no se inventa nada. Sin descripción válida el expediente se crea
 * con la descripción vacía (lo pidió Miguel: la completa el supervisor) y se avisa; sin CIF del contrario
 * el expediente se crea sin contrario (incidencia), porque buscar un contrario por nombre es adivinar.
 */
public final class Pedido {
    private Pedido() {
    }

    /** Lo máximo que sabe adjuntar la tarea del mapa (huecos adjunto … adjunto8). */
    public static final int MAX_ADJUNTOS = 8;

    /** Lo que hay que hacer para un expediente. {@code clave} = NIF|entidad|descripción, para no repetirlo. */
    public record PedidoExpediente(String clave, String entidad, Map<String, String> params, List<String> ficherosDelBloque,
            List<String> avisos) {
    }

    public record Resultado(List<PedidoExpediente> pedidos, List<String> noSeCrean) {
    }

    public static Resultado de(Lectura.Resultado l, Plan.Resultado plan) {
        List<PedidoExpediente> pedidos = new ArrayList<>();
        List<String> noSeCrean = new ArrayList<>();
        String nif = txt(l.cliente().get("nif")).toUpperCase(Locale.ROOT).replaceAll("[\\s.-]", "");
        String nombre = (txt(l.cliente().get("nombre")) + " " + txt(l.cliente().get("apellidos"))).strip().replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
        if (nif.isEmpty() || nombre.isBlank()) {
            noSeCrean.add("Faltan el NIF o el nombre del cliente: no se crea ningún expediente.");
            return new Resultado(pedidos, noSeCrean);
        }
        Map<String, String> cifPorEntidad = new LinkedHashMap<>();
        for (Object o : l.expedientes()) {
            if (o instanceof Map<?, ?> e) {
                cifPorEntidad.put(clave(txt(e.get("entidad"))), txt(e.get("cif_entidad")).toUpperCase(Locale.ROOT).replaceAll("\\s", ""));
            }
        }
        for (Plan.Expediente e : plan.expedientes()) {
            List<String> avisos = new ArrayList<>(e.avisos());
            Map<String, String> p = new LinkedHashMap<>();
            if (e.descripcion().isBlank()) {
                // Miguel: la descripción puede ir vacía, la completa el supervisor. No se inventa una.
                avisos.add("Sin descripción válida para «" + e.entidad() + "»: el expediente se crea con la descripción vacía, que la ponga el supervisor.");
            } else {
                p.put("descripcion", e.descripcion());
            }
            p.put("cliente", nombre);
            p.put("cliente_nombre", nombre);
            String cif = cifPorEntidad.getOrDefault(clave(e.entidad()), "");
            if (cif.isEmpty()) {
                avisos.add("Sin CIF de «" + e.entidad() + "» en los papeles: el expediente se crea sin contrario (que lo ponga una persona).");
            } else {
                p.put("contrario", cif);
            }
            List<String> ficheros = new ArrayList<>(e.adjuntos());
            if (ficheros.size() > MAX_ADJUNTOS) {
                avisos.add("Hay " + ficheros.size() + " documentos y solo caben " + MAX_ADJUNTOS + " por expediente: sobran "
                        + (ficheros.size() - MAX_ADJUNTOS) + " (que los suba una persona).");
                ficheros = new ArrayList<>(ficheros.subList(0, MAX_ADJUNTOS));
            }
            pedidos.add(new PedidoExpediente(nif + "|" + clave(e.entidad()) + "|" + e.descripcion(), e.entidad(), p, ficheros, avisos));
        }
        return new Resultado(pedidos, noSeCrean);
    }

    /** Pone los adjuntos en los parámetros, ya con la ruta real donde se han dejado. */
    public static Map<String, String> conAdjuntos(PedidoExpediente p, List<String> rutasReales) {
        Map<String, String> m = new LinkedHashMap<>(p.params());
        for (int i = 0; i < rutasReales.size() && i < MAX_ADJUNTOS; i++) {
            m.put(i == 0 ? "adjunto" : "adjunto" + (i + 1), rutasReales.get(i));
        }
        if (!rutasReales.isEmpty()) {
            String f = rutasReales.get(0).replace('/', '\\');
            String n = f.substring(f.lastIndexOf('\\') + 1);
            int punto = n.lastIndexOf('.');
            m.put("adjunto_nombre", punto > 0 ? n.substring(0, punto) : n);
        }
        return m;
    }

    /** Un nombre de fichero que Windows acepta. */
    public static String nombreSeguro(String nombre) {
        String n = Normalizer.normalize(nombre, Normalizer.Form.NFC).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        return n.isEmpty() ? "documento" : n;
    }

    /** Qué ficheros del bloque hay que sacar y con qué nombre; los que no están en el bloque, no. */
    public static Map<String, Zips.Doc> buscar(List<String> rutas, Zips.Bloque bloque) {
        Map<String, Zips.Doc> r = new LinkedHashMap<>();
        for (String ruta : rutas) {
            for (Zips.Doc d : bloque.documentos()) {
                if (d.ruta().equals(ruta)) {
                    r.put(ruta, d);
                    break;
                }
            }
        }
        return r;
    }

    private static String clave(String entidad) {
        return Normalizer.normalize(entidad, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "");
    }

    private static String txt(Object o) {
        return o == null ? "" : String.valueOf(o).strip();
    }
}
