package es.amagna.asistente.jarvis;

import es.amagna.asistente.Json;
import es.amagna.asistente.cerebro.Cerebro;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * El contador de la IA: envuelve al cerebro, apunta los tokens que se gastan cada día (datos\\consumo.json) y corta cuando se llega
 * al tope diario (datos\\limite-tokens.txt, un número; 0 = sin tope; por defecto 3.000.000). Es el freno para un Jarvis que
 * está siempre encendido: un bucle o una racha de encargos no puede gastar sin fin. Al día siguiente vuelve a cero.
 */
public final class Consumo implements Cerebro {
    public static final long TOPE_POR_DEFECTO = 3_000_000;

    private final Cerebro cerebro;
    private final Path fichero;
    private final long tope;
    private String dia = "";
    private long hoy;

    public Consumo(Cerebro cerebro, Path fichero, long tope) {
        this.cerebro = cerebro;
        this.fichero = fichero;
        this.tope = tope;
        leer();
    }

    /** El tope que dice el fichero (o el de por defecto si no está o no se entiende). */
    public static long topeDe(Path limite) {
        try {
            if (Files.isRegularFile(limite)) {
                String t = Files.readString(limite, StandardCharsets.UTF_8).strip().replace(".", "").replace("_", "");
                if (t.matches("\\d{1,12}")) {
                    return Long.parseLong(t);
                }
            }
        } catch (IOException e) {
            // por defecto
        }
        return TOPE_POR_DEFECTO;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> pensar(Map<String, Object> peticion) {
        synchronized (this) {
            cambioDeDia();
            if (tope > 0 && hoy >= tope) {
                throw new IllegalStateException("he llegado al límite diario de IA (" + tope + " tokens). Mañana vuelvo a poder; se cambia en datos\\\\limite-tokens.txt");
            }
        }
        Map<String, Object> r = cerebro.pensar(peticion);
        try {
            Map<String, Object> uso = (Map<String, Object>) r.getOrDefault("usage", Map.of());
            long gasto = num(uso.get("input_tokens")) + num(uso.get("output_tokens")) + num(uso.get("cache_read_input_tokens")) / 10
                    + num(uso.get("cache_creation_input_tokens"));
            synchronized (this) {
                cambioDeDia();
                hoy += gasto;
                guardar();
            }
        } catch (RuntimeException e) {
            // sin datos de uso: no se cuenta, pero no se rompe
        }
        return r;
    }

    public synchronized long hoy() {
        cambioDeDia();
        return hoy;
    }

    public long tope() {
        return tope;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private void cambioDeDia() {
        String d = LocalDate.now().toString();
        if (!d.equals(dia)) {
            dia = d;
            hoy = 0;
        }
    }

    private void leer() {
        try {
            if (Files.isRegularFile(fichero)) {
                Map<String, Object> m = Json.objeto(Files.readString(fichero, StandardCharsets.UTF_8));
                if (LocalDate.now().toString().equals(String.valueOf(m.get("dia")))) {
                    dia = String.valueOf(m.get("dia"));
                    hoy = num(m.get("tokens"));
                }
            }
        } catch (IOException | RuntimeException e) {
            // empieza de cero
        }
    }

    private void guardar() {
        try {
            Files.createDirectories(fichero.toAbsolutePath().getParent());
            Path tmp = fichero.resolveSibling(fichero.getFileName() + ".tmp");
            Files.writeString(tmp, Json.escribir(Json.obj("dia", dia, "tokens", hoy)), StandardCharsets.UTF_8);
            Files.move(tmp, fichero, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // sin sitio donde escribir
        }
    }
}
