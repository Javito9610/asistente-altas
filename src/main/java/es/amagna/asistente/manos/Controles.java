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
    /** La llamada UIA del ejercicio se ha abandonado: el hilo que aún ande no debe pulsar después. */
    private volatile boolean abandonarUiaEjercicio;

    public Controles(VentanasNativas ventanas) {
        this.ventanas = ventanas;
    }

    public record Encontrado(Uia.Elemento elemento, String descripcion) {
    }

    /** Un candidato con su puntuación, ya copiados sus datos (para poder ordenar sin ir y venir al programa). */
    private record Candidato(Uia.Elemento e, int puntos, String tipo, String nombre, String id, int[] recuadro, String etiqueta, String valor) {
        String comoSeLlama() {
            String n = nombre.isBlank() ? (id.isBlank() ? "(sin nombre)" : id) : nombre;
            if (valor != null && !valor.isBlank() && !valor.equals(nombre)) {
                return tipo + " «" + n + "» = «" + valor + "»";
            }
            return tipo + " «" + n + "»";
        }
    }

    // ---------- ver ----------

    /**
     * El dibujo de una ventana, pedido a la propia ventana: vale aunque esté tapada y aunque no haya pantalla
     * (servidor con la sesión de escritorio remoto desconectada). Ver {@link Captura}.
     */
    public Captura.Resultado dibujar(String tituloVentana) {
        ventanas.despertar(tituloVentana);
        Pointer hwnd = ventanas.handleDe(tituloVentana);
        if (hwnd == null) {
            return Captura.noHay("No hay ninguna ventana cuyo título contenga «" + tituloVentana + "».");
        }
        return Captura.deVentana(hwnd);
    }

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
            // La conexión con UIA se queda vieja tras un diálogo modal: se reinicia y se pregunta otra vez antes de rendirse.
            Uia.reiniciar();
            raiz = Uia.deVentana(hwnd);
        }
        if (raiz == null) {
            // Un aviso de Windows (MessageBox) guarda su texto en controles normales: se lee sin UIA.
            StringBuilder nativo = new StringBuilder();
            for (Pointer h : Win32.hijos(hwnd)) {
                String t = Win32.titulo(h);
                if (t != null && !t.isBlank()) {
                    nativo.append("- ").append(Win32.clase(h).toLowerCase(java.util.Locale.ROOT).contains("button") ? "boton" : "texto")
                            .append(" «").append(t.strip()).append("»\n");
                }
            }
            if (nativo.length() > 0) {
                return ("Controles de «" + Win32.titulo(hwnd) + "» (leídos sin UIA):\n" + nativo).strip();
            }
            return sinUia();
        }
        StringBuilder b = new StringBuilder("Controles de «" + Win32.titulo(hwnd) + "»:\n");
        int[] cuenta = {0};
        try {
            pintar(raiz, b, "", Math.max(1, profundidad), cuenta, tope);
        } finally {
            raiz.close();
        }
        if (cuenta[0] == 0) {
            Uia.reiniciar();
            raiz = Uia.deVentana(hwnd);
            if (raiz != null) {
                b = new StringBuilder("Controles de «" + Win32.titulo(hwnd) + "»:\n");
                cuenta[0] = 0;
                try {
                    pintar(raiz, b, "", Math.max(1, profundidad), cuenta, tope);
                } finally {
                    raiz.close();
                }
            }
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

    /**
     * Una fila de lista o de rejilla. En MN las de empresa y ejercicio no salen siempre como DataItem («fila»):
     * a veces son ListItem («opcion») y a veces un Custom («otro») con el año o «Fila 0» en el nombre.
     * Si solo se buscan DataItem, el guion no ve ninguna, no elige año y el diálogo no avanza.
     */
    public static boolean cuentaComoFila(String tipo, String nombre) {
        if (tipo == null) {
            return false;
        }
        // La frase de ayuda del diálogo («La empresa de prueba incluye ejemplos…») NO es una fila.
        // Si se puntúa por «contiene Empresa de prueba» empata con la celda y no se toca ninguna.
        if (esFraseDeAyuda(nombre)) {
            return false;
        }
        if (tipo.equals("fila") || tipo.equals("opcion")) {
            return true;
        }
        if (!tipo.equals("otro") && !tipo.equals("panel")) {
            return false;
        }
        String n = llano(nombre);
        if (n.isEmpty()) {
            return false;
        }
        // La cabecera de un DataGridView de MN se llama «Fila superior»: no es una empresa ni un ejercicio.
        if (n.contains("superior") || n.contains("header") || n.contains("cabecera")) {
            return false;
        }
        if (n.contains("fila") || n.contains("row") || n.startsWith("item ")) {
            return true;
        }
        String digits = n.replace(".", "").replace(" ", "").replace("'", "");
        return digits.matches(".*(19|20)\\d{2}.*");
    }

    /** Un texto largo de ayuda o de aviso, no el nombre de una fila. */
    public static boolean esFraseDeAyuda(String nombre) {
        if (nombre == null) {
            return false;
        }
        String n = nombre.trim();
        if (n.length() > 40) {
            return true;
        }
        int espacios = 0;
        for (int i = 0; i < n.length(); i++) {
            if (n.charAt(i) == ' ') {
                espacios++;
            }
        }
        return espacios >= 5;
    }

    /**
     * En MN la empresa no está en el nombre del control («Fila 0») sino en su valor («1;Empresa de prueba»).
     * Se compara el valor entero o cada campo separado por «;». No vale un trozo suelto: «Empresa» no debe
     * elegir «Empresa 4».
     */
    public static boolean elValorEsLaFila(String valor, String buscado) {
        String b = llano(buscado);
        if (b.isEmpty()) {
            return false;
        }
        for (String campo : camposDe(valor)) {
            if (campo.equals(b)) {
                return true;
            }
        }
        return false;
    }

    /** El texto entero y cada campo (separados por ; | tab o coma), ya en llano. */
    public static List<String> camposDe(String texto) {
        String v = llano(texto);
        if (v.isEmpty()) {
            return List.of();
        }
        List<String> r = new ArrayList<>();
        r.add(v);
        for (String campo : v.split("[;|\\t,]")) {
            String c = llano(campo);
            if (!c.isEmpty() && !r.contains(c)) {
                r.add(c);
            }
        }
        return r;
    }

    /**
     * Puntos de una fila contra un nombre de empresa (o de año). Solo igualdad de campo, nunca un trozo
     * («Empresa» no elige la 3 ni la 4). La fila entera («Fila 0») gana a la celda («Nombre Fila 0»)
     * por más del margen de empate: si no, el asistente no toca ninguna aunque la tenga delante.
     */
    public static int puntosDeFila(String tipo, String nombre, String valor, String buscado) {
        if (!cuentaComoFila(tipo, nombre)) {
            return 0;
        }
        String b = llano(buscado);
        if (b.isEmpty()) {
            return 0;
        }
        boolean encaja = elValorEsLaFila(valor, buscado) || elValorEsLaFila(nombre, buscado);
        if (!encaja) {
            return 0;
        }
        if (esCeldaDeFila(nombre)) {
            return 70;
        }
        if (esLaFilaEntera(nombre)) {
            return 112;
        }
        return 93;
    }

    /** Empresas reales de MN: NUNCA se eligen, se confirman ni se piden a la IA. */
    public static final List<String> EMPRESAS_PROHIBIDAS = List.of("Mi Empresa", "Empresa 3", "Empresa 4");

    public static final String EMPRESA_DE_PRUEBA = "Empresa de prueba";

    public static boolean empresaProhibida(String texto) {
        String t = llano(texto);
        if (t.isEmpty()) {
            return false;
        }
        for (String p : EMPRESAS_PROHIBIDAS) {
            if (elValorEsLaFila(texto, p) || t.contains(llano(p))) {
                return true;
            }
        }
        return false;
    }

    public static final String DIALOGO_EMPRESA = "Selección de Empresa";
    public static final String DIALOGO_EJERCICIO = "Selección de Ejercicio Contable";

    public static boolean esDialogoDeEmpresa(String ventana) {
        String v = llano(ventana);
        return v.contains("seleccion de empresa");
    }

    /** El diálogo real de MN se llama «Selección de Ejercicio Contable», no el trozo «Ejercicio Contable». */
    public static boolean esDialogoDeEjercicio(String ventana) {
        String v = llano(ventana);
        return v.contains("seleccion de ejercicio contable")
                || v.equals("ejercicio contable")
                || (v.contains("ejercicio") && v.contains("contable") && !v.contains("cierre"));
    }

    /**
     * Tras un Invoke/modal el árbol UIA sale vacío: no hay filas ni «Seleccionar».
     * Mientras el volcado esté así, avanzar no debe rendirse con Probado vacío.
     */
    public static boolean arbolSirveParaAvanzar(String arbol) {
        if (arbol == null || arbol.isBlank()) {
            return false;
        }
        String n = llano(arbol);
        if (n.startsWith("no hay ninguna ventana") || n.startsWith("no se pueden leer")) {
            return false;
        }
        if (n.contains("seleccionar") || n.contains("aceptar") || n.contains("confirmar")) {
            return true;
        }
        return n.contains("fila ") || n.contains("row ") || n.contains("2026")
                || n.contains("opcion") || n.contains("opción");
    }

    /**
     * El diálogo de ejercicio está listo para pulsar cuando se ve el botón Seleccionar.
     * El año 2026 ya viene marcado: no hace falta ver la fila para pulsar.
     * Un árbol vacío, «no hay ventana» o solo filas no basta.
     */
    public static boolean arbolDeEjercicioListo(String arbol) {
        if (arbol == null || arbol.isBlank()) {
            return false;
        }
        String n = llano(arbol);
        if (n.startsWith("no hay ninguna ventana") || n.startsWith("no se pueden leer")) {
            return false;
        }
        return n.contains("seleccionar");
    }

    /**
     * Una línea del parte cuenta como que el asistente pasó «Selección de Ejercicio Contable»
     * solo si empieza por «Pasada la pantalla» y consta que pulsó Seleccionar o hizo doble clic.
     * «Ya estaba pasada», «se salta» y «No se da por pasada» no cuentan: esa última frase
     * tiene las mismas palabras y el guion la daba por buena sin haber pulsado nada.
     */
    public static boolean elAsistentePasoElEjercicio(String linea) {
        if (linea == null || linea.isBlank()) {
            return false;
        }
        String n = llano(linea);
        if (n.contains("ya estaba pasada") || n.contains("se salta") || n.contains("mas adelante")
                || n.contains("no se ha visto") || n.contains("no se ha podido")
                || n.contains("no se da por") || n.contains("probado:")
                || n.contains("parado") || n.contains("sigue abierto")) {
            return false;
        }
        if (!n.startsWith("pasada la pantalla") || !n.contains("ejercicio")) {
            return false;
        }
        boolean pulso = n.contains("pulsado") && n.contains("seleccionar");
        boolean doble = n.contains("doble clic") || n.contains("doble-clic");
        boolean tecla = n.contains("tecla return") || n.contains("tecla intro");
        return pulso || doble || tecla;
    }

    /**
     * Éxito del ejercicio solo si el diálogo ya no está y consta el clic, o si ya no estaba
     * («Ya estaba pasada»). Con el handle aún vivo no vale ni eso ni un error que mencione Seleccionar.
     */
    public static boolean exitoDeEjercicio(String linea, boolean dialogoSigueAbierto) {
        if (dialogoSigueAbierto || linea == null || linea.isBlank()) {
            return false;
        }
        if (elAsistentePasoElEjercicio(linea)) {
            return true;
        }
        return llano(linea).startsWith("ya estaba pasada");
    }

    /** Alta / Baja / Modificar en «Selección de Empresa». El popup «No se puede modificar…» sale de pulsar Modificar. */
    public static boolean esBotonPeligrosoDeEmpresa(String nombre) {
        String n = llano(nombre);
        if (n.isEmpty()) {
            return false;
        }
        if (n.startsWith("#")) {
            n = n.substring(1);
        }
        int dp = n.indexOf(':');
        if (dp > 0) {
            n = n.substring(dp + 1).trim();
        }
        return n.equals("modificar") || n.equals("alta") || n.equals("baja")
                || n.equals("eliminar") || n.equals("borrar")
                || n.equals("btnmodificar") || n.equals("btnalta") || n.equals("btnbaja");
    }

    public static boolean esAvisoInformativo(String titulo) {
        String t = llano(titulo);
        return t.equals("informacion") || t.equals("aviso")
                || t.contains("no se puede modificar");
    }

    /**
     * Si este control no se puede pulsar en el diálogo de empresa (o es una empresa real), el motivo; si no, null.
     */
    public static String rechazoDeEmpresa(String ventana, String apodo) {
        return rechazoDeEmpresa(ventana, apodo, null, null, null);
    }

    public static String rechazoDeEmpresa(String ventana, String apodo, String tipo, String nombre, String valor) {
        String a = apodo == null ? "" : apodo;
        boolean dialogo = esDialogoDeEmpresa(ventana);
        if (dialogo && (esBotonPeligrosoDeEmpresa(a) || esBotonPeligrosoDeEmpresa(nombre))) {
            return "PARADO: en Selección de Empresa no se pulsa Alta, Baja ni Modificar. Solo Seleccionar.";
        }
        if (empresaProhibida(a) || empresaProhibida(nombre)
                || (cuentaComoFila(tipo, nombre) && empresaProhibida(valor))) {
            return "PARADO: no se elige una empresa real («Mi Empresa» / «Empresa 3» / «Empresa 4»). Solo «Empresa de prueba».";
        }
        return null;
    }

    /** La fila entera de un DataGridView: «Fila 0», no la celda «Nombre Fila 0». */
    public static boolean esLaFilaEntera(String nombre) {
        String n = llano(nombre);
        return n.matches("fila \\d+") || n.matches("row \\d+");
    }

    /** Una celda dentro de esa fila: si se puntúa igual que la fila, el asistente las da por empate y no toca ninguna. */
    public static boolean esCeldaDeFila(String nombre) {
        String n = llano(nombre);
        return n.matches("(nombre|numero) fila \\d+") || n.matches("(name|number) row \\d+");
    }

    /**
     * Cómo se llama el botón de confirmar, de más a menos habitual en MN. Siempre con tipo o identificador:
     * buscar «abrir» o «si» a secas acaba pulsando el desplegable de un combo o un texto que contiene esas letras.
     */
    public static List<String> apodosDeConfirmar() {
        return List.of(
                "boton:Seleccionar", "panel:Seleccionar", "#btnSeleccionar",
                "boton:Aceptar", "panel:Aceptar", "#btnAceptar",
                "boton:Confirmar", "panel:Confirmar",
                "boton:Continuar", "boton:Siguiente",
                "panel:Iniciar Sesión", "boton:OK", "#btnOk");
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
            if (c.recuadro() == null || c.recuadro()[2] <= 0 || c.recuadro()[3] <= 0) {
                continue;
            }
            boolean vale = tipo.equals("fila") ? cuentaComoFila(c.tipo(), c.nombre()) : c.tipo().equals(tipo);
            if (vale) {
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
            fuera.add(new Candidato(h, 0, h.tipo(), h.nombre(), h.automationId(), h.recuadro(), h.etiqueta(), h.valor()));
            if (quedan > 1) {
                recoger(h, quedan - 1, fuera);
            }
        }
    }

    private Encontrado porNombre(List<Candidato> todos, String tipo, String nombre, boolean porId, String apodo) {
        String buscado = llano(nombre);
        boolean buscandoFila = tipo == null || "fila".equals(tipo);
        List<Candidato> puntuados = new ArrayList<>();
        for (Candidato c : todos) {
            if (tipo != null && !c.tipo().equals(tipo) && !(tipo.equals("fila") && cuentaComoFila(c.tipo(), c.nombre()))) {
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
            } else if (!esFraseDeAyuda(c.nombre()) && n.startsWith(buscado)) {
                p = 72;
            } else if (!esFraseDeAyuda(c.nombre()) && n.contains(buscado)) {
                p = 58;
            } else if (!esFraseDeAyuda(c.nombre()) && !esFraseDeAyuda(c.etiqueta())
                    && (id.contains(buscado) || eti.contains(buscado))) {
                p = 50;
            }
            // DataGridView de MN: el control se llama «Fila 0» y el texto está en el valor.
            // Esto manda sobre el «contiene» del texto de ayuda: si no, empata con la celda y no se toca nada.
            if (buscandoFila) {
                int pf = puntosDeFila(c.tipo(), c.nombre(), c.valor(), nombre);
                if (pf > p) {
                    p = pf;
                }
            }
            if (p == 0) {
                continue;
            }
            if (c.recuadro() != null && c.recuadro()[2] > 0) {
                p += 4; // se ve en pantalla
            }
            puntuados.add(new Candidato(c.e(), p, c.tipo(), c.nombre(), c.id(), c.recuadro(), c.etiqueta(), c.valor()));
        }
        return elegir(puntuados, apodo);
    }

    /** El control que está pegado a una etiqueta: a su derecha en la misma línea, o justo debajo. Como en los formularios. */
    /** De varios controles con el mismo nombre, el más interior: el de menor área (a igual área, el primero). */
    static Candidato masInterior(List<Candidato> iguales) {
        if (iguales.isEmpty()) {
            return null;
        }
        return iguales.get(indiceMasInterior(iguales.stream().map(Candidato::recuadro).toList()));
    }

    /** El índice del recuadro de menor área ({x, y, ancho, alto}); a igual área, el primero. */
    public static int indiceMasInterior(List<int[]> recuadros) {
        int mejor = 0;
        for (int i = 1; i < recuadros.size(); i++) {
            if ((long) recuadros.get(i)[2] * recuadros.get(i)[3] < (long) recuadros.get(mejor)[2] * recuadros.get(mejor)[3]) {
                mejor = i;
            }
        }
        return mejor;
    }

    private Encontrado porAnclaje(List<Candidato> todos, String tipo, String anclaje, String apodo) {
        String buscado = llano(anclaje);
        List<String> tiposBuenos = tipo != null ? List.of(tipo) : List.of("campo", "desplegable", "casilla", "lista");

        // Antes de buscar la etiqueta: si hay un control DEL TIPO QUE SE PIDE que ya se llama así, ese es. Los
        // programas modernos le ponen el nombre al propio cuadro, no solo a la etiqueta de al lado.
        // MN repite el nombre: un campo exterior y, dentro, el cuadro de verdad (el exterior sale vacío y no deja
        // escribir). Entre los que se llaman igual se elige el más interior (el de menos área): es el que tiene el valor.
        List<Candidato> igualesDeNombre = new ArrayList<>();
        for (Candidato c : todos) {
            if (tiposBuenos.contains(c.tipo()) && llano(c.nombre()).equals(buscado)
                    && c.recuadro() != null && c.recuadro()[2] > 0) {
                igualesDeNombre.add(c);
            }
        }
        Candidato interior = masInterior(igualesDeNombre);
        if (interior != null) {
            guardado = interior.e();
            return new Encontrado(guardado, interior.comoSeLlama() + " (se llama así él mismo)");
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
            cerca.add(new Candidato(c.e(), Math.max(50, 100 - distancia / 8), c.tipo(), c.nombre(), c.id(), q, c.etiqueta(), c.valor()));
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
            Candidato filaEntera = null;
            int enteras = 0;
            for (Candidato c : empatados) {
                if (esLaFilaEntera(c.nombre())) {
                    filaEntera = c;
                    enteras++;
                }
            }
            if (enteras == 1) {
                guardado = filaEntera.e();
                return new Encontrado(guardado, filaEntera.comoSeLlama());
            }
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
        String no = rechazoDeEmpresa(ventana, apodo);
        if (no != null) {
            return no;
        }
        Encontrado f = buscar(ventana, apodo);
        if (f.elemento() == null) {
            return f.descripcion();
        }
        Uia.Elemento e = f.elemento();
        try {
            String no2 = rechazoDeEmpresa(ventana, apodo, e.tipo(), e.nombre(), e.valor());
            if (no2 != null) {
                return no2;
            }
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
        String no = rechazoDeEmpresa(ventana, apodo);
        if (no != null) {
            return no;
        }
        Encontrado f = buscar(ventana, apodo);
        if (f.elemento() == null) {
            return f.descripcion();
        }
        Uia.Elemento e = f.elemento();
        String no2 = rechazoDeEmpresa(ventana, apodo, e.tipo(), e.nombre(), e.valor());
        if (no2 != null) {
            e.close();
            return no2;
        }
        if (!e.activo()) {
            e.close();
            return "El control " + f.descripcion() + " está desactivado: no se puede pulsar ahora.";
        }
        int[] r = e.recuadro();
        String desc = f.descripcion();
        Pulsacion como = pulsarSinColgarse(e, 4);
        if (como != Pulsacion.SIN_VOLVER) {
            e.close();
        } else {
            // La llamada se ha quedado dentro de Windows (diálogo modal). Si no se recrea UI Automation,
            // el árbol de la pantalla nueva (empresa, ejercicio) sale vacío.
            Uia.reiniciar();
        }
        if (esperarA(logrado, segundosPorIntento)) {
            return "Pulsado " + desc + (como != Pulsacion.SIN_VOLVER ? "."
                    : " (el programa no devolvió el control: suele pasar cuando el botón abre otra ventana encima).");
        }

        if (r != null && r[2] > 0 && r[3] > 0) {
            if (ventanas != null) {
                ventanas.activar(ventana);
            }
            new ManosRobot(false).clicEnPantalla(r[0] + r[2] / 2, r[1] + r[3] / 2);
            if (esperarA(logrado, segundosPorIntento)) {
                return "Pulsado " + desc + " (por dentro no reaccionaba; ha hecho falta el ratón).";
            }
        }

        Encontrado otra = buscar(ventana, apodo);
        if (otra.elemento() != null) {
            try (Uia.Elemento otroE = otra.elemento()) {
                otroE.enfocar();
            }
            new ManosRobot(false).tecla("Return", 1);
            if (esperarA(logrado, segundosPorIntento)) {
                return "Pulsado " + desc + " (ha hecho falta Intro).";
            }
        }
        return "Se ha pulsado " + desc + " de tres formas distintas (por dentro, con el ratón y con Intro) "
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

    /**
     * Pasar de una pantalla cuyo único trabajo es confirmar, por las buenas o por las malas.
     *
     * Hay diálogos —elegir empresa, elegir ejercicio— cuyo botón de confirmar no siempre se llama igual. Afinar
     * el nombre a mano es frágil. Aquí se pulsa lo razonable (Seleccionar, Aceptar…) y, si el guion lo pide:
     * <ul>
     *   <li>una fila concreta (la empresa de prueba: NUNCA la última, que en MN es la empresa real);</li>
     *   <li>o la última fila (solo el ejercicio más reciente, y solo si el guion dice «ultima»).</li>
     * </ul>
     * Sin esa orden, no se elige fila: se confirma lo que ya está marcado. Solo se pulsan botones de confirmar.
     * Nunca uno que borre, elimine o cancele.
     */
    public String avanzar(String ventana, java.util.function.BooleanSupplier logrado, double segundos) {
        return avanzar(ventana, logrado, segundos, null, false);
    }

    public String avanzar(String ventana, java.util.function.BooleanSupplier logrado, double segundos, boolean ultima) {
        return avanzar(ventana, logrado, segundos, null, ultima);
    }

    /** Tope del diálogo de ejercicio: si COM no vuelve, se abandona y se sale. No minutos, no IA. */
    static final long PRESUPUESTO_EJERCICIO_MS = 25_000;

    public String avanzar(String ventana, java.util.function.BooleanSupplier logrado, double segundos,
            String fila, boolean ultima) {
        if (esDialogoDeEjercicio(ventana)) {
            return avanzarEjercicio(ventana, segundos);
        }
        StringBuilder loQueSeHaProbado = new StringBuilder();
        boolean ejercicio = esDialogoDeEjercicio(ventana);
        // El ejercicio NO se da por pasado porque la principal de MN se vea detrás, ni porque
        // UIA esté muerta (handleDe null → «desaparezca» true → «Ya estaba pasada» mentiroso).
        if (!ejercicio && logrado.getAsBoolean()) {
            return "Ya estaba pasada esa pantalla.";
        }
        // Un «Información / No se puede modificar…» (alguien pulsó Modificar) tapa la rejilla y
        // deja COM a medias. Se cierra con Aceptar y se sigue: no es éxito ni se congela.
        String apartado = apartarInformacion();
        if (!apartado.isEmpty()) {
            loQueSeHaProbado.append(" · ").append(apartado);
        }
        Uia.reiniciar();
        double porIntento = Math.max(2.5, segundos / 8);
        ManosRobot manos = new ManosRobot(false);

        if (ventanas != null) {
            ventanas.activar(ventana);
        }

        // Tras Invoke/modal COM queda muerto y el árbol sale vacío: Probado vacío, sin filas ni Seleccionar.
        double esperaArbol = ejercicio
                ? Math.min(45, Math.max(12, segundos / 3))
                : Math.min(25, Math.max(6, segundos / 3));
        boolean arbolListo = esperarArbolDeAvance(ventana, esperaArbol, loQueSeHaProbado);
        boolean hayVentana = ventanas != null && ventanas.handleDe(ventana) != null;
        if (ejercicio && !hayVentana && !arbolListo) {
            boolean loVimos = loQueSeHaProbado.indexOf("UIA:") >= 0
                    || loQueSeHaProbado.indexOf("árbol vacío") >= 0;
            if (!loVimos) {
                return "Ya estaba pasada esa pantalla.";
            }
            return "No se ha podido pulsar Seleccionar en «" + ventana
                    + "»: el diálogo se ha perdido de vista sin pulsarlo."
                    + loQueSeHaProbado + "\n" + arbol(ventana, 8, 200);
        }
        if (!ejercicio && logrado.getAsBoolean()) {
            return "Ya estaba pasada esa pantalla.";
        }

        String pedida = nombrePedidoDeFila(fila);
        if (empresaProhibida(fila) || empresaProhibida(pedida)) {
            return "PARADO: se pedía elegir una empresa real («" + fila + "»). Solo «Empresa de prueba». No se confirma nada.";
        }

        String elegida = null;
        if (fila != null && !fila.isBlank()) {
            String apodoFila = fila.trim();
            if (!apodoFila.contains(":")) {
                apodoFila = "fila:" + apodoFila;
            }
            String[] porQue = {null};
            elegida = elegirFila(ventana, apodoFila, porQue);
            if (elegida == null) {
                return "No se encuentra «" + fila + "» en «" + ventana
                        + "». No se confirma nada: podría ser la empresa equivocada."
                        + (porQue[0] == null ? "" : " " + porQue[0]) + "\n"
                        + arbol(ventana, 8, 200);
            }
            String prueba = probarFilaElegida(ventana, elegida, pedida);
            if (prueba != null) {
                return prueba + "\n" + arbol(ventana, 8, 200);
            }
            loQueSeHaProbado.append(" · ").append(elegida);
        } else if (ultima) {
            loQueSeHaProbado.append(" · ultima");
            if (esDialogoDeEmpresa(ventana)) {
                return "PARADO: en Selección de Empresa no se elige la última fila (esa es una empresa real). "
                        + "Hay que nombrar «Empresa de prueba». No se confirma nada.";
            }
            elegida = elegirUltimaFila(ventana);
            if (elegida == null && ejercicio && existe(ventana, "fila:2026")) {
                String[] porQue = {null};
                elegida = elegirFila(ventana, "fila:2026", porQue);
            }
            if (elegida != null) {
                String vUlt = textoDeFila(ventana, elegida);
                if (empresaProhibida(vUlt)) {
                    return "PARADO: la última fila es una empresa real («" + vUlt + "»). No se confirma nada.";
                }
                loQueSeHaProbado.append(" · ").append(elegida);
                if (vUlt != null && !vUlt.isBlank()) {
                    loQueSeHaProbado.append(" («").append(vUlt).append("»)");
                }
            } else {
                loQueSeHaProbado.append(" (sin filas aún)");
            }
        }
        if (elegida != null && ejercicio) {
            // 2026 suele estar ya marcado. Un clic simple deja la última fila por si no había
            // ninguna; el que cierra el diálogo es Seleccionar, no el doble clic.
            int[] r = recuadroClicDeFila(ventana, elegida);
            if (r != null && r[2] > 0 && r[3] > 0) {
                manos.clicEnPantalla(r[0] + r[2] / 2, r[1] + r[3] / 2, 1);
                loQueSeHaProbado.append(" · fila marcada");
            }
        } else if (elegida != null) {
            int[] r = recuadroClicDeFila(ventana, elegida);
            if (r != null && r[2] > 0 && r[3] > 0) {
                manos.clicEnPantalla(r[0] + r[2] / 2, r[1] + r[3] / 2, 2);
                loQueSeHaProbado.append(" · doble clic");
                if (esperarA(logrado, porIntento)) {
                    return frasePasada(ventana, ultima, "con doble clic en " + elegida);
                }
                String otra = apartarInformacion();
                if (!otra.isEmpty()) {
                    loQueSeHaProbado.append(" · ").append(otra);
                }
            }
        }

        java.util.List<String> apodos = ejercicio
                ? java.util.List.of("boton:Seleccionar", "panel:Seleccionar", "#btnSeleccionar")
                : apodosDeConfirmar();
        for (String apodo : apodos) {
            if (esBotonPeligrosoDeEmpresa(apodo)) {
                continue;
            }
            Encontrado f = buscar(ventana, apodo);
            if (f.elemento() == null) {
                continue;
            }
            f.elemento().close();
            loQueSeHaProbado.append(" · ").append(apodo);
            String r = pulsarInsistiendo(ventana, apodo, logrado, porIntento);
            if (r != null && r.startsWith("PARADO")) {
                return r;
            }
            if (ejercicio) {
                // El diálogo tiene que haberse ido DESPUÉS del clic. Si sigue ahí, no vale
                // que la principal de MN se vea detrás ni que UIA haya perdido el título un instante.
                Uia.reiniciar();
                dormir(500);
                if (ventanas != null && ventanas.handleDe(ventana) == null) {
                    return frasePasada(ventana, ultima, "con «" + apodo + "»: " + r);
                }
                loQueSeHaProbado.append(" · el diálogo sigue abierto");
            } else if (logrado.getAsBoolean()) {
                return frasePasada(ventana, ultima, "con «" + apodo + "»: " + r);
            }
            String otra = apartarInformacion();
            if (!otra.isEmpty()) {
                loQueSeHaProbado.append(" · ").append(otra);
                Uia.reiniciar();
            }
        }

        // Con una empresa concreta NO se confirma con teclas: alt+s acepta la fila que haya
        // marcada (así se acabó en «Mi Empresa»). Intro solo en el diálogo de ejercicio, y
        // solo si ese diálogo está delante: no se teclea contra la principal de MN.
        hayVentana = ventanas != null && ventanas.handleDe(ventana) != null;
        if (ejercicio && ultima && hayVentana && dialogoEnFrente(ventana)) {
            ventanas.activar(ventana);
            loQueSeHaProbado.append(" · tecla Return");
            manos.tecla("Return", 1);
            if (esperarA(() -> ventanas.handleDe(ventana) == null, porIntento)) {
                Uia.reiniciar();
                if (ventanas.handleDe(ventana) == null) {
                    return frasePasada(ventana, ultima, "con la tecla Return");
                }
            }
        }
        return "No se ha podido pasar de «" + ventana + "». Probado:" + loQueSeHaProbado
                + ". Esto es lo que hay en esa pantalla:\n" + arbol(ventana, 8, 200);
    }

    /**
     * Cierra «Selección de Ejercicio Contable» pulsando Seleccionar en ESA ventana.
     * 2026 ya viene marcado: el mismo clic que en la empresa (boton:Seleccionar). Como mucho ~25 s.
     * Cada llamada UIA va en un hilo con tope de unos segundos: si COM no vuelve, se abandona
     * y se pulsa el botón Win32 de ese diálogo. Si sigue abierto, PARADO, aviso y se sale.
     */
    private String avanzarEjercicio(String ventana, double segundos) {
        long presupuesto = Math.min(PRESUPUESTO_EJERCICIO_MS, (long) (Math.max(8, segundos) * 1000));
        long fin = System.currentTimeMillis() + presupuesto;
        java.util.concurrent.atomic.AtomicBoolean acabado = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread tope = new Thread(() -> vigilarEjercicio(fin, acabado), "tope-ejercicio");
        tope.setDaemon(true);
        tope.start();
        try {
            return pulsarEjercicio(ventana, fin);
        } finally {
            acabado.set(true);
            tope.interrupt();
        }
    }

    /** Si el paso se queda dentro de una llamada nativa, este hilo sí puede salir. */
    private void vigilarEjercicio(long fin, java.util.concurrent.atomic.AtomicBoolean acabado) {
        long queda = fin - System.currentTimeMillis();
        try {
            if (queda > 0) {
                Thread.sleep(queda);
            }
        } catch (InterruptedException e) {
            return;
        }
        if (acabado.get() || !ejercicioSigueAbierto()) {
            return;
        }
        String linea = "PARADO: «" + DIALOGO_EJERCICIO + "» sigue abierto. No se ha podido pulsar Seleccionar a tiempo.";
        System.out.println(linea);
        System.out.flush();
        Aviso.avisar("Asistente Altas", linea, true);
        System.exit(2);
    }

    private String pulsarEjercicio(String ventana, long fin) {
        if (!ejercicioSigueAbierto()) {
            return "Ya estaba pasada esa pantalla.";
        }
        if (ventanas != null) {
            ventanas.activar(ventana);
        }
        boolean intento = false;
        String uia = null;
        if (fin - System.currentTimeMillis() > 3000) {
            abandonarUiaEjercicio = false;
            try {
                // Unos segundos: reiniciar/buscar/pulsar en el hilo principal no vuelven si COM está muerto.
                uia = Uia.conTope(6, () -> pulsarSeleccionar(ventana), null);
            } catch (RuntimeException e) {
                uia = null;
            }
            if (uia == null) {
                abandonarUiaEjercicio = true;
            } else if (uia.startsWith("Pulsado")) {
                intento = true;
            }
            if (intento && esperarCierre(fin, 1500)) {
                return frasePasada(ventana, true, "con «boton:Seleccionar»: " + uia);
            }
        }
        // UIA no ha cerrado el diálogo (no contestó, o el clic no valió). El botón de ESE diálogo, por Win32.
        if (ejercicioSigueAbierto() && fin - System.currentTimeMillis() > 800 && ventanas != null) {
            Pointer h = ventanas.handleEjercicio();
            int ms = (int) Math.min(3000, Math.max(500, fin - System.currentTimeMillis() - 500));
            if (Win32.pulsarBoton(h, "Seleccionar", ms)) {
                intento = true;
            }
            if (intento && esperarCierre(fin, 2500)) {
                return frasePasada(ventana, true, "con «boton:Seleccionar»: Pulsado boton «Seleccionar».");
            }
        }
        if (!ejercicioSigueAbierto() && intento) {
            return frasePasada(ventana, true, "con «boton:Seleccionar»: Pulsado boton «Seleccionar».");
        }
        if (!ejercicioSigueAbierto()) {
            return "Ya estaba pasada esa pantalla.";
        }
        return "PARADO: «" + DIALOGO_EJERCICIO + "» sigue abierto. El botón no ha cerrado el diálogo.";
    }

    /**
     * El mismo pulsar de la empresa, pero sin soltar el control si la llamada no vuelve:
     * Release se quedaría esperando a COM y el tope del paso no llegaría a correr.
     * Se ejecuta siempre dentro de {@link Uia#conTope}.
     */
    private String pulsarSeleccionar(String ventana) {
        Encontrado f = buscar(ventana, "boton:Seleccionar");
        if (abandonarUiaEjercicio) {
            return "";
        }
        if (f.elemento() == null) {
            return f.descripcion() == null ? "" : f.descripcion();
        }
        Uia.Elemento e = f.elemento();
        String desc = f.descripcion();
        // No se suelta el control: si la llamada no ha vuelto, Release se queda en COM y este hilo no regresa.
        Pulsacion como = pulsarSinColgarse(e, 3);
        if (como == Pulsacion.HECHA) {
            return "Pulsado " + desc + ".";
        }
        if (como == Pulsacion.SIN_VOLVER) {
            return "Pulsado " + desc + " (el programa no devolvió el control: suele pasar cuando el botón abre otra ventana encima).";
        }
        return "El control " + desc + " no se deja pulsar por dentro.";
    }

    private boolean esperarCierre(long finPaso, long maxMs) {
        long fin = Math.min(finPaso, System.currentTimeMillis() + maxMs);
        while (System.currentTimeMillis() < fin) {
            if (!ejercicioSigueAbierto()) {
                return true;
            }
            dormir(200);
        }
        return !ejercicioSigueAbierto();
    }

    /** El diálogo de ejercicio, con su título real. La principal de MN detrás no cuenta. Sin UIA. */
    private boolean ejercicioSigueAbierto() {
        return ventanas != null && ventanas.ejercicioVisible();
    }

    private static String frasePasada(String ventana, boolean ultima, String como) {
        String base = ultima || esDialogoDeEjercicio(ventana)
                ? "Pasada la pantalla «" + ventana + "» eligiendo la última fila " + como
                : "Pasada la pantalla «" + ventana + "» " + como;
        return base.endsWith(".") ? base : base + ".";
    }

    private boolean dialogoEnFrente(String ventana) {
        if (ventanas == null) {
            return false;
        }
        String activa = ventanas.activa();
        if (activa == null || activa.isBlank()) {
            return false;
        }
        String a = llano(activa);
        String v = llano(ventana);
        return a.equals(v) || a.contains(v) || (!v.isEmpty() && v.contains(a));
    }

    /**
     * Recrea UIA y espera a que en ESA ventana se vea Seleccionar.
     * No se aborta porque «desaparezca» sea true: eso es COM muerto o alguien que ya pulsó.
     */
    private boolean esperarArbolDeAvance(String ventana, double segundos, StringBuilder log) {
        if (ventanas == null) {
            return false;
        }
        long fin = System.currentTimeMillis() + (long) (Math.max(2, segundos) * 1000);
        boolean vioVacio = false;
        boolean ejercicio = esDialogoDeEjercicio(ventana);
        while (System.currentTimeMillis() < fin) {
            Uia.reiniciar();
            if (ventanas.handleDe(ventana) == null) {
                dormir(400);
                continue;
            }
            ventanas.activar(ventana);
            boolean boton = existe(ventana, "boton:Seleccionar") || existe(ventana, "panel:Seleccionar")
                    || existe(ventana, "#btnSeleccionar");
            boolean fila = existe(ventana, "fila[-1]") || existe(ventana, "fila[1]")
                    || existe(ventana, "fila:2026");
            if (ejercicio) {
                // 2026 ya está marcado: con ver Seleccionar basta para pulsar. Las filas
                // se usan después, solo si no hay ninguna marcada.
                if (boton || arbolDeEjercicioListo(arbol(ventana, 5, 80))) {
                    log.append(" · UIA: Seleccionar a la vista");
                    return true;
                }
            } else if (boton || fila || existe(ventana, "boton:Aceptar")
                    || arbolSirveParaAvanzar(arbol(ventana, 5, 80))) {
                if (vioVacio) {
                    log.append(" · UIA recuperada");
                }
                return true;
            }
            vioVacio = true;
            dormir(400);
        }
        if (vioVacio) {
            log.append(" · árbol vacío tras esperar y reiniciar UIA");
        }
        return false;
    }

    /** El nombre pedido en «fila:Empresa de prueba» → «Empresa de prueba». */
    public static String nombrePedidoDeFila(String fila) {
        if (fila == null) {
            return null;
        }
        String s = fila.trim();
        int dp = s.indexOf(':');
        if (dp >= 0) {
            s = s.substring(dp + 1).trim();
        }
        return s.isEmpty() ? null : s;
    }

    /**
     * Cierra «Información» / «Aviso» con Aceptar (el de «No se puede modificar la Empresa de prueba»).
     * No cancela «Selección de Empresa». No se da por bueno el paso.
     */
    public String apartarInformacion() {
        if (ventanas == null) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (String t : List.of("Información", "Informacion", "Aviso")) {
            if (esDialogoDeEmpresa(t) || ventanas.handleDe(t) == null) {
                continue;
            }
            if (!esAvisoInformativo(t) && ventanas.handleDe("Selección de Empresa") == null) {
                continue;
            }
            Encontrado f = buscar(t, "boton:Aceptar");
            if (f.elemento() == null) {
                continue;
            }
            f.elemento().close();
            String r = pulsar(t, "boton:Aceptar");
            if (!b.isEmpty()) {
                b.append("; ");
            }
            b.append("apartado aviso «").append(t).append("» (").append(r).append(")");
            Uia.reiniciar();
            dormir(300);
        }
        return b.toString();
    }

    /**
     * De «otro «Fila 0» = «1;Empresa de prueba»» saca el valor. Tras un modal, releer el control
     * a veces devuelve null aunque buscar ya lo había visto.
     */
    public static boolean descripcionEsLaFila(String desc, String pedida) {
        if (elValorEsLaFila(desc, pedida)) {
            return true;
        }
        if (desc == null) {
            return false;
        }
        int i = desc.indexOf("= «");
        if (i < 0) {
            i = desc.indexOf("= \"");
        }
        if (i >= 0) {
            int j = Math.max(desc.lastIndexOf('»'), desc.lastIndexOf('"'));
            if (j > i + 3) {
                return elValorEsLaFila(desc.substring(i + 3, j), pedida);
            }
        }
        return false;
    }

    /** Tras elegir la fila: o el valor es el pedido (campo igual), o se para. */
    private String probarFilaElegida(String ventana, String apodo, String pedida) {
        Encontrado f = buscar(ventana, apodo);
        String texto;
        String desc = f.descripcion();
        if (f.elemento() == null) {
            texto = desc;
        } else {
            try (Uia.Elemento e = f.elemento()) {
                String v = e.valor();
                texto = v != null && !v.isBlank() ? v : e.nombre();
            }
        }
        if (empresaProhibida(texto) || empresaProhibida(apodo) || empresaProhibida(desc)) {
            return "PARADO: la fila elegida es una empresa real («" + texto + "»). No se confirma nada.";
        }
        if (pedida == null || pedida.isBlank()) {
            return "PARADO: no se puede probar qué fila se ha elegido. No se confirma nada.";
        }
        if (elValorEsLaFila(texto, pedida) || descripcionEsLaFila(desc, pedida)) {
            return null;
        }
        return "PARADO: se pedía «" + pedida + "» y la fila elegida vale «" + texto
                + "». No se confirma nada: no está probado que sea Empresa de prueba.";
    }

    private String textoDeFila(String ventana, String apodo) {
        Encontrado f = buscar(ventana, apodo);
        if (f.elemento() == null) {
            return f.descripcion();
        }
        try (Uia.Elemento e = f.elemento()) {
            String v = e.valor();
            String n = e.nombre();
            if (v != null && !v.isBlank()) {
                return v;
            }
            return n;
        }
    }

    /** Elige UNA fila por su apodo (la empresa de prueba). No usa «la última». */
    private String elegirFila(String ventana, String apodo, String[] porQue) {
        Encontrado f = buscar(ventana, apodo);
        if (f.elemento() == null) {
            Uia.reiniciar();
            dormir(400);
            if (ventanas != null) {
                ventanas.activar(ventana);
            }
            f = buscar(ventana, apodo);
        }
        if (f.elemento() == null) {
            if (porQue != null && porQue.length > 0) {
                porQue[0] = f.descripcion();
            }
            return null;
        }
        String tipo;
        String nom;
        String valor;
        String desc = f.descripcion();
        try (Uia.Elemento e = f.elemento()) {
            tipo = e.tipo();
            nom = e.nombre();
            valor = e.valor();
        }
        String no = rechazoDeEmpresa(ventana, apodo, tipo, nom, valor);
        if (no != null) {
            if (porQue != null && porQue.length > 0) {
                porQue[0] = no;
            }
            return null;
        }
        String pedida = nombrePedidoDeFila(apodo);
        if (pedida != null
                && !elValorEsLaFila(valor, pedida)
                && !elValorEsLaFila(nom, pedida)
                && !descripcionEsLaFila(desc, pedida)) {
            if (porQue != null && porQue.length > 0) {
                porQue[0] = "el control encontrado es " + tipo + " «" + nom + "» = «" + valor
                        + "» y no se puede probar que sea «" + pedida + "»";
            }
            return null;
        }
        int[] r = recuadroClicDeFila(ventana, apodo);
        if (r == null || r[2] <= 0 || r[3] <= 0) {
            if (porQue != null && porQue.length > 0) {
                porQue[0] = "está la fila pero no se ve su recuadro para pulsarla";
            }
            return null;
        }
        new ManosRobot(false).clicEnPantalla(r[0] + r[2] / 2, r[1] + r[3] / 2);
        return apodo;
    }

    /**
     * Dónde hacer clic en una fila de MN. El recuadro de «Fila 0» a veces es TODA la rejilla: el centro
     * cae en «Mi Empresa». Se pulsa la celda «Nombre Fila 0», que sí tiene el tamaño de una fila.
     */
    private int[] recuadroClicDeFila(String ventana, String apodo) {
        Encontrado f = buscar(ventana, apodo);
        if (f.elemento() == null) {
            return recuadroDe(ventana, apodo);
        }
        String nom;
        int[] r;
        try (Uia.Elemento e = f.elemento()) {
            nom = e.nombre();
            r = e.recuadro();
        }
        if (esLaFilaEntera(nom)) {
            Encontrado celda = buscar(ventana, "fila:Nombre " + nom);
            if (celda.elemento() != null) {
                try (Uia.Elemento c = celda.elemento()) {
                    int[] rc = c.recuadro();
                    if (rc != null && rc[2] > 8 && rc[3] > 8 && (r == null || r[3] > 50 || rc[3] + 4 < r[3])) {
                        return rc;
                    }
                }
            }
        }
        return r;
    }

    /**
     * Elige la última fila visible de una lista o rejilla (el ejercicio más reciente, si están en orden).
     * Devuelve el apodo usado, o null si no hay ninguna. NUNCA se usa para elegir empresa.
     */
    private String elegirUltimaFila(String ventana) {
        if (ventanas != null) {
            ventanas.activar(ventana);
        }
        // End solo si este diálogo está delante: si no, se va al final de otra ventana.
        if (!esDialogoDeEjercicio(ventana) || dialogoEnFrente(ventana)) {
            new ManosRobot(false).tecla("End", 1);
            dormir(400);
        }
        if (!existe(ventana, "fila[-1]")) {
            return null;
        }
        Encontrado f = buscar(ventana, "fila[-1]");
        if (f.elemento() == null) {
            return null;
        }
        int[] r;
        try (Uia.Elemento e = f.elemento()) {
            if (empresaProhibida(e.valor()) || empresaProhibida(e.nombre())) {
                return null;
            }
            r = e.recuadro();
            e.enfocar();
            e.pulsar();
        }
        if (r != null && r[2] > 0 && r[3] > 0) {
            new ManosRobot(false).clicEnPantalla(r[0] + r[2] / 2, r[1] + r[3] / 2);
        }
        return "fila[-1]";
    }

    private int[] recuadroDe(String ventana, String apodo) {
        Encontrado f = buscar(ventana, apodo);
        if (f.elemento() == null) {
            return null;
        }
        try (Uia.Elemento e = f.elemento()) {
            return e.recuadro();
        }
    }

    private static void dormir(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        Percepcion.Vigilia vigilia = Percepcion.mirar();
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
            // Se despierta en cuanto Windows avisa de un cambio; los 300 ms son solo el tope por si no avisa.
            Percepcion.esperar(vigilia, c -> true, Math.min(0.3, (fin - System.currentTimeMillis()) / 1000.0));
            if (Thread.currentThread().isInterrupted()) {
                return "Interrumpido.";
            }
        }
    }
}
