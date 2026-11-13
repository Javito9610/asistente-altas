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
            "enviar", "remesa", "cobro", "pago", "facturar", "contabilizar", "chequeo", "copia");

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

    /** Paso 1: el plano. Solo mira. */
    public static List<Pantalla> plano(Controles ctrl, String ventana, int hondo) {
        List<Pantalla> r = new ArrayList<>();
        for (List<String> ruta : ctrl.rutasDeMenu(ventana, hondo)) {
            String motivo = porQueNoSeToca(ruta);
            r.add(new Pantalla(ruta, motivo == null, motivo));
        }
        return r;
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
            Path carpeta, Consumer<String> registro) throws IOException {
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
        List<String> fallos = new ArrayList<>();
        for (Pantalla p : plano) {
            if (!p.seguro()) {
                saltadas++;
                continue;
            }
            registro.accept("→ " + p.comoTexto());
            String antes = ventanas.abiertas();
            String r = ctrl.pulsarRuta(ventana, p.ruta());
            if (!r.startsWith("Pulsado")) {
                fallos.add(p.comoTexto() + ": " + r);
                continue;
            }
            esperarQuieto(1.5);
            String nueva = ventanaNueva(ventanas, antes);
            String deQuien = nueva != null ? nueva : ventana;
            String arbol = ctrl.arbol(deQuien, 14, 3000);
            Path f = carpeta.resolve(limpio(p.comoTexto()) + ".txt");
            Files.writeString(f, "Pantalla: " + p.comoTexto() + "\nVentana: " + deQuien + "\n\n" + arbol, StandardCharsets.UTF_8);
            registro.accept("   " + arbol.lines().count() + " líneas → " + f.getFileName());
            hechas++;
            if (nueva != null) {
                ventanas.cerrarVentana(nueva); // la hemos abierto nosotros al entrar
                esperarQuieto(0.8);
            }
        }
        return "Recorridas " + hechas + " pantallas, saltadas " + saltadas + " por no estar aprobadas o ser peligrosas"
                + (fallos.isEmpty() ? "" : " · no se pudo entrar en: " + String.join(" · ", fallos))
                + ". Los controles están en " + carpeta.toAbsolutePath() + ".";
    }

    private static String ventanaNueva(VentanasNativas ventanas, String antes) {
        for (String linea : ventanas.abiertas().split("\n")) {
            int a = linea.indexOf('«');
            int b = linea.indexOf('»');
            if (a >= 0 && b > a) {
                String t = linea.substring(a + 1, b);
                if (!antes.contains("«" + t + "»")) {
                    return t;
                }
            }
        }
        return null;
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
