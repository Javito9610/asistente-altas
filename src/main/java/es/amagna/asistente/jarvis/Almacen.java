package es.amagna.asistente.jarvis;

import es.amagna.asistente.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * La memoria de los encargos: un fichero JSON por trabajo en datos\trabajos\ (escritura atómica: un apagón no deja un fichero
 * a medias). Sin librerías ni servidor de base de datos; si más adelante hace falta SQLite, solo cambia esta clase.
 */
public final class Almacen {
    private final Path carpeta;

    public Almacen(Path carpeta) {
        this.carpeta = carpeta;
    }

    public synchronized Trabajo nuevo(String origen, String quien, String peticion, String tarea, Map<String, String> params, String estado) {
        long ahora = System.currentTimeMillis();
        String id = String.format("%tY%<tm%<td-%<tH%<tM%<tS-%03d", ahora, (int) (Math.random() * 1000));
        while (Files.exists(ruta(id))) {
            id = id + "x";
        }
        Trabajo t = new Trabajo(id, origen, quien, peticion, tarea, new LinkedHashMap<>(params), estado, "", ahora, 0);
        guardar(t);
        return t;
    }

    public synchronized void guardar(Trabajo t) {
        try {
            Files.createDirectories(carpeta);
            Map<String, Object> m = Json.obj("id", t.id(), "origen", t.origen(), "quien", t.quien(), "peticion", t.peticion(),
                    "tarea", t.tarea(), "params", new LinkedHashMap<String, Object>(t.params()), "estado", t.estado(),
                    "resultado", t.resultado(), "creado", t.creado(), "terminado", t.terminado());
            Path tmp = carpeta.resolve(t.id() + ".json.tmp");
            Files.writeString(tmp, Json.escribir(m), StandardCharsets.UTF_8);
            Files.move(tmp, ruta(t.id()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo guardar el trabajo " + t.id() + ": " + e.getMessage(), e);
        }
    }

    public synchronized Trabajo leer(String id) {
        try {
            return desde(Files.readString(ruta(id), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public synchronized List<Trabajo> todos() {
        List<Trabajo> r = new ArrayList<>();
        if (!Files.isDirectory(carpeta)) {
            return r;
        }
        try (Stream<Path> s = Files.list(carpeta)) {
            for (Path p : s.filter(x -> x.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    r.add(desde(Files.readString(p, StandardCharsets.UTF_8)));
                } catch (IOException | RuntimeException e) {
                    // un fichero estropeado no tumba al resto
                }
            }
        } catch (IOException e) {
            // carpeta ilegible: lista vacía
        }
        r.sort(Comparator.comparingLong(Trabajo::creado).thenComparing(Trabajo::id));
        return r;
    }

    public synchronized List<Trabajo> en(String estado) {
        return todos().stream().filter(t -> t.estado().equals(estado)).toList();
    }

    /** Al arrancar: lo que estaba «en curso» se cortó por un apagado; NO se repite solo (podría duplicar algo): se marca para que lo mire una persona. */
    public synchronized List<Trabajo> marcarInterrumpidos() {
        List<Trabajo> r = new ArrayList<>();
        for (Trabajo t : en(Trabajo.EN_CURSO)) {
            Trabajo n = t.con(Trabajo.FALLO, "Interrumpido: el programa se apagó mientras lo hacía. No se repite solo por si ya había guardado algo; "
                    + "comprueba en MN si quedó hecho y, si no, vuelve a pedirlo.");
            guardar(n);
            r.add(n);
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Trabajo desde(String json) {
        Map<String, Object> m = Json.objeto(json);
        Map<String, String> params = new LinkedHashMap<>();
        for (var e : ((Map<String, Object>) m.getOrDefault("params", Map.of())).entrySet()) {
            params.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return new Trabajo(str(m, "id"), str(m, "origen"), str(m, "quien"), str(m, "peticion"), str(m, "tarea"), params, str(m, "estado"),
                str(m, "resultado"), ((Number) m.getOrDefault("creado", 0)).longValue(), ((Number) m.getOrDefault("terminado", 0)).longValue());
    }

    private static String str(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o == null ? "" : String.valueOf(o);
    }

    private Path ruta(String id) {
        if (!id.matches("[0-9a-zA-Z-]+")) {
            throw new IllegalArgumentException("id no válido");
        }
        return carpeta.resolve(id + ".json");
    }
}
