package es.amagna.asistente.manos;

import com.sun.jna.Pointer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Los controles de un programa, por dentro (Uia): verlos, leerlos, escribir en ellos, pulsarlos y esperar a que pasen
 * cosas. POR SU NOMBRE, sin coordenadas. Es la forma en que trabajan las herramientas profesionales de automatización,
 * y la que hará que las altas en MN se rellenen campo a campo y se comprueben una a una.
 *
 * <h2>Cómo se nombra un control (el «apodo»)</h2>
 * <pre>
 *   Guardar                  por su nombre
 *   boton:Guardar            solo botones (tipos: boton, campo, desplegable, casilla, opcion-de-menu, fila…)
 *   #txtNif                  por su identificador interno, que es lo que nunca cambia
 *   campo junto a NIF        el cuadro que hay al lado de la etiqueta «NIF» (para formularios sin nombres)
 * </pre>
 * No hace falta acertar con mayúsculas ni acentos. Cada candidato se puntúa (el identificador vale más que el nombre
 * exacto, y este más que un trozo del nombre) y, si los dos mejores se parecen demasiado, NO se toca ninguno y se
 * explica: antes parar que equivocarse.
 *
 * @package Amagna\Altas
 */
public final class Controles {
    private static final int TOPE = 300;
    private static final int HONDO = 14;
    /** Por debajo de esto no se da por bueno un parecido. */
    private static final int MINIMO = 45;
    /** Si el segundo candidato está a menos de esto del primero, hay empate: no se toca nada. */
    private static final int EMPATE = 15;

    private final VentanasNativas ventanas;

    public Controles(VentanasNativas ventanas) {
        this.ventanas = ventanas;
    }

    public record Encontrado(Uia.Elemento elemento, String descripcion) {
    }

    /** Un candidato con su puntuación, ya copiados sus datos (para poder ordenar sin ir y venir al programa). */
    private record Candidato(Uia.Elemento e, int puntos, String tipo, String nombre, String id, int[] recuadro, String etiqueta) {
        String comoSeLlama() {
            return tipo + " «" + (nombre.isBlank() ? (id.isBlank() ? "(sin nombre)" : id) : nombre) + "»";
        }
    }

    // ---------- ver ----------

    /** Lo que el asistente «ve por dentro» de una ventana, en texto. */
    public String arbol(String tituloVentana, int profundidad) {
        return arbol(tituloVentana, profundidad, TOPE);
    }

    /** Igual, pero diciendo cuántos controles como mucho (una ficha de un programa de gestión tiene muchos). */
    public String arbol(String tituloVentana, int profundidad, int tope) {
        ventanas.despertar(tituloVentana);
        Pointer hwnd = ventanas.handleDe(tituloVentana);
        if (hwnd == null) {
            return "No hay ninguna ventana cuyo título contenga «" + tituloVentana + "».";
        }
        Uia.Elemento raiz = Uia.deVentana(hwnd);
        if (raiz == null) {
            return sinUia();
        }
        StringBuilder b = new StringBuilder("Controles de «" + Win32.titulo(hwnd) + "»:\n");
        int[] cuenta = {0};
        try {
            pintar(raiz, b, "", Math.max(1, profundidad), cuenta, tope);
        } finally {
            raiz.close();
        }
        if (cuenta[0] >= tope) {
            b.append("… (hay más; busca uno concreto por su nombre)\n");
        }
        return b.toString().strip();
    }

    private String sinUia() {
        return "No se pueden leer los controles de esa ventana" + (Uia.noDisponible().isEmpty() ? "" : " (" + Uia.noDisponible() + ")")
                + ". Con este programa hay que mirar la pantalla y usar el ratón y el teclado.";
    }

    private void pintar(Uia.Elemento e, StringBuilder b, String sangria, int quedan, int[] cuenta, int tope) {
        for (Uia.Elemento h : e.hijos()) {
            try {
                if (cuenta[0]++ >= tope) {
                    return;
                }
                String nombre = h.nombre();
                String valor = h.valor();
                boolean interesa = !nombre.isBlank() || valor != null;
                if (interesa) {
                    b.append(sangria).append("- ").append(h.tipo());
                    if (!nombre.isBlank()) {
                        b.append(" «").append(nombre).append("»");
                    }
                    String id = h.automationId();
                    if (!id.isBlank() && !id.equals(nombre)) {
                        b.append(" (#").append(id).append(")");
                    }
                    String eti = h.etiqueta();
                    if (!eti.isBlank() && !eti.equals(nombre)) {
                        b.append(" [etiqueta: ").append(eti).append("]");
                    }
                    if (valor != null) {
                        b.append(" = «").append(valor.length() > 120 ? valor.substring(0, 120) + "…" : valor).append("»");
                    }
                    if (!h.activo()) {
                        b.append(" [desactivado]");
                    }
                    b.append('\n');
                }
                if (quedan > 1) {
                    pintar(h, b, interesa ? sangria + "   " : sangria, quedan - 1, cuenta, tope);
                }
            } finally {
                h.close();
            }
        }
    }

    /** Todas las rutas de menú de una ventana: ["Bases de Datos", "Clientes"], etc. Solo mira: no abre nada. */
    public List<List<String>> rutasDeMenu(String tituloVentana, int hondo) {
        ventanas.despertar(tituloVentana);
        Pointer hwnd = ventanas.handleDe(tituloVentana);
        List<List<String>> rutas = new ArrayList<>();
        if (hwnd == null) {
            return rutas;
        }
        Uia.Elemento raiz = Uia.deVentana(hwnd);
        if (raiz == null) {
            return rutas;
        }
        try {
            recogerMenus(raiz, new ArrayList<>(), rutas, Math.max(2, hondo));
        } finally {
            raiz.close();
        }
        return rutas;
    }

    private void recogerMenus(Uia.Elemento e, List<String> camino, List<List<String>> fuera, int quedan) {
        for (Uia.Elemento h : e.hijos()) {
            try {
                if (fuera.size() > 600) {
                    return;
                }
                boolean esMenu = h.tipo().equals("opcion-de-menu");
                String nombre = h.nombre().strip();
                List<String> aqui = camino;
                if (esMenu && !nombre.isBlank()) {
                    aqui = new ArrayList<>(camino);
                    aqui.add(nombre);
                    if (!fuera.contains(aqui)) {
                        fuera.add(aqui);
                    }
                }
                if (quedan > 1) {
                    recogerMenus(h, aqui, fuera, quedan - 1);
                }
            } finally {
                h.close();
            }
        }
    }

    /** Pulsa una opción de menú siguiendo su ruta exacta, no por su nombre suelto (en MN hay nombres repetidos). */
    public String pulsarRuta(String tituloVentana, List<String> ruta) {
        ventanas.despertar(tituloVentana);
        Pointer hwnd = ventanas.handleDe(tituloVentana);
        if (hwnd == null) {
            return "No hay ninguna ventana cuyo título contenga «" + tituloVentana + "».";
        }
        Uia.Elemento raiz = Uia.deVentana(hwnd);
        if (raiz == null) {
            return sinUia();
        }
        try {
            Uia.Elemento actual = null;
            for (int i = 0; i < ruta.size(); i++) {
                Uia.Elemento donde = actual == null ? raiz : actual;
                Uia.Elemento hijo = hijoLlamado(donde, ruta.get(i), 6);
                if (actual != null) {
                    actual.close();
                }
                if (hijo == null) {
                    return "No se encuentra «" + ruta.get(i) + "» dentro de " + String.join(" > ", ruta.subList(0, i))
                            + " (ruta: " + String.join(" > ", ruta) + ").";
                }
                actual = hijo;
                if (i < ruta.size() - 1) {
                    actual.desplegar();
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            if (actual == null) {
                return "Ruta vacía.";
            }
            try (Uia.Elemento fin = actual) {
                if (!fin.activo()) {
                    return "La opción «" + String.join(" > ", ruta) + "» está desactivada.";
                }
                return fin.pulsar() ? "Pulsado " + String.join(" > ", ruta)
                        : fin.desplegar() ? "Desplegado " + String.join(" > ", ruta)
                        : "No se ha dejado pulsar «" + String.join(" > ", ruta) + "».";
            }
        } finally {
            raiz.close();
        }
    }

    private Uia.Elemento hijoLlamado(Uia.Elemento e, String nombre, int quedan) {
        String buscado = llano(nombre);
        for (Uia.Elemento h : e.hijos()) {
            if (llano(h.nombre()).equals(buscado)) {
                return h;
            }
            if (quedan > 1) {
                Uia.Elemento dentro = hijoLlamado(h, nombre, quedan - 1);
                h.close();
                if (dentro != null) {
                    return dentro;
                }
            } else {
                h.close();
            }
        }
        return null;
    }

    // ---------- buscar ----------

    /** Quita acentos, símbolos de atajo y los dos puntos del final: «&Nombre:» y «nombre» se parecen. */
    public static String llano(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace("&", "").replace("_", "").replaceAll("[:：]\\s*$", "").trim().toLowerCase(Locale.ROOT);
    }

    public Encontrado buscar(String tituloVentana, String apodo) {
        ventanas.despertar(tituloVentana);
        Pointer hwnd = ventanas.handleDe(tituloVentana);
        if (hwnd == null) {
            return new Encontrado(null, "No hay ninguna ventana cuyo título contenga «" + tituloVentana + "».");
        }
        Uia.Elemento raiz = Uia.deVentana(hwnd);
        if (raiz == null) {
            return new Encontrado(null, sinUia());
        }
        try {
            return buscarEn(raiz, apodo);
        } finally {
            raiz.close();
        }
    }

    private Encontrado buscarEn(Uia.Elemento raiz, String apodo) {
        String resto = apodo.trim();
        String anclaje = null;
        int junto = llano(resto).indexOf(" junto a ");
        if (junto > 0) {
            anclaje = resto.substring(junto + " junto a ".length()).trim();
            resto = resto.substring(0, junto).trim();
        }
        // «campo[2]» = el segundo cuadro de arriba abajo. Para los diálogos cuyos cuadros no tienen NI nombre NI
        // etiqueta: lo único estable de verdad es dónde están. Los números que da Windows en esos casos son el
        // identificador de la ventana y cambian cada vez que se abre, así que no sirven.
        java.util.regex.Matcher porSitio = java.util.regex.Pattern.compile("^([a-zA-Zñáéíóú-]+)\\s*\\[(-?\\d+)\\]$").matcher(resto);
        if (porSitio.matches()) {
            return porOrden(todos(raiz), llano(porSitio.group(1)), Integer.parseInt(porSitio.group(2)), apodo);
        }
        String tipo = null;
        String nombre = resto;
        if (resto.startsWith("#")) {
            tipo = null;
            nombre = resto.substring(1);
        } else {
            int dp = resto.indexOf(':');
            if (dp > 0) {
                tipo = llano(resto.substring(0, dp));
                nombre = resto.substring(dp + 1).trim();
            }
        }
        boolean porId = resto.startsWith("#");

        List<Candidato> todos = todos(raiz);
        try {
            if (anclaje != null) {
                return porAnclaje(todos, tipo, anclaje, apodo);
            }
            return porNombre(todos, tipo, nombre, porId, apodo);
        } finally {
            todos.forEach(c -> {
                if (!c.e().equals(guardado)) {
                    c.e().close();
                }
            });
            guardado = null;
        }
    }

    private List<Candidato> todos(Uia.Elemento raiz) {
        List<Candidato> r = new ArrayList<>();
        recoger(raiz, HONDO, r);
        return r;
    }

    /**
     * El n-ésimo control de un tipo, contando como se lee: de arriba abajo y, a igual altura, de izquierda a derecha.
     * Es el último recurso, pero para un diálogo de los de siempre es perfectamente estable: el cuadro de la
     * contraseña siempre está debajo del del usuario.
     */
    private Encontrado porOrden(List<Candidato> todos, String tipo, int cual, String apodo) {
        List<Candidato> deEseTipo = new ArrayList<>();
        for (Candidato c : todos) {
            if (c.tipo().equals(tipo) && c.recuadro() != null && c.recuadro()[2] > 0 && c.recuadro()[3] > 0) {
                deEseTipo.add(c);
            }
        }
        deEseTipo.sort(Comparator.<Candidato>comparingInt(c -> c.recuadro()[1]).thenComparingInt(c -> c.recuadro()[0]));
        // Los negativos cuentan desde el final: «fila[-1]» es la última. Para listas que crecen (los ejercicios
        // contables, por ejemplo), «el último» es estable y «el número 8» no lo es.
        if (cual < 0) {
            cual = deEseTipo.size() + cual + 1;
        }
        if (cual < 1 || cual > deEseTipo.size()) {
            return new Encontrado(null, "se pide el «" + tipo + "» número " + cual + " de la pantalla, y solo se ven "
                    + deEseTipo.size() + ". ¿Seguro que estamos en esa pantalla?");
        }
        Candidato elegido = deEseTipo.get(cual - 1);
        guardado = elegido.e();
        return new Encontrado(guardado, elegido.comoSeLlama() + " (el " + cual + ".º de arriba abajo)");
    }

    /** El que se devuelve no se cierra aquí: lo cierra quien lo use. */
    private Uia.Elemento guardado;

    private void recoger(Uia.Elemento e, int quedan, List<Candidato> fuera) {
        for (Uia.Elemento h : e.hijos()) {
            if (fuera.size() > 1200) {
                h.close();
                return;
            }
            fuera.add(new Candidato(h, 0, h.tipo(), h.nombre(), h.automationId(), h.recuadro(), h.etiqueta()));
            if (quedan > 1) {
                recoger(h, quedan - 1, fuera);
            }
        }
    }

    private Encontrado porNombre(List<Candidato> todos, String tipo, String nombre, boolean porId, String apodo) {
        String buscado = llano(nombre);
        List<Candidato> puntuados = new ArrayList<>();
        for (Candidato c : todos) {
            if (tipo != null && !c.tipo().equals(tipo)) {
                continue;
            }
            int p = 0;
            String n = llano(c.nombre());
            String id = llano(c.id());
            String eti = llano(c.etiqueta());
            if (porId) {
                p = id.equals(buscado) ? 100 : id.contains(buscado) && !buscado.isEmpty() ? 60 : 0;
            } else if (buscado.isEmpty()) {
                p = tipo != null ? 60 : 0; // «boton:» = cualquier botón
            } else if (id.equals(buscado)) {
                p = 100;
            } else if (n.equals(buscado)) {
                p = 95;
            } else if (eti.equals(buscado)) {
                p = 90;
            } else if (n.startsWith(buscado)) {
                p = 72;
            } else if (n.contains(buscado)) {
                p = 58;
            } else if (id.contains(buscado) || eti.contains(buscado)) {
                p = 50;
            }
            if (p == 0) {
                continue;
            }
            if (c.recuadro() != null && c.recuadro()[2] > 0) {
                p += 4; // se ve en pantalla
            }
            puntuados.add(new Candidato(c.e(), p, c.tipo(), c.nombre(), c.id(), c.recuadro(), c.etiqueta()));
        }
        return elegir(puntuados, apodo);
    }

    /** El control que está pegado a una etiqueta: a su derecha en la misma línea, o justo debajo. Como en los formularios. */
    private Encontrado porAnclaje(List<Candidato> todos, String tipo, String anclaje, String apodo) {
        String buscado = llano(anclaje);
        List<String> tiposBuenos = tipo != null ? List.of(tipo) : List.of("campo", "desplegable", "casilla", "lista");

        // Antes de buscar la etiqueta: si hay un control DEL TIPO QUE SE PIDE que ya se llama así, ese es. Los
        // programas modernos le ponen el nombre al propio cuadro, no solo a la etiqueta de al lado.
        for (Candidato c : todos) {
            if (tiposBuenos.contains(c.tipo()) && llano(c.nombre()).equals(buscado)
                    && c.recuadro() != null && c.recuadro()[2] > 0) {
                guardado = c.e();
                return new Encontrado(guardado, c.comoSeLlama() + " (se llama así él mismo)");
            }
        }

        // La etiqueta puede ser de cualquier tipo MENOS un cuadro de los que buscamos (si no, se tomaría por etiqueta
        // el propio campo). Que sea un panel sí vale: en los programas de toda la vida las etiquetas se ven así.
        Candidato etiqueta = null;
        int mejor = 0;
        for (Candidato c : todos) {
            if (tiposBuenos.contains(c.tipo())) {
                continue;
            }
            String n = llano(c.nombre());
            int p = n.equals(buscado) ? 100 : n.startsWith(buscado) ? 70 : n.contains(buscado) ? 50 : 0;
            if (p > mejor && c.recuadro() != null && c.recuadro()[2] > 0) {
                mejor = p;
                etiqueta = c;
            }
        }
        if (etiqueta == null) {
            return new Encontrado(null, "No se ve ninguna etiqueta «" + anclaje + "» en esa ventana. Mira las que hay con ver_controles.");
        }
        int[] r = etiqueta.recuadro();

        // Si lo que hemos encontrado ENVUELVE a un cuadro del tipo que buscamos, ese cuadro de dentro es el bueno.
        // Es lo que hace MN: un panel llamado «Correo electrónico» con el cuadro del correo dentro. Antes se miraba
        // qué había DEBAJO del panel, y debajo estaba la contraseña: así se escribió un correo en esa casilla.
        Candidato dentro = null;
        for (Candidato c : todos) {
            int[] q = c.recuadro();
            if (!tiposBuenos.contains(c.tipo()) || q == null || q[2] <= 0
                    || q[0] < r[0] || q[1] < r[1] || q[0] + q[2] > r[0] + r[2] + 2 || q[1] + q[3] > r[1] + r[3] + 2) {
                continue;
            }
            if (dentro == null || q[2] * (long) q[3] > dentro.recuadro()[2] * (long) dentro.recuadro()[3]) {
                dentro = c;
            }
        }
        if (dentro != null) {
            guardado = dentro.e();
            return new Encontrado(guardado, dentro.comoSeLlama() + " (dentro de «" + etiqueta.nombre() + "»)");
        }

        int centroY = r[1] + r[3] / 2;
        List<Candidato> cerca = new ArrayList<>();
        for (Candidato c : todos) {
            if (c.e().equals(etiqueta.e()) || c.recuadro() == null || c.recuadro()[2] <= 0) {
                continue;
            }
            if (!tiposBuenos.contains(c.tipo())) {
                continue;
            }
            int[] q = c.recuadro();
            int qY = q[1] + q[3] / 2;
            int distancia;
            if (q[0] >= r[0] + r[2] - 4 && Math.abs(qY - centroY) <= Math.max(14, r[3])) {
                distancia = q[0] - (r[0] + r[2]); // a la derecha, en la misma línea
            } else if (q[1] >= r[1] + r[3] - 4 && Math.abs(q[0] - r[0]) <= Math.max(30, r[2])) {
                distancia = 1000 + q[1] - (r[1] + r[3]); // debajo, en la misma columna
            } else {
                continue;
            }
            cerca.add(new Candidato(c.e(), Math.max(50, 100 - distancia / 8), c.tipo(), c.nombre(), c.id(), q, c.etiqueta()));
        }
        if (cerca.isEmpty()) {
            return new Encontrado(null, "Hay una etiqueta «" + etiqueta.nombre() + "» pero no se ve ningún control a su lado ni debajo.");
        }
        cerca.sort(Comparator.comparingInt(Candidato::puntos).reversed());
        guardado = cerca.get(0).e();
        return new Encontrado(guardado, cerca.get(0).comoSeLlama() + " (el que está junto a «" + etiqueta.nombre() + "»)");
    }

    private Encontrado elegir(List<Candidato> puntuados, String apodo) {
        puntuados.removeIf(c -> c.puntos() < MINIMO);
        if (puntuados.isEmpty()) {
            return new Encontrado(null, "No hay ningún control que encaje con «" + apodo + "» en esa ventana. Mira los que hay con ver_controles.");
        }
        puntuados.sort(Comparator.comparingInt(Candidato::puntos).reversed());
        Candidato uno = puntuados.get(0);
        List<Candidato> empatados = new ArrayList<>();
        for (Candidato c : puntuados) {
            if (uno.puntos() - c.puntos() < EMPATE) {
                empatados.add(c);
            }
        }
        if (empatados.size() > 1) {
            List<String> como = new ArrayList<>();
            empatados.stream().limit(6).forEach(c -> como.add(c.comoSeLlama()));
            return new Encontrado(null, "«" + apodo + "» encaja igual de bien con " + empatados.size() + " controles (" + String.join("; ", como)
                    + "). Afina el apodo (su tipo delante, «#identificador» o «campo junto a <etiqueta>»): no se toca ninguno.");
        }
        guardado = uno.e();
        return new Encontrado(guardado, uno.comoSeLlama());
    }

    // ---------- actuar ----------

    /** Escribe el valor exacto en un control y lo relee para comprobarlo. */
    public String escribir(String ventana, String apodo, String texto) {
        Encontrado f = buscar(ventana, apodo);
        if (f.elemento() == null) {
            return f.descripcion();
        }
        try (Uia.Elemento e = f.elemento()) {
            e.enfocar();
            if (!e.escribir(texto)) {
                int[] r = e.recuadro();
                return "El control " + f.descripcion() + " no deja escribir directamente: haz clic en él"
                        + (r == null ? "" : " (está en x=" + (r[0] + r[2] / 2) + ", y=" + (r[1] + r[3] / 2) + " de la pantalla real)")
                        + ", bórralo con ctrl+a y Delete, y escribe. Luego compruébalo con leer_control.";
            }
            String ahora = e.valor();
            if (ahora == null) {
                return "Escrito en " + f.descripcion() + ", pero no se puede releer para comprobarlo: míralo en la captura.";
            }
            return ahora.equals(texto) ? "IGUAL: " + f.descripcion() + " vale ahora exactamente «" + ahora + "»."
                    : "DISTINTO: en " + f.descripcion() + " se quería «" + texto + "» y ha quedado «" + ahora + "». Corrígelo.";
        }
    }

    /** ¿Se encuentra este control ahora mismo? (no lo toca). */
    public boolean existe(String ventana, String apodo) {
        Encontrado f = buscar(ventana, apodo);
        if (f.elemento() == null) {
            return false;
        }
        f.elemento().close();
        return true;
    }

    /** El valor de un control, tal cual, para compararlo; null si no lo encuentra o no tiene valor que leer. */
    public String valorDe(String ventana, String apodo) {
        Encontrado f = buscar(ventana, apodo);
        if (f.elemento() == null) {
            return null;
        }
        try (Uia.Elemento e = f.elemento()) {
            return e.valor();
        }
    }

    public String leer(String ventana, String apodo) {
        Encontrado f = buscar(ventana, apodo);
        if (f.elemento() == null) {
            return f.descripcion();
        }
        try (Uia.Elemento e = f.elemento()) {
            String v = e.valor();
            return f.descripcion() + (v == null ? " no tiene un valor que leer." : " vale «" + v + "»") + (e.activo() ? "" : " [desactivado]") + ".";
        }
    }

    public String pulsar(String ventana, String apodo) {
        Encontrado f = buscar(ventana, apodo);
        if (f.elemento() == null) {
            return f.descripcion();
        }
        Uia.Elemento e = f.elemento();
        try {
            if (!e.activo()) {
                return "El control " + f.descripcion() + " está desactivado: no se puede pulsar ahora. Mira qué falta antes.";
            }
            // En un hilo aparte: un botón que abre una ventana modal no devuelve el control hasta que se cierre.
            Pulsacion como = pulsarSinColgarse(e, 4);
            if (como == Pulsacion.SIN_VOLVER) {
                return "Pulsado " + f.descripcion() + " (el programa no ha devuelto el control: suele pasar cuando "
                        + "el botón abre otra ventana encima). Comprueba qué hay en pantalla.";
            }
            if (como == Pulsacion.HECHA) {
                return "Pulsado " + f.descripcion() + ".";
            }
            if (e.desplegar()) {
                return "Desplegado " + f.descripcion() + ".";
            }
            int[] r = e.recuadro();
            return r == null ? "El control " + f.descripcion() + " no se deja pulsar por dentro y no se sabe dónde está: usa el ratón mirando la captura."
                    : "El control " + f.descripcion() + " no se deja pulsar por dentro. Está en la pantalla real en x=" + (r[0] + r[2] / 2)
                      + ", y=" + (r[1] + r[3] / 2) + ": haz clic ahí con el ratón.";
        } finally {
            e.close();
        }
    }

    /**
     * Pulsa y NO se da por satisfecho hasta que pasa lo que tiene que pasar.
     *
     * «Pulsado» no puede querer decir «lo he intentado»: tiene que querer decir «el programa ha reaccionado». Hay
     * botones que por dentro dicen que sí y no hacen nada — el de entrar de MN es un «panel» pintado como botón, y
     * Windows contestaba que lo había pulsado mientras la pantalla seguía igual. Así que se prueba por dentro,
     * después con el ratón de verdad, y por último con Intro; y entre cada intento se comprueba.
     */
    public String pulsarInsistiendo(String ventana, String apodo, java.util.function.BooleanSupplier logrado,
            double segundosPorIntento) {
        Encontrado f = buscar(ventana, apodo);
        if (f.elemento() == null) {
            return f.descripcion();
        }
        Uia.Elemento e = f.elemento();
        if (!e.activo()) {
            e.close();
            return "El control " + f.descripcion() + " está desactivado: no se puede pulsar ahora.";
        }
        int[] r = e.recuadro();
        Pulsacion como = pulsarSinColgarse(e, 4);
        if (como != Pulsacion.SIN_VOLVER) {
            e.close();
        }
        if (esperarA(logrado, segundosPorIntento)) {
            return "Pulsado " + f.descripcion() + (como != Pulsacion.SIN_VOLVER ? "."
                    : " (el programa no devolvió el control: suele pasar cuando el botón abre otra ventana encima).");
        }

        if (r != null && r[2] > 0 && r[3] > 0) {
            new ManosRobot(false).clicEnPantalla(r[0] + r[2] / 2, r[1] + r[3] / 2);
            if (esperarA(logrado, segundosPorIntento)) {
                return "Pulsado " + f.descripcion() + " (por dentro no reaccionaba; ha hecho falta el ratón).";
            }
        }

        Encontrado otra = buscar(ventana, apodo);
        if (otra.elemento() != null) {
            try (Uia.Elemento otroE = otra.elemento()) {
                otroE.enfocar();
            }
            new ManosRobot(false).tecla("Return", 1);
            if (esperarA(logrado, segundosPorIntento)) {
                return "Pulsado " + f.descripcion() + " (ha hecho falta Intro).";
            }
        }
        return "Se ha pulsado " + f.descripcion() + " de tres formas distintas (por dentro, con el ratón y con Intro) "
                + "y el programa no ha reaccionado. Hay que mirar qué pasa en esa pantalla.";
    }

    /**
     * Pulsa sin arriesgarse a quedarse colgado para siempre.
     *
     * Cuando se pulsa por dentro un botón que abre una ventana modal (elegir empresa, elegir ejercicio, un aviso),
     * Windows NO devuelve el control hasta que esa ventana se cierre. Y como la va a cerrar el propio asistente
     * después, la llamada se quedaría esperando a algo que depende de ella misma: colgado hasta el fin de los
     * tiempos. Así que se pulsa en un hilo aparte y no se espera a que vuelva: lo que importa no es que la llamada
     * termine, es que el programa reaccione, y eso se comprueba mirando.
     *
     * @return true si la llamada volvió sola (entonces el elemento se puede soltar sin riesgo)
     */
    private enum Pulsacion {
        /** Volvió y funcionó. */
        HECHA,
        /** Volvió y ese control no se deja pulsar así. */
        NO_SE_DEJA,
        /** No ha vuelto: se ha quedado dentro de Windows, casi seguro porque ha abierto una ventana modal. */
        SIN_VOLVER
    }

    private static Pulsacion pulsarSinColgarse(Uia.Elemento e, double segundos) {
        final boolean[] fue = {false, false}; // {volvió, funcionó}
        Thread t = new Thread(() -> {
            try {
                fue[1] = e.pulsar();
            } catch (RuntimeException ignorado) {
                // Si la llamada falla, da igual: lo que cuenta es si el programa ha reaccionado.
            } finally {
                fue[0] = true;
            }
        }, "pulsar");
        t.setDaemon(true); // si se queda dentro de Windows, que no impida cerrar el programa
        t.start();
        try {
            t.join((long) (segundos * 1000));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return !fue[0] ? Pulsacion.SIN_VOLVER : fue[1] ? Pulsacion.HECHA : Pulsacion.NO_SE_DEJA;
    }

    private static boolean esperarA(java.util.function.BooleanSupplier logrado, double segundos) {
        long fin = System.currentTimeMillis() + (long) (Math.max(1, segundos) * 1000);
        do {
            if (logrado.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.currentTimeMillis() < fin);
        return false;
    }

    // ---------- esperar (lo que hace fiable a una automatización) ----------

    public enum Que { APAREZCA, DESAPAREZCA, SE_ACTIVE, VALGA }

    /**
     * Espera a que pase algo, mirando de verdad, en vez de dormir un rato a ciegas. Es lo que distingue una
     * automatización fiable de una que falla el día que el programa va lento.
     */
    public String esperar(String ventana, String apodo, Que que, String valor, double segundos) {
        long fin = System.currentTimeMillis() + (long) (Math.max(0.5, segundos) * 1000);
        String ultimo = "";
        while (true) {
            boolean ventanaHay = ventanas.handleDe(ventana) != null;
            if (apodo == null || apodo.isBlank()) {
                if (que == Que.APAREZCA && ventanaHay) {
                    return "Ya está: la ventana «" + ventana + "» está abierta.";
                }
                if (que == Que.DESAPAREZCA && !ventanaHay) {
                    return "Ya está: la ventana «" + ventana + "» ha desaparecido.";
                }
                ultimo = ventanaHay ? "la ventana está abierta" : "la ventana no está";
            } else if (!ventanaHay) {
                ultimo = "todavía no hay ninguna ventana «" + ventana + "»";
            } else {
                Encontrado f = buscar(ventana, apodo);
                if (f.elemento() == null) {
                    if (que == Que.DESAPAREZCA && f.descripcion().startsWith("No hay ningún control")) {
                        return "Ya está: " + apodo + " ha desaparecido.";
                    }
                    ultimo = f.descripcion();
                } else {
                    try (Uia.Elemento e = f.elemento()) {
                        switch (que) {
                            case APAREZCA:
                                return "Ya está: " + f.descripcion() + (e.activo() ? "" : " (pero está desactivado)") + ".";
                            case SE_ACTIVE:
                                if (e.activo()) {
                                    return "Ya está: " + f.descripcion() + " está activo.";
                                }
                                ultimo = f.descripcion() + " sigue desactivado";
                                break;
                            case VALGA:
                                String v = e.valor();
                                if (v != null && v.equals(valor)) {
                                    return "Ya está: " + f.descripcion() + " vale «" + v + "».";
                                }
                                ultimo = f.descripcion() + " vale ahora «" + v + "»";
                                break;
                            case DESAPAREZCA:
                                ultimo = f.descripcion() + " sigue ahí";
                                break;
                            default:
                                break;
                        }
                    }
                }
            }
            if (System.currentTimeMillis() >= fin) {
                return "Se han pasado los " + (long) segundos + " s esperando y no ha ocurrido: " + ultimo + ".";
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Interrumpido.";
            }
        }
    }
}
