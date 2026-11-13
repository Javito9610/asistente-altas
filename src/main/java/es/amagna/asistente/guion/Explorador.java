package es.amagna.asistente.guion;

import es.amagna.asistente.Json;
import es.amagna.asistente.manos.Controles;
import es.amagna.asistente.manos.VentanasNativas;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * El explorador: levanta el plano de un programa ENTERO, no de una pantalla.
 *
 * Va en dos tiempos, y el orden no es un capricho:
 *
 * <p><b>1. El plano, sin tocar nada.</b> Lee el árbol de menús completo y saca todas las pantallas que el programa
 * ofrece, sin abrir ni una. Esto es gratis, instantáneo y no puede hacer daño.
 *
 * <p><b>2. Entrar en cada una.</b> Para saber qué campos tiene una pantalla hay que abrirla, y ahí está el peligro:
 * en un programa de gestión hay menús que abren una pantalla y menús que HACEN algo, y por fuera son idénticos. En MN,
 * «Fichaje → Inicio Jornada» ficha tu entrada y «Cierre/Apertura Ejercicio → Cierre Ejercicio» cierra el ejercicio
 * contable. Abrir menús a lo loco en el programa de un despacho es una barbaridad.
 *
 * Por eso el paso 2 tiene tres cerrojos:
 * <ul>
 *   <li>Solo se ejecuta si el título dice que estamos en la EMPRESA DE PRUEBA. Fuera de ahí no se abre nada.</li>
 *   <li>Las opciones que suenan a acción (fichar, cerrar, borrar, importar, generar, actualizar…) quedan marcadas
 *       como peligrosas y NO se abren, aunque se pidan.</li>
 *   <li>El plano del paso 1 se repasa a mano antes del paso 2: nadie abre nada que una persona no haya aprobado.</li>
 * </ul>
 *
 * @package Amagna\Altas
 */
public final class Explorador {
    /** Sin esto en el título, no se abre nada: no vamos a pasear por la empresa de verdad. */
    public static final String EMPRESA_PRUEBA = "empresa de prueba";

    /** Si una opción lleva una de estas palabras, no se toca: no abre una pantalla, hace algo. */
    static final List<String> PELIGROSAS = List.of(
            "fichaje", "inicio jornada", "fin jornada", "inicio descanso", "fin descanso",
            "cierre", "apertura", "invertir", "papelera", "eliminar", "borrar", "vaciar",
            "importar", "importacion", "exportar", "traspaso", "generar", "actualizar",
            "enviar", "remesa", "cobro", "pago", "facturar", "contabilizar", "chequeo", "copia",
            // Los módulos que hablan con el mundo exterior: abrirlos puede bajar o mandar cosas sin preguntar.
            // En un despacho, que Lexnet descargue una notificación al entrar arranca un plazo. No se tocan.
            "lexnet", "correo", "whatsapp", "sign", "notificacion");

    private Explorador() {
    }

        private static final String ENCARGO = """
            Eres el Asistente Altas de Amagna. Te paso el menú entero de un programa de gestión de un despacho de
            abogados. Vamos a cartografiarlo, y para eso hay que ENTRAR en cada pantalla.

            El peligro es este: unas opciones de menú ABREN una pantalla (inofensivo, es navegar) y otras HACEN algo
            (fichan una entrada, cierran un ejercicio contable, borran, envían, recalculan datos de todo el despacho).
            Por fuera son iguales. Tu trabajo es distinguirlas, UNA POR UNA.

            Contesta SOLO con un JSON, sin explicaciones ni ```:
            {"opciones": [{"ruta": "Bases de Datos > Clientes", "que_hace": "abre", "por_que": "es una base de datos"}, ...]}

            «que_hace» solo puede ser:
              "abre"  -> abre una pantalla, una ficha, un listado o un buscador. Entrar no cambia nada.
              "hace"  -> ejecuta algo: fichar, cerrar, abrir ejercicio, invertir, borrar, vaciar, importar, exportar,
                         generar, actualizar, enviar, remesar, cobrar, pagar, facturar, contabilizar, chequear, copiar.
              "duda"  -> no lo tienes claro.

            Reglas:
            - Ante la más mínima duda, "duda". No pasa NADA por dejar una pantalla sin cartografiar; sí pasa por
              cerrarle el ejercicio contable a un despacho.
            - Un submenú que solo despliega otros ("Configuraciones", "Ficheros") es "abre".
            - Devuelve TODAS las rutas que te paso, ninguna de más y ninguna de menos, con la ruta escrita igual.
            """;

    /** Lo que opina la IA de cada opción de menú. */
    public record Juicio(String queHace, String porQue) {
    }

    /**
     * La IA lee el menú entero y dice, una por una, cuáles abren una pantalla y cuáles ejecutan algo. Esto es criterio,
     * no se programa con una lista de palabras: «Actualizar %IVA» y «Actualizar listado» se parecen mucho y no tienen
     * nada que ver.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Juicio> clasificar(es.amagna.asistente.cerebro.Cerebro cerebro, String modelo, List<Pantalla> plano) {
        StringBuilder lista = new StringBuilder();
        plano.forEach(p -> lista.append(p.comoTexto()).append('\n'));
        Map<String, Object> r = cerebro.pensar(Json.obj(
                "model", modelo, "max_tokens", 8192, "system", ENCARGO,
                "messages", List.of(Json.obj("role", "user", "content", lista.toString()))));
        StringBuilder texto = new StringBuilder();
        for (Object o : (List<Object>) r.getOrDefault("content", List.of())) {
            if (o instanceof Map<?, ?> b && "text".equals(b.get("type"))) {
                texto.append(b.get("text"));
            }
        }
        String t = texto.toString();
        int a = t.indexOf('{');
        int z = t.lastIndexOf('}');
        if (a < 0 || z <= a) {
            throw new IllegalStateException("La IA no ha contestado con una lista: " + (t.length() > 200 ? t.substring(0, 200) + "…" : t));
        }
        Map<String, Juicio> juicios = new LinkedHashMap<>();
        Object ops = Json.objeto(t.substring(a, z + 1)).get("opciones");
        if (ops instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m && m.get("ruta") != null) {
                    String que = (m.get("que_hace") == null ? "duda" : String.valueOf(m.get("que_hace"))).trim().toLowerCase(Locale.ROOT);
                    juicios.put(String.valueOf(m.get("ruta")).trim(),
                            new Juicio(que.equals("abre") || que.equals("hace") ? que : "duda",
                                    m.get("por_que") == null ? "" : String.valueOf(m.get("por_que"))));
                }
            }
        }
        if (juicios.isEmpty()) {
            throw new IllegalStateException("La IA no ha clasificado ninguna opción.");
        }
        return juicios;
    }

    /**
     * Junta las dos opiniones. Y aquí está lo importante: para entrar en una pantalla tienen que estar de acuerdo
     * LAS DOS. La IA pone el criterio (entiende lo que significa cada opción) y la lista de palabras pone el suelo
     * (no depende de que la IA acierte). Si una de las dos dice que no, no se entra. Lo que la IA no haya clasificado,
     * tampoco se toca.
     */
    public static List<Pantalla> juntar(List<Pantalla> plano, Map<String, Juicio> juicios) {
        List<Pantalla> r = new ArrayList<>();
        for (Pantalla p : plano) {
            String porPalabra = porQueNoSeToca(p.ruta());
            Juicio j = juicios.get(p.comoTexto());
            if (porPalabra != null) {
                r.add(new Pantalla(p.ruta(), false, porPalabra));
            } else if (j == null) {
                r.add(new Pantalla(p.ruta(), false, "la IA no la ha clasificado: no se toca"));
            } else if (j.queHace().equals("abre")) {
                r.add(new Pantalla(p.ruta(), true, null));
            } else {
                r.add(new Pantalla(p.ruta(), false, "la IA dice que «" + j.queHace() + "»"
                        + (j.porQue().isBlank() ? "" : ": " + j.porQue())));
            }
        }
        return r;
    }

    public record Pantalla(List<String> ruta, boolean seguro, String motivo) {
        public String comoTexto() {
            return String.join(" > ", ruta);
        }
    }

    /** Deja en el plano solo las pantallas cuya ruta contenga uno de estos textos (y marca el resto como no aprobadas). */
    public static List<Pantalla> soloEstas(List<Pantalla> plano, List<String> quiero) {
        List<Pantalla> r = new ArrayList<>();
        for (Pantalla p : plano) {
            boolean pedida = quiero.stream().anyMatch(q -> p.comoTexto().toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT).trim()));
            r.add(pedida ? p : new Pantalla(p.ruta(), false, "no está en la lista de las que se piden"));
        }
        return r;
    }

    /** Paso 1: el plano. Solo mira. */
    public static List<Pantalla> plano(Controles ctrl, String ventana, int hondo) {
        List<List<String>> todas = ctrl.rutasDeMenu(ventana, hondo);
        List<Pantalla> r = new ArrayList<>();
        for (List<String> ruta : todas) {
            String motivo = esSubmenu(ruta, todas) ? "es un submenú, no una pantalla: lo que hay son las de dentro"
                    : porQueNoSeToca(ruta);
            r.add(new Pantalla(ruta, motivo == null, motivo));
        }
        return r;
    }

    /**
     * ¿Esta opción solo despliega otras? «Bases de Datos» no es una pantalla; «Bases de Datos > Clientes» sí.
     * Pulsar un submenú no abre nada: deja el menú desplegado y deja al programa esperando a que alguien elija.
     */
    public static boolean esSubmenu(List<String> ruta, List<List<String>> todas) {
        for (List<String> otra : todas) {
            if (otra.size() == ruta.size() + 1 && otra.subList(0, ruta.size()).equals(ruta)) {
                return true;
            }
        }
        return false;
    }

    public static String porQueNoSeToca(List<String> ruta) {
        String todo = String.join(" > ", ruta).toLowerCase(Locale.ROOT);
        for (String mala : PELIGROSAS) {
            if (todo.contains(mala)) {
                return "lleva «" + mala + "»: esto no abre una pantalla, hace algo";
            }
        }
        return null;
    }

    public static void guardarPlano(Path fichero, String ventana, List<Pantalla> plano) throws IOException {
        List<Object> lista = new ArrayList<>();
        for (Pantalla p : plano) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ruta", p.ruta());
            m.put("abrir", p.seguro());
            if (p.motivo() != null) {
                m.put("por_que_no", p.motivo());
            }
            lista.add(m);
        }
        Files.writeString(fichero, Json.escribir(Json.obj("ventana", ventana, "pantallas", lista)), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static List<Pantalla> leerPlano(Path fichero) throws IOException {
        Map<String, Object> m = Json.objeto(Files.readString(fichero, StandardCharsets.UTF_8));
        List<Pantalla> r = new ArrayList<>();
        for (Object o : (List<Object>) m.getOrDefault("pantallas", List.of())) {
            if (o instanceof Map<?, ?> p && p.get("ruta") instanceof List<?> l) {
                List<String> ruta = new ArrayList<>();
                l.forEach(x -> ruta.add(String.valueOf(x)));
                boolean abrir = Boolean.TRUE.equals(p.get("abrir"));
                String no = porQueNoSeToca(ruta);
                r.add(new Pantalla(ruta, abrir && no == null, no != null ? no : abrir ? null : "no aprobada en el plano"));
            }
        }
        return r;
    }

    /**
     * Paso 2: abre una a una las pantallas aprobadas y guarda sus controles. Devuelve lo que ha hecho.
     * No se ejecuta fuera de la empresa de prueba, y las peligrosas no se abren aunque vengan aprobadas.
     */
    public static String recorrer(Controles ctrl, VentanasNativas ventanas, String ventana, List<Pantalla> plano,
            Path carpeta, double espera, Consumer<String> registro) throws IOException {
        String titulo = ventanas.tituloDe(ventana);
        if (titulo == null) {
            return "No hay ninguna ventana «" + ventana + "».";
        }
        if (!titulo.toLowerCase(Locale.ROOT).contains(EMPRESA_PRUEBA)) {
            return "NO SE ABRE NADA: el título dice «" + titulo + "», y ahí no pone «" + EMPRESA_PRUEBA + "». "
                    + "Explorar abriendo pantallas solo se hace en la empresa de prueba.";
        }
        Files.createDirectories(carpeta);
        int hechas = 0;
        int saltadas = 0;
        int seguidosMal = 0;
        List<String> incidencias = new ArrayList<>();
        List<String> resumen = new ArrayList<>();
        for (Pantalla p : plano) {
            if (!p.seguro()) {
                saltadas++;
                continue;
            }
            // Antes de entrar, el programa tiene que estar libre: si quedó un aviso de la pantalla anterior, se cierra.
            List<String> bloqueos = ventanas.cancelarBloqueos();
            if (!bloqueos.isEmpty()) {
                registro.accept("   (venía bloqueado; se han cerrado: " + String.join(", ", bloqueos) + ")");
            }
            registro.accept("→ " + p.comoTexto());
            java.util.Set<Long> antes = new java.util.HashSet<>(VentanasNativas.instantanea().keySet());
            // Con tiempo máximo: una llamada a un programa ajeno puede quedarse colgada para siempre (un menú que se
            // despliega y espera, un cuadro que no termina de pintarse). Un recorrido de 40 pantallas no se puede
            // perder porque una se atasque.
            String r = conTiempo(30, () -> ctrl.pulsarRuta(ventana, p.ruta()), "se ha quedado colgado al pulsar");
            if (!r.startsWith("Pulsado")) {
                escapar();
                incidencias.add(p.comoTexto() + ": no se pudo entrar (" + r + ")");
                resumen.add("- " + p.comoTexto() + " · NO SE PUDO ENTRAR");
                if (++seguidosMal >= 3) {
                    return parada(carpeta, resumen, incidencias, hechas, saltadas,
                            "Tres pantallas seguidas sin poder entrar: se para para no dejar MN hecho un lío.");
                }
                continue;
            }

            // Hay que ESPERAR a que la ventana aparezca de verdad, no un rato a ojo. Las pantallas grandes de MN
            // (Clientes, Expedientes) tardan bastante más que las pequeñas, y volcar la ventana principal mientras
            // tanto da un fichero que no sirve para nada y encima parece bueno.
            Long nuevaH = esperarVentanaNueva(antes, Math.max(espera, 2) * 6);
            if (nuevaH == null) {
                String bloqueada = ventanas.abiertas().contains("BLOQUEADA")
                        ? " (hay algo bloqueando: " + String.join(", ", ventanas.cancelarBloqueos()) + ")" : "";
                incidencias.add(p.comoTexto() + ": no se ha abierto ninguna ventana" + bloqueada
                        + ". O no abre pantalla, o tarda más de la cuenta: hay que verla con una persona delante.");
                resumen.add("- " + p.comoTexto() + " · NO ABRIÓ NINGUNA VENTANA");
                registro.accept("   no se ha abierto ninguna ventana; se anota y se sigue");
                seguidosMal = 0;
                continue;
            }
            String nueva = VentanasNativas.instantanea().get(nuevaH);
            esperarQuieto(espera); // un respiro para que termine de pintarse

            if (ventanas.abiertas().contains("BLOQUEADA")) {
                List<String> cerrado = ventanas.cancelarBloqueos();
                incidencias.add(p.comoTexto() + ": al entrar pide algo (" + String.join(", ", cerrado)
                        + "). Hay que verla con una persona delante.");
                resumen.add("- " + p.comoTexto() + " · PIDE ALGO AL ENTRAR (no cartografiada)");
                cerrarDeVerdad(ventanas, nuevaH, nueva);
                seguidosMal = 0;
                continue;
            }
            seguidosMal = 0;

            String arbol = conTiempo(45, () -> ctrl.arbol(nueva, 14, 3000), "se ha quedado colgado al leer la pantalla");
            if (arbol.startsWith("se ha quedado colgado")) {
                incidencias.add(p.comoTexto() + ": " + arbol);
                resumen.add("- " + p.comoTexto() + " · SE QUEDÓ COLGADA AL LEERLA");
                escapar();
                cerrarDeVerdad(ventanas, nuevaH, nueva);
                continue;
            }
            Path f = carpeta.resolve(limpio(p.comoTexto()) + ".txt");
            Files.writeString(f, "Pantalla: " + p.comoTexto() + "\nVentana: " + nueva + "\n\n" + arbol, StandardCharsets.UTF_8);
            long lineas = arbol.lines().count();
            registro.accept("   «" + nueva + "» · " + lineas + " líneas → " + f.getFileName());
            resumen.add("- " + p.comoTexto() + " · ventana «" + nueva + "» · " + lineas + " líneas · " + f.getFileName());
            hechas++;

            // Y se cierra. Si no, se van apilando pantallas hasta que MN se atasca.
            if (!cerrarDeVerdad(ventanas, nuevaH, nueva)) {
                incidencias.add(p.comoTexto() + ": la pantalla «" + nueva + "» no se ha dejado cerrar; ciérrala tú.");
                registro.accept("   OJO: «" + nueva + "» no se ha cerrado");
            }
        }
        return parada(carpeta, resumen, incidencias, hechas, saltadas, null);
    }

    /** Escribe el resumen de lo recorrido y devuelve la frase final. */
    private static String parada(Path carpeta, List<String> resumen, List<String> incidencias, int hechas, int saltadas,
            String corte) throws IOException {
        StringBuilder b = new StringBuilder("# Recorrido de pantallas\n\n");
        resumen.forEach(l -> b.append(l).append('\n'));
        if (!incidencias.isEmpty()) {
            b.append("\n## Lo que hay que mirar a mano\n\n");
            incidencias.forEach(l -> b.append("- ").append(l).append('\n'));
        }
        Files.writeString(carpeta.resolve("resumen.md"), b.toString(), StandardCharsets.UTF_8);
        return (corte == null ? "" : corte + " ")
                + "Recorridas " + hechas + " pantallas, saltadas " + saltadas + " (no aprobadas o peligrosas)"
                + (incidencias.isEmpty() ? "" : ", " + incidencias.size() + " piden algo al entrar y quedan para mirar a mano")
                + ". Todo en " + carpeta.toAbsolutePath() + " (empieza por resumen.md).";
    }

    /** Hace algo dándole un tiempo máximo; si se pasa, lo deja por imposible en vez de colgar el recorrido entero. */
    public static <T> T conTiempo(int segundos, java.util.concurrent.Callable<T> tarea, T siTarda) {
        java.util.concurrent.ExecutorService hilo = java.util.concurrent.Executors.newSingleThreadExecutor(h -> {
            Thread t = new Thread(h, "explorador");
            t.setDaemon(true);
            return t;
        });
        try {
            return hilo.submit(tarea).get(segundos, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return siTarda;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return siTarda;
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e.getCause() == null ? e : e.getCause());
        } finally {
            hilo.shutdownNow();
        }
    }

    private static String conTiempo(int segundos, java.util.concurrent.Callable<String> tarea, String siTarda) {
        java.util.concurrent.ExecutorService hilo = java.util.concurrent.Executors.newSingleThreadExecutor(h -> {
            Thread t = new Thread(h, "explorador");
            t.setDaemon(true); // si se queda colgado, que no impida cerrar el programa
            return t;
        });
        try {
            return hilo.submit(tarea).get(segundos, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return siTarda + " (más de " + segundos + " s)";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrumpido";
        } catch (java.util.concurrent.ExecutionException e) {
            return "ha fallado: " + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
        } finally {
            hilo.shutdownNow();
        }
    }

    /** Escape, por si ha quedado un menú desplegado esperando a que alguien elija. */
    private static void escapar() {
        try {
            new es.amagna.asistente.manos.ManosRobot(false).tecla("Escape", 2);
            esperarQuieto(0.4);
        } catch (RuntimeException e) {
            // Si no se puede, se sigue igual.
        }
    }

    /** Espera a que aparezca una ventana que antes no estaba, mirando de verdad. Devuelve cuál, o null. */
    private static Long esperarVentanaNueva(java.util.Set<Long> antes, double segundos) {
        long fin = System.currentTimeMillis() + (long) (segundos * 1000);
        do {
            for (Map.Entry<Long, String> e : VentanasNativas.instantanea().entrySet()) {
                if (!antes.contains(e.getKey()) && !e.getValue().isBlank()) {
                    return e.getKey();
                }
            }
            esperarQuieto(0.3);
        } while (System.currentTimeMillis() < fin);
        return null;
    }

    /** Cierra una pantalla y comprueba que se ha ido; si pregunta algo al salir, se cancela. */
    private static boolean cerrarDeVerdad(VentanasNativas ventanas, long handle, String titulo) {
        for (int intento = 1; intento <= 3; intento++) {
            ventanas.cerrarVentana(titulo);
            esperarQuieto(0.6);
            ventanas.cancelarBloqueos();
            esperarQuieto(0.3);
            if (!VentanasNativas.instantanea().containsKey(handle)) {
                return true;
            }
        }
        return false;
    }

    private static void esperarQuieto(double segundos) {
        try {
            Thread.sleep((long) (segundos * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String limpio(String s) {
        return s.replaceAll("[^A-Za-z0-9áéíóúñÁÉÍÓÚÑ ._-]", "_").replace(" > ", "__").replace(' ', '_');
    }
}
