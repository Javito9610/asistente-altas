package es.amagna.asistente.cerebro;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Un especialista: un agente preparado para UNA cosa y con las manos atadas para todo lo demás.
 *
 * Un agente no es «Claude con una clave». Un agente es cuatro cosas: el cerebro, las herramientas que le das, las
 * instrucciones que le das y, sobre todo, LO QUE NO LE DEJAS HACER. Lo último es lo que más se olvida y lo que más
 * importa en un despacho: el cartógrafo, por ejemplo, no puede escribir ni pulsar nada aunque quiera y aunque se lo
 * pidan — no es que se lo digan las instrucciones, es que el programa le devuelve un error si lo intenta.
 *
 * Cada especialista es un fichero en «instrucciones/agentes/»: una cabecera con sus parámetros y debajo lo que tiene
 * que saber. Todos heredan las normas comunes (base.md) y añaden las suyas, así que ninguno empieza de cero.
 *
 * <pre>
 * ---
 * modelo: claude-sonnet-5-5
 * pasos: 30
 * tope-tokens: 150000
 * puede: ver_controles, leer_control, ventanas_abiertas, screenshot, zoom
 * ---
 * (lo que este especialista tiene que saber)
 * </pre>
 *
 * «puede» es una lista blanca: lo que no esté ahí, no se puede hacer. Si no se pone, puede todo.
 *
 * «base: no» es para un especialista que no toca el ordenador: el que lee documentos, por ejemplo, no necesita saber
 * nada de ventanas ni de ratones, y contarle todo eso solo le da ruido donde tiene que haber precisión.
 *
 * @package Amagna\Altas
 */
public record Especialista(String nombre, String modelo, int pasos, long topeTokens, Set<String> puede, String instrucciones) {

    /** Los que hay. */
    public static final List<String> NOMBRES = List.of("cartografo", "desatascador", "consulta", "altas");

    public static Especialista cargar(String nombre, String modeloPorDefecto, long topePorDefecto) throws IOException {
        String bruto = leer("/instrucciones/agentes/" + nombre + ".md");
        if (bruto == null) {
            throw new IllegalArgumentException("No hay ningún especialista que se llame «" + nombre + "». Los que hay: "
                    + String.join(", ", NOMBRES));
        }
        String modelo = modeloPorDefecto;
        int pasos = 20;
        long tope = topePorDefecto;
        Set<String> puede = new LinkedHashSet<>();
        boolean conBase = true;
        String cuerpo = bruto;
        if (bruto.startsWith("---")) {
            int fin = bruto.indexOf("\n---", 3);
            if (fin > 0) {
                for (String linea : bruto.substring(3, fin).split("\n")) {
                    int dp = linea.indexOf(':');
                    if (dp <= 0) {
                        continue;
                    }
                    String k = linea.substring(0, dp).trim();
                    String v = linea.substring(dp + 1).trim();
                    switch (k) {
                        case "modelo" -> modelo = v;
                        case "pasos" -> pasos = Integer.parseInt(v);
                        case "tope-tokens" -> tope = Long.parseLong(v);
                        case "base" -> conBase = !v.equalsIgnoreCase("no");
                        case "puede" -> {
                            for (String h : v.split(",")) {
                                if (!h.isBlank()) {
                                    puede.add(h.trim());
                                }
                            }
                        }
                        default -> { }
                    }
                }
                cuerpo = bruto.substring(bruto.indexOf('\n', fin + 1) + 1);
            }
        }
        String base = conBase ? leer("/instrucciones/base.md") : null;
        return new Especialista(nombre, modelo, pasos, tope, Set.copyOf(puede),
                base == null ? cuerpo.strip()
                        : base + "\n\n# Tu especialidad: " + nombre + "\n" + cuerpo.strip());
    }

    private static String leer(String ruta) throws IOException {
        try (InputStream in = Especialista.class.getResourceAsStream(ruta)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
