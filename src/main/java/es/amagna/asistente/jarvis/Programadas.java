package es.amagna.asistente.jarvis;

import es.amagna.asistente.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Encargos que se repiten («cada día laborable a las 9, mira cuántos expedientes hay»). Se guardan en disco y, a su hora, se
 * le vuelven a pedir a Jarvis como si la persona los hubiera escrito (con su mismo permiso: lo «libre» sigue pidiendo un sí).
 */
public final class Programadas {
    public record Programada(String id, String origen, String quien, String hora, Set<DayOfWeek> dias, String peticion, String ultima, String fecha) {
    }

    private final Path fichero;
    private final List<Programada> lista = new ArrayList<>();

    public Programadas(Path fichero) {
        this.fichero = fichero;
        cargar();
    }

    /** dias: «lun-vie», «lun,mie», «todos», «finde»… (en español, sin importar acentos). */
    public static Set<DayOfWeek> dias(String texto) {
        String t = Servicio.norm(texto);
        java.util.EnumSet<DayOfWeek> r = java.util.EnumSet.noneOf(DayOfWeek.class);
        if (t.isEmpty() || t.contains("todos") || t.contains("diari") || t.contains("cada dia")) {
            r.addAll(List.of(DayOfWeek.values()));
            return r;
        }
        if (t.contains("laborable") || t.matches(".*lun\\w*\\s*(-|a)\\s*vie.*")) {
            r.addAll(List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY));
            return r;
        }
        if (t.contains("finde") || t.contains("fin de semana")) {
            r.addAll(List.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
            return r;
        }
        String[][] nombres = {{"lun", "MONDAY"}, {"mar", "TUESDAY"}, {"mie", "WEDNESDAY"}, {"jue", "THURSDAY"}, {"vie", "FRIDAY"},
            {"sab", "SATURDAY"}, {"dom", "SUNDAY"}};
        for (String[] n : nombres) {
            if (t.contains(n[0])) {
                r.add(DayOfWeek.valueOf(n[1]));
            }
        }
        return r;
    }

    public synchronized String anadir(String origen, String quien, String hora, String dias, String peticion) {
        LocalTime h;
        try {
            h = LocalTime.parse(hora.strip().length() == 4 ? "0" + hora.strip() : hora.strip());
        } catch (RuntimeException e) {
            return null;
        }
        String fecha = "";
        String nd = Servicio.norm(dias);
        if (nd.contains("hoy") || nd.contains("manana") || nd.matches(".*\\d{4}-\\d{2}-\\d{2}.*")) {
            try {
                var m = java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(nd);
                LocalDate dia = m.find() ? LocalDate.parse(m.group()) : nd.contains("manana") ? LocalDate.now().plusDays(1) : LocalDate.now();
                fecha = dia.toString();
                dias = "todos";
            } catch (RuntimeException e) {
                return null;
            }
        }
        Set<DayOfWeek> d = dias(dias);
        if (d.isEmpty() || peticion == null || peticion.isBlank()) {
            return null;
        }
        String id = "p" + (System.currentTimeMillis() % 100000);
        lista.add(new Programada(id, origen, quien, h.toString(), d, peticion.strip(), "", fecha));
        guardar();
        return id;
    }

    public synchronized boolean quitar(String id) {
        boolean r = lista.removeIf(p -> p.id().equals(id));
        if (r) {
            guardar();
        }
        return r;
    }

    public synchronized List<Programada> todas() {
        return List.copyOf(lista);
    }

    /** Las que tocan ahora (su hora ya pasó hoy y no se han hecho hoy). Las marca como hechas hoy. Si el programa estuvo apagado a esa hora, se hacen al volver el mismo día. */
    public synchronized List<Programada> vencidas(LocalDateTime ahora) {
        List<Programada> r = new ArrayList<>();
        for (int i = 0; i < lista.size(); i++) {
            Programada p = lista.get(i);
            LocalDate hoy = ahora.toLocalDate();
            if (!p.fecha().isEmpty()) {
                if (!hoy.isBefore(LocalDate.parse(p.fecha())) && (hoy.isAfter(LocalDate.parse(p.fecha()))
                        || !ahora.toLocalTime().isBefore(LocalTime.parse(p.hora())))) {
                    r.add(p);
                    lista.remove(i--);
                }
                continue;
            }
            if (p.dias().contains(hoy.getDayOfWeek()) && !ahora.toLocalTime().isBefore(LocalTime.parse(p.hora()))
                    && !hoy.toString().equals(p.ultima())) {
                lista.set(i, new Programada(p.id(), p.origen(), p.quien(), p.hora(), p.dias(), p.peticion(), hoy.toString(), ""));
                r.add(p);
            }
        }
        if (!r.isEmpty()) {
            guardar();
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private void cargar() {
        try {
            if (!Files.exists(fichero)) {
                return;
            }
            for (Object o : (List<Object>) Json.objeto(Files.readString(fichero, StandardCharsets.UTF_8)).getOrDefault("programadas", List.of())) {
                Map<String, Object> m = (Map<String, Object>) o;
                java.util.EnumSet<DayOfWeek> d = java.util.EnumSet.noneOf(DayOfWeek.class);
                for (Object x : (List<Object>) m.get("dias")) {
                    d.add(DayOfWeek.valueOf(String.valueOf(x)));
                }
                lista.add(new Programada(String.valueOf(m.get("id")), String.valueOf(m.get("origen")), String.valueOf(m.get("quien")),
                        String.valueOf(m.get("hora")), d, String.valueOf(m.get("peticion")), String.valueOf(m.getOrDefault("ultima", "")), String.valueOf(m.getOrDefault("fecha", ""))));
            }
        } catch (IOException | RuntimeException e) {
            // un fichero estropeado no tumba a Jarvis
        }
    }

    private void guardar() {
        try {
            List<Object> l = new ArrayList<>();
            for (Programada p : lista) {
                l.add(Json.obj("id", p.id(), "origen", p.origen(), "quien", p.quien(), "hora", p.hora(),
                        "dias", p.dias().stream().map(DayOfWeek::name).toList(), "peticion", p.peticion(), "ultima", p.ultima(), "fecha", p.fecha()));
            }
            Files.createDirectories(fichero.toAbsolutePath().getParent());
            Path tmp = fichero.resolveSibling(fichero.getFileName() + ".tmp");
            Files.writeString(tmp, Json.escribir(new LinkedHashMap<>(Map.of("programadas", l))), StandardCharsets.UTF_8);
            Files.move(tmp, fichero, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // sin sitio donde escribir
        }
    }
}
