package es.amagna.asistente.guion;

import es.amagna.asistente.Json;
import es.amagna.asistente.manos.Controles;
import es.amagna.asistente.manos.VentanasNativas;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * El guion: hacer una tarea entera de forma determinista, sin inteligencia artificial, y comprobándola.
 *
 * Esta es la parte que hace que dar un alta en MN no dependa cada noche de que el agente acierte. El guion dice qué
 * hay que hacer, el {@link Mapa} dice dónde está cada cosa, y aquí se ejecuta siempre igual:
 *
 * <ol>
 *   <li><b>Encuentra cada control probando sus apodos</b> por orden. Si el primero ya no vale y funciona otro, lo
 *       ejecuta igualmente y lo avisa en el parte: así una actualización de MN no para el trabajo, solo deja un aviso.</li>
 *   <li><b>Comprueba cada paso al hacerlo.</b> Lo escrito se relee: solo vale IGUAL.</li>
 *   <li><b>Doble lectura al final.</b> Cuando ha terminado, vuelve a leer TODO lo que escribió y lo compara con lo que
 *       tenía que poner. Como el arqueo de una caja: si algo no cuadra, no se da por bueno.</li>
 *   <li><b>No repite un trabajo ya hecho.</b> Cada guion lleva una clave (por ejemplo el alta). Si esa clave ya se
 *       completó, no se vuelve a ejecutar: es lo que impide crear dos veces el mismo cliente.</li>
 *   <li><b>Ensayo.</b> En modo ensayo recorre el guion entero y comprueba que encuentra todos los controles, pero no
 *       escribe ni pulsa nada. Sirve para decir con pruebas «esto va a funcionar» antes de tocar MN.</li>
 * </ol>
 *
 * Cuando algo falla, para y cuenta exactamente qué esperaba y qué había: entonces (y solo entonces) tiene sentido que
 * entre la inteligencia artificial a resolverlo.
 *
 * Un guion es un fichero así:
 * <pre>
 * {
 *   "clave": "alta-2026-000123",
 *   "pasos": [
 *     {"hacer": "esperar",  "control": "nif", "que": "aparezca", "segundos": 30},
 *     {"hacer": "escribir", "control": "nif", "valor": "00000000T"},
 *     {"hacer": "pulsar",   "control": "guardar", "toca": true,
 *      "luego": {"control": "aviso", "que": "aparezca", "segundos": 20}}
 *   ]
 * }
 * </pre>
 * «toca: true» marca los pasos que cambian algo de verdad: son los que el ensayo no hace.
 *
 * @package Amagna\Altas
 */
public final class Guion {
    private final Controles controles;
    private final VentanasNativas ventanas;
    private final Mapa mapa;
    private final Consumer<String> registro;

    public Guion(Controles controles, VentanasNativas ventanas, Mapa mapa, Consumer<String> registro) {
        this.controles = controles;
        this.ventanas = ventanas;
        this.mapa = mapa;
        this.registro = registro;
    }

    public record Parte(boolean bien, String resumen, List<String> lineas, int hechos, int avisos) {
    }

    /** Dónde se apunta lo ya hecho, para no repetirlo nunca. */
    public static Path carpetaHechos() {
        String local = System.getenv("LOCALAPPDATA");
        return Path.of(local != null ? local : System.getProperty("user.home"), "AsistenteAltas", "hechos");
    }

    public Parte ejecutar(Path fichero, boolean ensayo) throws IOException {
        Map<String, Object> g = Json.objeto(Files.readString(fichero, StandardCharsets.UTF_8));
        String clave = String.valueOf(g.getOrDefault("clave", "")).trim();
        List<String> lineas = new ArrayList<>();
        int avisos = 0;

        if (!ensayo && !clave.isEmpty()) {
            Path hecho = carpetaHechos().resolve(limpio(clave) + ".txt");
            if (Files.isRegularFile(hecho)) {
                String cuando = Files.readString(hecho, StandardCharsets.UTF_8).strip();
                return new Parte(false, "NO SE REPITE: «" + clave + "» ya se hizo el " + cuando
                        + ". Si de verdad hay que rehacerlo, hay que borrar " + hecho + " a mano.", lineas, 0, 0);
            }
        }

        List<Object> pasos = g.get("pasos") instanceof List<?> l ? new ArrayList<>(l) : List.of();
        if (pasos.isEmpty()) {
            return new Parte(false, "El guion no tiene pasos.", lineas, 0, 0);
        }

        // Lo escrito se apunta para la doble lectura del final.
        Map<String, String[]> escrito = new LinkedHashMap<>(); // control -> {apodo que funcionó, valor}
        int hechos = 0;

        for (Object o : pasos) {
            if (!(o instanceof Map<?, ?> crudo)) {
                continue;
            }
            Map<String, Object> m = mapaDe(crudo);
            String hacer = String.valueOf(m.getOrDefault("hacer", "")).trim();
            String control = String.valueOf(m.getOrDefault("control", "")).trim();
            String valor = m.get("valor") == null ? null : String.valueOf(m.get("valor"));
            boolean toca = Boolean.TRUE.equals(m.get("toca"));
            double segundos = m.get("segundos") instanceof Number n ? n.doubleValue() : 20;

            String apodo;
            try {
                apodo = resolver(control, hacer.equals("esperar") ? segundos : 0);
            } catch (Fallo f) {
                lineas.add("FALLA  " + hacer + " " + control + ": " + f.getMessage());
                return parte(false, "PARADO en «" + hacer + " " + control + "»: " + f.getMessage(), lineas, hechos, avisos, clave, ensayo);
            }
            List<String> todos = mapa.apodos(control);
            if (!apodo.equals(todos.get(0))) {
                avisos++;
                lineas.add("AVISO  «" + control + "»: «" + todos.get(0) + "» ya no vale; ha funcionado «" + apodo
                        + "». Conviene actualizar el mapa.");
            }

            if (ensayo && toca) {
                lineas.add("ENSAYO " + hacer + " " + control + " (" + apodo + "): encontrado, NO se toca");
                hechos++;
                continue;
            }

            String r;
            switch (hacer) {
                case "escribir" -> {
                    if (valor == null) {
                        return parte(false, "El paso «escribir " + control + "» no dice qué valor poner.", lineas, hechos, avisos, clave, ensayo);
                    }
                    if (ensayo) {
                        lineas.add("ENSAYO escribir " + control + " (" + apodo + "): encontrado, NO se escribe");
                        hechos++;
                        continue;
                    }
                    r = controles.escribir(mapa.ventana(), apodo, valor);
                    if (!r.startsWith("IGUAL")) {
                        lineas.add("FALLA  escribir " + control + ": " + r);
                        return parte(false, "PARADO al escribir «" + control + "»: " + r, lineas, hechos, avisos, clave, ensayo);
                    }
                    escrito.put(control, new String[] {apodo, valor});
                }
                case "pulsar" -> {
                    r = controles.pulsar(mapa.ventana(), apodo);
                    if (!r.startsWith("Pulsado") && !r.startsWith("Desplegado")) {
                        lineas.add("FALLA  pulsar " + control + ": " + r);
                        return parte(false, "PARADO al pulsar «" + control + "»: " + r, lineas, hechos, avisos, clave, ensayo);
                    }
                }
                case "esperar" -> {
                    Controles.Que que = switch (String.valueOf(m.getOrDefault("que", "aparezca"))) {
                        case "desaparezca" -> Controles.Que.DESAPAREZCA;
                        case "se active" -> Controles.Que.SE_ACTIVE;
                        case "valga" -> Controles.Que.VALGA;
                        default -> Controles.Que.APAREZCA;
                    };
                    r = controles.esperar(mapa.ventana(), apodo, que, valor, segundos);
                    if (!r.startsWith("Ya está")) {
                        lineas.add("FALLA  esperar " + control + ": " + r);
                        return parte(false, "PARADO esperando a «" + control + "»: " + r, lineas, hechos, avisos, clave, ensayo);
                    }
                }
                case "comprobar" -> {
                    String hay = controles.valorDe(mapa.ventana(), apodo);
                    if (valor != null && !valor.equals(hay)) {
                        lineas.add("FALLA  comprobar " + control + ": se esperaba «" + valor + "» y hay «" + hay + "»");
                        return parte(false, "PARADO: «" + control + "» tenía que valer «" + valor + "» y vale «" + hay + "».",
                                lineas, hechos, avisos, clave, ensayo);
                    }
                    r = "vale «" + hay + "»";
                }
                default -> {
                    return parte(false, "El guion pide «" + hacer + "», que no se sabe hacer (escribir, pulsar, esperar, comprobar).",
                            lineas, hechos, avisos, clave, ensayo);
                }
            }
            lineas.add("ok     " + hacer + " " + control + (valor == null ? "" : " = «" + valor + "»"));
            hechos++;

            // Condición de después: no se sigue hasta que el programa reaccione de verdad.
            if (m.get("luego") instanceof Map<?, ?> crudo2) {
                Map<String, Object> l2 = mapaDe(crudo2);
                String ctrl2 = String.valueOf(l2.getOrDefault("control", control));
                double s2 = l2.get("segundos") instanceof Number n2 ? n2.doubleValue() : 20;
                Controles.Que que2 = switch (String.valueOf(l2.getOrDefault("que", "aparezca"))) {
                    case "desaparezca" -> Controles.Que.DESAPAREZCA;
                    case "se active" -> Controles.Que.SE_ACTIVE;
                    case "valga" -> Controles.Que.VALGA;
                    default -> Controles.Que.APAREZCA;
                };
                String v2 = l2.get("valor") == null ? null : String.valueOf(l2.get("valor"));
                String apodo2;
                try {
                    apodo2 = resolver(ctrl2, s2);
                } catch (Fallo f) {
                    if (ensayo) {
                        lineas.add("ENSAYO después de " + hacer + " " + control + ": no se comprueba (no se ha tocado nada)");
                        continue;
                    }
                    lineas.add("FALLA  después de " + hacer + " " + control + ": " + f.getMessage());
                    return parte(false, "PARADO: después de «" + hacer + " " + control + "» no ha pasado lo que tenía que pasar: "
                            + f.getMessage(), lineas, hechos, avisos, clave, ensayo);
                }
                String r2 = controles.esperar(mapa.ventana(), apodo2, que2, v2, s2);
                if (!r2.startsWith("Ya está")) {
                    lineas.add("FALLA  después de " + hacer + " " + control + ": " + r2);
                    return parte(false, "PARADO: después de «" + hacer + " " + control + "»: " + r2, lineas, hechos, avisos, clave, ensayo);
                }
                lineas.add("ok     después de " + hacer + " " + control + ": " + r2);
            }
        }

        // La doble lectura: todo lo escrito se vuelve a leer y se compara. Como arquear una caja.
        if (!ensayo && !escrito.isEmpty()) {
            List<String> mal = new ArrayList<>();
            for (Map.Entry<String, String[]> e : escrito.entrySet()) {
                String hay = controles.valorDe(mapa.ventana(), e.getValue()[0]);
                if (!e.getValue()[1].equals(hay)) {
                    mal.add("«" + e.getKey() + "» tenía que valer «" + e.getValue()[1] + "» y vale «" + hay + "»");
                }
            }
            if (!mal.isEmpty()) {
                lineas.add("FALLA  doble lectura: " + String.join("; ", mal));
                return parte(false, "PARADO en la comprobación final: " + String.join("; ", mal), lineas, hechos, avisos, clave, ensayo);
            }
            lineas.add("ok     doble lectura: los " + escrito.size() + " datos escritos siguen siendo exactos");
        }

        String resumen = (ensayo ? "ENSAYO CORRECTO: " : "HECHO: ") + hechos + " pasos"
                + (escrito.isEmpty() ? "" : ", " + escrito.size() + " datos comprobados dos veces")
                + (avisos == 0 ? "" : " · " + avisos + " aviso(s) para actualizar el mapa");
        return parte(true, resumen, lineas, hechos, avisos, clave, ensayo);
    }

    private static Map<String, Object> mapaDe(Map<?, ?> crudo) {
        Map<String, Object> r = new LinkedHashMap<>();
        crudo.forEach((k, v) -> r.put(String.valueOf(k), v));
        return r;
    }

    /** Prueba los apodos del mapa por orden y devuelve el que funciona. */
    private String resolver(String control, double esperarSegundos) {
        List<String> apodos = mapa.apodos(control);
        long fin = System.currentTimeMillis() + (long) (Math.max(0, esperarSegundos) * 1000);
        do {
            for (String a : apodos) {
                if (controles.existe(mapa.ventana(), a)) {
                    return a;
                }
            }
            if (System.currentTimeMillis() >= fin) {
                break;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (true);
        throw new Fallo("no se encuentra «" + control + "» en la ventana «" + mapa.ventana() + "» de ninguna de sus formas ("
                + String.join(" / ", apodos) + "). O no estamos en esa pantalla, o el programa ha cambiado y hay que repasar el mapa.");
    }

    private Parte parte(boolean bien, String resumen, List<String> lineas, int hechos, int avisos, String clave, boolean ensayo) {
        lineas.forEach(registro);
        registro.accept(resumen);
        if (bien && !ensayo && !clave.isEmpty()) {
            try {
                Files.createDirectories(carpetaHechos());
                Files.writeString(carpetaHechos().resolve(limpio(clave) + ".txt"), LocalDateTime.now().toString(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                registro.accept("AVISO: no se ha podido apuntar que «" + clave + "» ya está hecho: " + e.getMessage());
            }
        }
        return new Parte(bien, resumen, lineas, hechos, avisos);
    }

    /** Abre el programa del mapa si no está ya abierto. */
    public String abrirSiHaceFalta() {
        if (ventanas.handleDe(mapa.ventana()) != null) {
            return "Ya estaba abierto.";
        }
        if (mapa.ejecutable().isBlank()) {
            return "No está abierto «" + mapa.ventana() + "» y el mapa no dice con qué programa se abre.";
        }
        ventanas.abrir(mapa.ejecutable(), mapa.argumentos());
        return controles.esperar(mapa.ventana(), null, Controles.Que.APAREZCA, null, 60);
    }

    private static String limpio(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Lo que pasa cuando el programa no está donde el mapa dice. */
    public static final class Fallo extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Fallo(String m) {
            super(m);
        }
    }
}
