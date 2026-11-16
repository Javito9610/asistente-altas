package es.amagna.asistente.altas;

import com.sun.jna.Pointer;
import es.amagna.asistente.guion.Explorador;
import es.amagna.asistente.guion.Mapa;
import es.amagna.asistente.guion.Sesion;
import es.amagna.asistente.manos.Aviso;
import es.amagna.asistente.manos.Controles;
import es.amagna.asistente.manos.ManosRobot;
import es.amagna.asistente.manos.Uia;
import es.amagna.asistente.manos.VentanasNativas;
import es.amagna.asistente.manos.Win32;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El alta en MN a partir de una lectura que ya está lista.
 *
 * <p>No entra en el programa si la lectura está parada o le falta el nombre, los apellidos o el NIF. Si MN ya está en
 * Empresa de prueba, usa esa ventana y no la reinicia. Si no está abierto, lo abre la sesión que ya existe (empresa de
 * prueba y el último ejercicio). La ficha en blanco se lee por
 * dentro; solo se rellenan los campos que encajan con un solo control, y no se pulsa Guardar si el título ya no dice
 * «Empresa de prueba» o el NIF de la pantalla no es el de la lectura.
 *
 * @package Amagna\Altas
 */
public final class Alta {
    /** Si un diálogo no se mueve en este tiempo, se para. No se espera más. */
    public static final int TOPE_SEGUNDOS = 25;

    private static final Pattern LINEA = Pattern.compile(
            "^\\s*-\\s+(\\S+)(?:\\s+«([^»]*)»)?(?:\\s+\\(#([^)]+)\\))?(?:\\s+\\[etiqueta:\\s*([^\\]]+)\\])?(?:\\s+=\\s+«([^»]*)»)?");

    private static final List<String> NIF = List.of("nif", "dni", "cif", "nie", "nifcif", "dnicif", "dninif");
    private static final List<String> NOMBRE = List.of("nombre", "nombre del cliente", "nombre cliente", "nombre de pila");
    private static final List<String> APELLIDOS = List.of("apellidos", "apellido");
    private static final List<String> TELEFONO = List.of("telefono", "tel", "movil", "telefono movil");
    private static final List<String> CORREO = List.of("correo", "email", "correo electronico");
    private static final List<String> DOMICILIO = List.of("domicilio", "direccion");
    private static final List<String> CODIGO_POSTAL = List.of("codigo postal", "c postal", "cp");
    private static final List<String> POBLACION = List.of("poblacion");
    private static final List<String> NACIMIENTO = List.of("fecha de nacimiento", "fecha nacimiento", "f nacimiento");

    /** Tope de la ficha de alta. Una llamada UIA colgada no puede gastárselo entero. */
    static final long PRESUPUESTO_FICHA_MS = 25_000;
    /** Cada lectura o escritura UIA de la ficha: unos segundos, en un hilo aparte. */
    private static final int SEGUNDOS_UIA_FICHA = 4;

    private Alta() {
    }

    /** Lo que se decide antes de tocar MN. */
    public record Decision(boolean seguir, String mensaje, Lectura.Resultado lectura) {
    }

    /** Un control tal como sale en el árbol de UIA. No se inventa: o está en el texto, o no existe. */
    public record ControlFicha(String tipo, String nombre, String id, String etiqueta, String valor, boolean activo) {
        public String apodo() {
            if (id != null && !id.isBlank()) {
                return "#" + id;
            }
            if ("boton".equals(tipo) && nombre != null && !nombre.isBlank()) {
                return "boton:" + nombre;
            }
            if (etiqueta != null && !etiqueta.isBlank()) {
                return "campo junto a " + etiqueta;
            }
            return tipo + ":" + (nombre == null ? "" : nombre);
        }
    }

    /** Un dato de la lectura que ha encajado con un solo control. */
    public record Encaje(String dato, ControlFicha control) {
    }

    /** El plano de la ficha: lo que se puede rellenar y lo que queda en duda. */
    public record Plano(List<Encaje> encajes, List<String> inciertos) {
        public Encaje de(String dato) {
            for (Encaje e : encajes) {
                if (e.dato().equals(dato)) {
                    return e;
                }
            }
            return null;
        }

        /**
         * Nombre y NIF tienen cada uno un único campo, y no queda nada en duda.
         * Los apellidos van en su campo, o —si la ficha solo tiene Nombre— junto con el nombre.
         */
        public boolean listoParaRellenar() {
            return de("nombre") != null && de("nif") != null && inciertos.isEmpty();
        }
    }

    /** Sin MN. LISTO solo con nombre, apellidos y NIF, y sin dudas. */
    public static Decision sePuedeSeguir(Lectura.Resultado r) {
        if (r == null) {
            return new Decision(false, "PARADO: no hay lectura.", null);
        }
        if (!r.completo()) {
            StringBuilder b = new StringBuilder("PARADO: la lectura no está lista.");
            for (String f : r.queFalta()) {
                b.append(" Falta ").append(f).append('.');
            }
            for (String d : r.dudas()) {
                b.append(" Duda: ").append(d).append('.');
            }
            return new Decision(false, b.toString(), r);
        }
        return new Decision(true, "LISTO: " + r.cliente().get("nombre") + " " + r.cliente().get("apellidos")
                + " · " + r.cliente().get("nif"), r);
    }

    /**
     * Lee lo que haya guardado {@code asistente leer}: el JSON, el informe con LISTO, o un PARADO / NO SE SIGUE.
     * Si no está claro, no se sigue.
     */
    public static Decision deTexto(String texto) {
        if (texto == null || texto.isBlank()) {
            return new Decision(false, "PARADO: la lectura está vacía.", null);
        }
        String t = texto.strip();
        for (String linea : t.split("\\R")) {
            String l = linea.strip();
            if (l.startsWith("PARADO")) {
                return new Decision(false, l.contains(":") ? l : "PARADO: " + l, null);
            }
        }
        if (t.contains("NO SE SIGUE")) {
            return new Decision(false, "PARADO: la lectura dice que no se sigue.", null);
        }
        try {
            return sePuedeSeguir(Lectura.deJson(t));
        } catch (RuntimeException e) {
            Lectura.Resultado inf = deInforme(t);
            if (inf != null) {
                return sePuedeSeguir(inf);
            }
            return new Decision(false, "PARADO: no se entiende la lectura.", null);
        }
    }

    /** El informe que ve una persona, el que termina en LISTO y lista los datos bajo CLIENTE. */
    static Lectura.Resultado deInforme(String t) {
        if (!t.contains("LISTO") || !t.contains("CLIENTE")) {
            return null;
        }
        Map<String, Object> cliente = new LinkedHashMap<>();
        boolean en = false;
        for (String linea : t.split("\\R")) {
            String s = linea.strip();
            if (s.equals("CLIENTE")) {
                en = true;
                continue;
            }
            if (!en) {
                continue;
            }
            if (s.isEmpty() || s.startsWith("EXPEDIENTES") || s.startsWith("LISTO") || s.startsWith("QUÉ")) {
                break;
            }
            int p = s.indexOf(':');
            if (p < 0) {
                continue;
            }
            String v = s.substring(p + 1).strip();
            if (v.equals("(no consta)")) {
                v = "";
            }
            cliente.put(s.substring(0, p).strip().toLowerCase(Locale.ROOT), v);
        }
        return new Lectura.Resultado(cliente, List.of(), List.of(), List.of(), t);
    }

    /** La ruta de menú que el mapa llama Clientes. Vacía si no está o si apunta a Contabilidad. */
    public static List<String> rutaClientes(String rutaDelMapa) {
        if (rutaDelMapa == null || rutaDelMapa.isBlank()) {
            return List.of();
        }
        if (compacto(rutaDelMapa).contains("contabilidad")) {
            return List.of();
        }
        List<String> r = new ArrayList<>();
        for (String t : rutaDelMapa.split(">")) {
            if (!t.isBlank()) {
                r.add(t.strip());
            }
        }
        return List.copyOf(r);
    }

    /** Los controles de un árbol de UIA, línea a línea. Lo que no casa con el formato se ignora. */
    public static List<ControlFicha> leerControles(String arbol) {
        List<ControlFicha> l = new ArrayList<>();
        if (arbol == null) {
            return l;
        }
        for (String linea : arbol.split("\\R")) {
            Matcher m = LINEA.matcher(linea);
            if (!m.find()) {
                continue;
            }
            boolean activo = !linea.contains("[desactivado]");
            l.add(new ControlFicha(m.group(1), vacio(m.group(2)), vacio(m.group(3)), vacio(m.group(4)),
                    vacio(m.group(5)), activo));
        }
        return l;
    }

    /**
     * Encaja los datos de la lectura con los campos de la ficha. Un dato con dos controles posibles no se rellena:
     * se apunta como incierto.
     */
    public static Plano encajar(Lectura.Resultado lectura, List<ControlFicha> controles) {
        List<ControlFicha> campos = new ArrayList<>();
        for (ControlFicha c : unicos(controles)) {
            if ("campo".equals(c.tipo())) {
                campos.add(c);
            }
        }
        List<Encaje> encajes = new ArrayList<>();
        List<String> inciertos = new ArrayList<>();
        boolean apellidosPartidos = false;
        boolean hayNombre = false;
        for (ControlFicha c : campos) {
            String texto = compacto(c.etiqueta().isBlank() ? c.nombre() : c.etiqueta());
            if (texto.equals("primerapellido") || texto.equals("segundoapellido")) {
                apellidosPartidos = true;
            }
            if ("nombre".equals(datoDe(c))) {
                hayNombre = true;
            }
        }
        for (String dato : List.of("nif", "nombre", "apellidos", "telefono", "correo", "domicilio",
                "codigo_postal", "poblacion", "fecha_nacimiento")) {
            if ("apellidos".equals(dato) && apellidosPartidos) {
                inciertos.add("apellidos: la ficha separa primer y segundo apellido, y la lectura trae uno solo");
                continue;
            }
            String valor = valorPrevisto(lectura, dato);
            if (valor.isBlank() && !Lectura.IMPRESCINDIBLES.contains(dato)) {
                continue;
            }
            List<ControlFicha> encajan = new ArrayList<>();
            for (ControlFicha c : campos) {
                if (dato.equals(datoDe(c))) {
                    encajan.add(c);
                }
            }
            if ("apellidos".equals(dato) && encajan.isEmpty() && hayNombre && !apellidosPartidos) {
                // Un solo Nombre: ahí se escribe «nombre apellidos». No es una duda.
                continue;
            }
            if ("telefono".equals(dato) && encajan.size() > 1) {
                ControlFicha movil = unicoMovil(encajan);
                if (movil != null) {
                    encajes.add(new Encaje(dato, movil));
                    continue;
                }
            }
            if (encajan.size() == 1 && encajan.get(0).activo()) {
                encajes.add(new Encaje(dato, encajan.get(0)));
            } else if (encajan.size() == 1) {
                inciertos.add(dato + ": el campo está desactivado");
            } else if (encajan.size() > 1) {
                inciertos.add(dato + ": encaja con " + encajan.size() + " campos");
            } else if (Lectura.IMPRESCINDIBLES.contains(dato)) {
                inciertos.add(dato + ": no hay un campo con ese nombre");
            }
        }
        return new Plano(List.copyOf(encajes), List.copyOf(inciertos));
    }

    /** Si hay un único Móvil entre varios teléfonos, se usa ese y el fijo se deja en blanco. */
    private static ControlFicha unicoMovil(List<ControlFicha> encajan) {
        ControlFicha movil = null;
        int n = 0;
        for (ControlFicha c : encajan) {
            if (!c.activo() || !esMovil(c)) {
                continue;
            }
            movil = c;
            n++;
        }
        return n == 1 ? movil : null;
    }

    private static boolean esMovil(ControlFicha c) {
        String texto = compacto(c.etiqueta().isBlank() ? c.nombre() : c.etiqueta());
        String id = compacto(c.id());
        return texto.equals("movil") || texto.equals("telefonomovil") || id.equals("movil") || id.equals("txtmovil");
    }

    /**
     * El botón de alta del listado de clientes. Null si no hay uno solo, o si la ventana es la de empresa o la de
     * ejercicio: ahí Alta, Baja y Modificar no se tocan.
     */
    public static ControlFicha botonDeCrear(List<ControlFicha> controles, String tituloVentana) {
        if (tituloVentana == null || Controles.esDialogoDeEmpresa(tituloVentana) || Controles.esDialogoDeEjercicio(tituloVentana)) {
            return null;
        }
        List<ControlFicha> ok = new ArrayList<>();
        for (ControlFicha c : unicos(controles)) {
            if (!"boton".equals(c.tipo()) || !c.activo()) {
                continue;
            }
            String n = compacto(c.nombre());
            String id = compacto(c.id());
            boolean es = n.equals("alta") || n.equals("crear") || n.equals("nuevo")
                    || id.equals("btnalta") || id.equals("btncrear") || id.equals("btnnuevo");
            if (es && !n.equals("baja") && !n.equals("modificar")) {
                ok.add(c);
            }
        }
        return ok.size() == 1 ? ok.get(0) : null;
    }

    /** Un único Guardar o un único Aceptar. Si están los dos, o no está ninguno, no se pulsa. */
    public static ControlFicha botonDeGuardar(List<ControlFicha> controles, String tituloFicha) {
        if (tituloFicha == null || Controles.esDialogoDeEmpresa(tituloFicha) || Controles.esDialogoDeEjercicio(tituloFicha)) {
            return null;
        }
        List<ControlFicha> ok = new ArrayList<>();
        for (ControlFicha c : unicos(controles)) {
            if (!"boton".equals(c.tipo()) || !c.activo()) {
                continue;
            }
            String n = compacto(c.nombre());
            String id = compacto(c.id());
            if (n.equals("guardar") || n.equals("aceptar") || id.equals("btnguardar") || id.equals("btnaceptar")) {
                ok.add(c);
            }
        }
        return ok.size() == 1 ? ok.get(0) : null;
    }

    /**
     * Null si se puede pulsar Guardar. Cualquier otra frase es el motivo para no hacerlo.
     * Hace falta el título de la ventana principal (tiene que seguir diciendo Empresa de prueba), el de la ficha,
     * y el NIF que se acaba de leer en pantalla.
     */
    public static String motivoParaNoGuardar(String tituloPrincipal, String tituloFicha, String nifPantalla, String nifLectura,
            ControlFicha guardar) {
        if (tituloPrincipal == null || !tituloPrincipal.toLowerCase(Locale.ROOT).contains(Controles.EMPRESA_DE_PRUEBA.toLowerCase(Locale.ROOT))) {
            return "el título no contiene «Empresa de prueba»";
        }
        if (Controles.empresaProhibida(tituloPrincipal) || (tituloFicha != null && Controles.empresaProhibida(tituloFicha))) {
            return "el título es una empresa real";
        }
        if (Controles.esDialogoDeEmpresa(tituloFicha) || Controles.esDialogoDeEjercicio(tituloFicha)) {
            return "la ventana es el diálogo de empresa o el de ejercicio";
        }
        if (!nifIgual(nifPantalla, nifLectura)) {
            return "el NIF en pantalla no coincide con la lectura";
        }
        if (guardar == null) {
            return "no hay un único botón Guardar o Aceptar";
        }
        return null;
    }

    public static boolean nifIgual(String pantalla, String lectura) {
        if (pantalla == null || lectura == null) {
            return false;
        }
        String a = compacto(pantalla);
        String b = compacto(lectura);
        return !a.isEmpty() && a.equals(b);
    }

    /**
     * Una ventana que se ve de MN, para decidir el candado sin tocar el programa.
     * {@code procesoMn} es el ejecutable. {@code dialogo} es un hijo (Clientes, un aviso), no el marco.
     */
    public record VentanaVista(String titulo, boolean procesoMn, boolean dialogo) {
    }

    /** «Clientes», «Alta Cliente» y los avisos de teléfono y correo no son la ventana de la empresa. */
    public static boolean esHijoDeTrabajo(String titulo) {
        return switch (compacto(titulo)) {
            case "clientes", "altacliente", "telefonomovil", "direccioncorreoelectronico", "direccionesdecorreoelectronico" -> true;
            default -> false;
        };
    }

    /**
     * El título del marco de MN (el que dice MNprogram), no el de un hijo.
     * Si hay dos, gana el que dice Empresa de prueba. Una empresa real no se elige si la de prueba también está.
     */
    public static String elegirPrincipal(List<VentanaVista> ventanas) {
        if (ventanas == null) {
            return null;
        }
        String mejor = null;
        int pts = Integer.MIN_VALUE;
        for (VentanaVista v : ventanas) {
            if (v == null || v.titulo() == null || v.titulo().isBlank() || esHijoDeTrabajo(v.titulo())) {
                continue;
            }
            String n = sinTildes(v.titulo());
            if (!n.contains("mnprogram")) {
                continue;
            }
            int p = 100;
            if (n.contains("empresa de prueba")) {
                p += 40;
            }
            if (v.procesoMn()) {
                p += 10;
            }
            if (!v.dialogo()) {
                p += 15;
            }
            if (Controles.empresaProhibida(v.titulo())) {
                p -= 80;
            }
            if (p > pts) {
                pts = p;
                mejor = v.titulo();
            }
        }
        return mejor;
    }

    /**
     * Null si se puede seguir. El candado mira la ventana principal. «Clientes» delante no es otra empresa.
     */
    public static String motivoCandado(String tituloPrincipal, String tituloDelante) {
        if (tituloDePrueba(tituloPrincipal)) {
            return null;
        }
        if (tituloPrincipal != null && Controles.empresaProhibida(tituloPrincipal)) {
            return "el título es una empresa real";
        }
        if (esHijoDeTrabajo(tituloDelante) || esHijoDeTrabajo(tituloPrincipal)) {
            return "no se ve «Empresa de prueba» en la ventana principal de MN";
        }
        return "el título no contiene «Empresa de prueba»";
    }

    /**
     * La principal que se ve ahora manda. Si no se ve (delante está un hijo), vale la que ya se comprobó.
     * Si la principal pasa a decir otra empresa, ya no vale.
     */
    public static boolean empresaSigueBien(String tituloPrincipalAhora, String tituloDelante, String tituloYaVisto) {
        if (tituloPrincipalAhora != null && !esHijoDeTrabajo(tituloPrincipalAhora)) {
            return tituloDePrueba(tituloPrincipalAhora);
        }
        return tituloDePrueba(tituloYaVisto);
    }

    /** True si el texto del listado (el árbol o la rejilla) ya trae ese NIF. */
    public static boolean nifYaListado(String nif, String textoListado) {
        if (nif == null || nif.isBlank() || textoListado == null || textoListado.isBlank()) {
            return false;
        }
        return compacto(textoListado).contains(compacto(nif));
    }

    /**
     * No se pulsa Alta si la ficha ya está abierta o el NIF ya sale en el listado.
     * Así no se crea un segundo cliente con el mismo NIF.
     */
    public static boolean debePulsarAlta(String nif, String textoListado, boolean fichaAbierta) {
        if (fichaAbierta || nif == null || nif.isBlank()) {
            return false;
        }
        return !nifYaListado(nif, textoListado);
    }

    /**
     * Botón para cerrar un diálogo que ya conocemos. Null si no es uno de estos o no hay un botón seguro.
     * La lista de correos se cierra con Salir, nunca con Baja, aunque Baja esté en la ventana.
     * Con {@code conservar}, el teléfono y el correo se quedan (Aceptar, Guardar o Añadir).
     * Si el dato ya está en la ficha y solo hay Cancelar, Cancelar también vale.
     */
    public static String botonDeCierre(String titulo, List<String> botones, boolean conservar, boolean datoYaEnFicha) {
        List<String> bots = botones == null ? List.of() : botones;
        String c = compacto(titulo);
        if ("direccionesdecorreoelectronico".equals(c)) {
            return elegir(bots, "salir", "cerrar");
        }
        if ("telefonomovil".equals(c) || "direccioncorreoelectronico".equals(c)) {
            if (conservar) {
                String queda = elegir(bots, "aceptar", "guardar", "anadir", "agregar", "ok");
                if (queda != null) {
                    return queda;
                }
                if (datoYaEnFicha) {
                    return elegir(bots, "cancelar", "salir", "cerrar");
                }
                return null;
            }
            return elegir(bots, "cancelar", "salir", "cerrar");
        }
        if ("altacliente".equals(c) && !conservar) {
            return elegir(bots, "cancelar");
        }
        return null;
    }

    /**
     * De delante hacia atrás: el correo suelto, la lista, el teléfono y, al final, la ficha.
     * El de delante se cierra antes. Guardar de «Alta Cliente» con el correo todavía modal no guarda.
     */
    public static int ordenDeCierre(String titulo) {
        return switch (compacto(titulo)) {
            case "direccioncorreoelectronico" -> 1;
            case "direccionesdecorreoelectronico" -> 2;
            case "telefonomovil" -> 3;
            case "altacliente" -> 4;
            default -> 9;
        };
    }

    /** Un clic de la pila: qué diálogo y qué botón, por el nombre (no por el texto Win32 del botón). */
    public record PasoCierre(String dialogo, String boton) {
    }

    /**
     * Botón del diálogo conocido, sin mirar el texto de la ventana del botón: en DevExpress no hay.
     * La lista sale con Salir. El correo suelto, con Guardar si se conserva. El teléfono, con Guardar,
     * o con Cancelar solo si ese número ya está en la ficha.
     */
    public static String botonConocido(String titulo, boolean conservar, boolean datoYaEnFicha) {
        String c = compacto(titulo);
        if ("direccionesdecorreoelectronico".equals(c)) {
            return "Salir";
        }
        if ("direccioncorreoelectronico".equals(c)) {
            return conservar ? "Guardar" : "Cancelar";
        }
        if ("telefonomovil".equals(c)) {
            if (!conservar) {
                return "Cancelar";
            }
            return datoYaEnFicha ? "Cancelar" : "Guardar";
        }
        if ("altacliente".equals(c)) {
            return conservar ? "Guardar" : "Cancelar";
        }
        return null;
    }

    /**
     * Orden en que se cierran los diálogos que están abiertos.
     * Correo suelto (Guardar), lista (Salir, nunca Baja), teléfono, y un solo Guardar de la ficha al final.
     * El Guardar de la ficha está en la lista, pero no se pulsa mientras quede uno de los otros: eso lo dice
     * {@link #sePuedeGuardarLaFicha}.
     */
    public static List<PasoCierre> secuenciaDeCierre(List<String> abiertos, boolean telefonoYaEnFicha) {
        List<String> unicos = new ArrayList<>();
        List<String> claves = new ArrayList<>();
        if (abiertos != null) {
            for (String t : abiertos) {
                if (t == null || ordenDeCierre(t) >= 9) {
                    continue;
                }
                String clave = compacto(t);
                if (claves.contains(clave)) {
                    continue;
                }
                claves.add(clave);
                unicos.add(t);
            }
        }
        unicos.sort(Comparator.comparingInt(Alta::ordenDeCierre));
        List<PasoCierre> pasos = new ArrayList<>();
        String ficha = null;
        for (String t : unicos) {
            if ("altacliente".equals(compacto(t))) {
                ficha = t;
                continue;
            }
            boolean ya = "telefonomovil".equals(compacto(t)) && telefonoYaEnFicha;
            String boton = botonConocido(t, true, ya);
            if (boton != null && !noSePulsaEnLaPila(t, boton)) {
                pasos.add(new PasoCierre(t, boton));
            }
        }
        if (ficha != null) {
            pasos.add(new PasoCierre(ficha, "Guardar"));
        }
        return pasos;
    }

    /** False si el correo, la lista o el teléfono siguen en la lista: el Guardar de la ficha no se pulsa. */
    public static boolean sePuedeGuardarLaFicha(List<String> abiertos) {
        if (abiertos == null) {
            return true;
        }
        for (String t : abiertos) {
            String c = compacto(t);
            if ("direccioncorreoelectronico".equals(c) || "direccionesdecorreoelectronico".equals(c)
                    || "telefonomovil".equals(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * El alta ha quedado guardada solo si «Alta Cliente» ya no está y el NIF se ve en la rejilla.
     * Una ficha que sigue abierta no cuenta, aunque el NIF ya estuviera en Clientes.
     */
    public static boolean altaQuedoGuardada(boolean fichaAbierta, boolean nifEnRejilla) {
        return !fichaAbierta && nifEnRejilla;
    }

    /**
     * La pila conocida se pulsa en el rectángulo de su propia ventana, sin esperar al árbol UIA.
     * Esos botones de DevExpress no tienen texto Win32 y el árbol no contesta: los 4 s no aportan el clic.
     * Guardar es el de abajo a la izquierda; Salir, el de abajo a la derecha de la lista. Baja no entra aquí.
     */
    public static boolean zonaAlMomento(String titulo, String boton) {
        String c = compacto(titulo);
        String b = compacto(boton);
        if ("direccionesdecorreoelectronico".equals(c)) {
            return "salir".equals(b);
        }
        return "guardar".equals(b) && ("direccioncorreoelectronico".equals(c) || "telefonomovil".equals(c)
                || "altacliente".equals(c));
    }

    /**
     * Qué dice un volcado de la rejilla (el mismo texto que el listado) sobre el NIF.
     * «presente» aunque el volcado lo escriba con espacios, puntos o guiones ({@code 73918462G} cuadra con
     * {@code 73918462-G} y con {@code 7391 8462 G}). «sin-leer» si no hay volcado o se ha cortado sin verlo:
     * eso no es ausencia. «ausente» solo con un volcado completo que no lo trae.
     */
    public static String veredictoNif(String nif, String volcado) {
        if (volcado == null || volcado.isBlank() || volcado.startsWith("No ")) {
            return "sin-leer";
        }
        if (nifYaListado(nif, volcado)) {
            return "presente";
        }
        if (volcado.contains("hay más")) {
            return "sin-leer";
        }
        return "ausente";
    }

    /**
     * Varias lecturas de la rejilla. Si en alguna el NIF está, está. Una lectura que no ha vuelto
     * no se convierte en «ausente» mientras no haya un volcado completo que no lo traiga.
     */
    public static String juntarLecturas(List<String> intentos) {
        boolean ausente = false;
        if (intentos != null) {
            for (String v : intentos) {
                if ("presente".equals(v)) {
                    return "presente";
                }
                if ("ausente".equals(v)) {
                    ausente = true;
                }
            }
        }
        return ausente ? "ausente" : "sin-leer";
    }

    /**
     * Tras Guardar: ficha abierta es parada; NIF presente con la ficha cerrada es guardado;
     * lecturas fallidas, aunque sean varias, no cuentan como NIF ausente.
     *
     * @return guardado, parada, ausente o sin-leer
     */
    public static String cierreTrasRejilla(boolean fichaAbierta, List<String> lecturas) {
        if (fichaAbierta) {
            return "parada";
        }
        String fin = juntarLecturas(lecturas);
        if ("presente".equals(fin) && altaQuedoGuardada(false, true)) {
            return "guardado";
        }
        return fin;
    }

    /** La línea que se imprime cuando la ficha se ha cerrado y el NIF está en Clientes. */
    public static String fraseDeGuardado(String sesion) {
        return "Guardado. La ficha «Alta Cliente» se ha cerrado. Sesión: «" + sesion + "».";
    }

    /** Baja, Alta y Modificar no se pulsan en esta pila. En la lista de correos solo vale Salir. */
    static boolean noSePulsaEnLaPila(String titulo, String boton) {
        String n = compacto(boton);
        if (n.equals("baja") || n.equals("alta") || n.equals("modificar") || n.equals("eliminar") || n.equals("borrar")) {
            return true;
        }
        if ("direccionesdecorreoelectronico".equals(compacto(titulo))) {
            return !n.equals("salir") && !n.equals("cerrar");
        }
        return false;
    }

    /**
     * Abre Clientes en la sesión de Empresa de prueba y rellena la ficha solo con lo que encaja.
     * Si MN no está abierto, lo abre esa sesión (empresa de prueba y el último ejercicio). Si ya está ahí, no se reinicia.
     * Si «Alta Cliente» ya está abierta, no se pulsa Alta otra vez: se usa esa ventana.
     * Si algo no cuadra, escribe el mapa de campos y sale sin Guardar.
     *
     * @return 0 si se ha guardado; 1 si se ha parado
     */
    public static int crearEnMn(Lectura.Resultado lectura, Path mapaFichero, Path informe, Consumer<String> decir) throws IOException {
        Decision d = sePuedeSeguir(lectura);
        if (!d.seguir()) {
            decir.accept(d.mensaje());
            return 1;
        }
        Mapa mapa = Mapa.leer(mapaFichero);
        VentanasNativas ventanas = new VentanasNativas(false);
        Controles controles = new Controles(ventanas);
        Sesion sesion = new Sesion(controles, ventanas, mapa, mapa.debeDecir(), decir).conEntradaDelMapa();
        anunciarMn(recogerVistas(), decir);
        String tituloPrincipal = principalVisible(ventanas, mapa);
        if (!tituloDePrueba(tituloPrincipal)) {
            if (tituloPrincipal != null && Controles.empresaProhibida(tituloPrincipal)) {
                return parar(decir, "el título es una empresa real («" + tituloPrincipal
                        + "»). No se cierra MN ni se rellena la ficha.", true);
            }
            // MN abierto con un título que no es la empresa de prueba ni la entrada: no se mata.
            // Si no hay proceso, o está el login, la empresa o el ejercicio, lo resuelve la sesión que ya existe.
            if (hayProcesoMn() && !entradaPendiente(ventanas)) {
                String candado = motivoCandado(tituloPrincipal, ventanas.activa());
                return parar(decir, candado + ". No se rellena la ficha.", true);
            }
            decir.accept("MN no está en Empresa de prueba. Se abre por la sesión (Empresa de prueba, último ejercicio).");
            Sesion.Estado abierto = sesion.asegurar();
            if (!abierto.lista()) {
                return parar(decir, "la sesión no está lista (" + abierto.comoEsta() + "). No se rellena la ficha.", true);
            }
            anunciarMn(recogerVistas(), decir);
            tituloPrincipal = principalVisible(ventanas, mapa);
        }
        if (ventanas.handleDe(Controles.DIALOGO_EMPRESA) != null) {
            return parar(decir, "está «Selección de Empresa». No se pulsa Alta, Baja ni Modificar.", true);
        }
        if (ventanas.handleDe(Controles.DIALOGO_EJERCICIO) != null) {
            return parar(decir, "está «Selección de Ejercicio Contable». No se sigue.", true);
        }
        String candado = motivoCandado(tituloPrincipal, ventanas.activa());
        if (candado != null) {
            return parar(decir, candado + ". No se rellena la ficha.", true);
        }
        decir.accept("Sesión: «" + tituloPrincipal + "».");
        String nif = valorDe(lectura, "nif");
        Pointer fichaYa = fichaAlta(ventanas);
        if (fichaYa != null) {
            String tituloFicha = Win32.titulo(fichaYa);
            decir.accept("Ya estaba abierta: «" + tituloFicha + "». No se pulsa Alta.");
            // Con el correo delante, UIA sobre Clientes o sobre la ficha no vuelve. El NIF se mira
            // solo si no hay diálogo de contacto; si ya está en la rejilla, no se pulsa Alta.
            if (sePuedeGuardarLaFicha(titulosContactoVisibles())) {
                Pointer listaYa = hwndPorTitulo("clientes");
                if (listaYa != null && Boolean.TRUE.equals(nifEnListado(listaYa, nif))) {
                    decir.accept("YA ESTÁ: el NIF " + nif + " ya está en Clientes. No se pulsa Alta.");
                    return 0;
                }
            }
            return rellenarFicha(lectura, ventanas, controles, mapa, fichaYa, tituloFicha, tituloPrincipal, informe, decir);
        }
        String sobraInicial = cerrarPila(null, false, false, decir);
        if (sobraInicial != null) {
            return parar(decir, sobraInicial, true);
        }
        Pointer listaYa = hwndPorTitulo("clientes");
        if (listaYa != null) {
            int ya = siYaEsta(nif, listaYa, decir);
            if (ya >= 0) {
                return ya;
            }
        }
        List<String> apodosMenu = mapa.controles().get("menu_clientes");
        String rutaTexto = apodosMenu == null || apodosMenu.isEmpty() ? "" : apodosMenu.get(0);
        List<String> ruta = rutaClientes(rutaTexto);
        if (ruta.size() < 2) {
            return parar(decir, "el mapa no trae la ruta de Clientes, o apunta a Contabilidad. No se abre el menú.", true);
        }
        String ahora = marcoAhora();
        if (tituloDePrueba(ahora)) {
            tituloPrincipal = ahora;
        }
        if (!tituloDePrueba(tituloPrincipal)) {
            return parar(decir, "el título no contiene «Empresa de prueba». No se reescribe el login ni se cierra MN.", true);
        }
        Sesion.Estado estado = sesion.mirar();
        if (!estado.lista()) {
            if (estado.comoEsta() != null && estado.comoEsta().contains("sitio equivocado")) {
                return parar(decir, "la sesión no está lista (" + estado.comoEsta()
                        + "). No se reescribe el login ni se cierra MN.", true);
            }
            decir.accept("La ventana principal sigue en «" + tituloPrincipal + "». No se reabre MN.");
        }
        decir.accept("Entrando en: " + String.join(" > ", ruta) + "…");
        var antes = Explorador.ventanasAhora();
        String pulso = conTope(() -> controles.pulsarRuta(mapa.ventana(), ruta));
        if (pulso == null) {
            return parar(decir, "el menú de Clientes no ha contestado en " + TOPE_SEGUNDOS + " s.", true);
        }
        if (!pulso.startsWith("Pulsado")) {
            return parar(decir, "no se ha podido abrir Clientes. " + pulso, true);
        }
        var salio = Explorador.esperarPantalla(antes, TOPE_SEGUNDOS);
        String ventanaLista;
        if (salio.ventana() != null) {
            ventanaLista = Explorador.tituloDe(salio.ventana());
            decir.accept("Se ha abierto: «" + ventanaLista + "».");
        } else {
            ventanaLista = ventanas.tituloDe("Clientes");
            if (ventanaLista == null) {
                return parar(decir, "Clientes no se ha abierto en " + TOPE_SEGUNDOS + " s.", true);
            }
            decir.accept("Ya estaba abierta: «" + ventanaLista + "».");
        }
        if (Controles.esDialogoDeEmpresa(ventanaLista) || Controles.esDialogoDeEjercicio(ventanaLista)) {
            return parar(decir, "lo que se ha abierto es «" + ventanaLista + "». No es la ficha de clientes.", true);
        }
        String arbolLista = conTope(() -> controles.arbol(ventanaLista, 8, 800));
        if (arbolLista == null) {
            return parar(decir, "el listado de Clientes no se ha podido leer en " + TOPE_SEGUNDOS + " s.", true);
        }
        ControlFicha crear = botonDeCrear(leerControles(arbolLista), ventanaLista);
        if (crear == null) {
            guardarInforme(informe, ventanaLista, tituloPrincipal, arbolLista, null, "no hay un único botón Alta, Crear o Nuevo");
            decir.accept("Mapa escrito en " + informe.toAbsolutePath());
            return parar(decir, "en «" + ventanaLista + "» no hay un único botón para dar de alta. No se pulsa nada. Mapa en "
                    + informe.toAbsolutePath(), true);
        }
        decir.accept("El botón de alta es " + crear.apodo() + ".");
        Pointer otraVez = fichaAlta(ventanas);
        if (otraVez != null) {
            String tituloFicha = Win32.titulo(otraVez);
            decir.accept("Ya estaba abierta: «" + tituloFicha + "». No se pulsa Alta.");
            return rellenarFicha(lectura, ventanas, controles, mapa, otraVez, tituloFicha, tituloPrincipal, informe, decir);
        }
        Pointer lista = hwndPorTitulo("clientes");
        if (lista == null) {
            lista = ventanas.handleDe("Clientes");
        }
        if (nifYaListado(nif, arbolLista) || !debePulsarAlta(nif, arbolLista, false)) {
            decir.accept("YA ESTÁ: el NIF " + nif + " ya está en Clientes. No se pulsa Alta.");
            String sobra = cerrarPila(null, false, true, decir);
            if (sobra != null) {
                return parar(decir, sobra, true);
            }
            return 0;
        }
        int ya = siYaEsta(nif, lista, decir);
        if (ya >= 0) {
            return ya;
        }
        String pulsoAlta = conTope(() -> controles.pulsar(ventanaLista, crear.apodo()));
        if (pulsoAlta == null) {
            return parar(decir, "el botón de alta no ha contestado en " + TOPE_SEGUNDOS + " s.", true);
        }
        decir.accept(pulsoAlta);
        if (!pulsoAlta.startsWith("Pulsado")) {
            guardarInforme(informe, ventanaLista, tituloPrincipal, arbolLista, null, pulsoAlta);
            return parar(decir, "no se ha pulsado el alta. " + pulsoAlta, true);
        }
        Pointer hwndFicha = esperarFicha(ventanas, 6_000);
        if (hwndFicha == null) {
            return parar(decir, "después del alta no hay una ficha válida.", true);
        }
        String ventanaFicha = Win32.titulo(hwndFicha);
        if (Controles.esDialogoDeEmpresa(ventanaFicha) || Controles.esDialogoDeEjercicio(ventanaFicha)) {
            return parar(decir, "después del alta no hay una ficha válida.", true);
        }
        if (Controles.esAvisoInformativo(ventanaFicha)) {
            return parar(decir, "ha salido un aviso «" + ventanaFicha + "». No se pulsa Aceptar.", true);
        }
        return rellenarFicha(lectura, ventanas, controles, mapa, hwndFicha, ventanaFicha, tituloPrincipal, informe, decir);
    }

    /**
     * Rellena «Alta Cliente» y, solo si el NIF releído es el de la lectura y el título sigue en la empresa de prueba,
     * pulsa Guardar una sola vez y mira el diálogo siguiente. Cada lectura UIA va en un hilo con tope de unos segundos.
     * El botón se busca por su nombre en UI Automation y se pulsa con el ratón en su rectángulo: el texto Win32
     * de un botón DevExpress no existe, e Invoke a menudo no vuelve. Cerrar la pila y ese Guardar caben en 25 s;
     * si no, PARADO, aviso y salida.
     */
    private static int rellenarFicha(Lectura.Resultado lectura, VentanasNativas ventanas, Controles controles, Mapa mapa,
            Pointer dialogo, String tituloFicha, String tituloPrincipal, Path informe, Consumer<String> decir) throws IOException {
        long fin = System.currentTimeMillis() + PRESUPUESTO_FICHA_MS;
        AtomicBoolean acabado = new AtomicBoolean(false);
        Thread tope = new Thread(() -> vigilarFicha(fin, acabado), "tope-ficha");
        tope.setDaemon(true);
        tope.start();
        Runnable soltar = () -> {
            acabado.set(true);
            tope.interrupt();
        };
        try {
            return rellenarFichaDentro(lectura, ventanas, controles, mapa, dialogo, tituloFicha, tituloPrincipal, informe, decir, fin,
                    soltar);
        } finally {
            soltar.run();
            try {
                cerrarPila(lectura, false, false, decir);
            } catch (RuntimeException e) {
                decir.accept("No se han podido cerrar los diálogos de teléfono o de correo que quedaban.");
            }
        }
    }

    /** Si el paso se queda dentro de una llamada nativa, este hilo sí puede salir. */
    private static void vigilarFicha(long fin, AtomicBoolean acabado) {
        long queda = fin - System.currentTimeMillis();
        try {
            if (queda > 0) {
                Thread.sleep(queda);
            }
        } catch (InterruptedException e) {
            return;
        }
        if (acabado.get()) {
            return;
        }
        String linea = "PARADO: la ficha no se ha podido rellenar en " + TOPE_SEGUNDOS + " s.";
        System.out.println(linea);
        System.out.flush();
        Aviso.avisar("Asistente Altas", linea, true);
        System.exit(2);
    }

    private static int rellenarFichaDentro(Lectura.Resultado lectura, VentanasNativas ventanas, Controles controles, Mapa mapa,
            Pointer dialogo, String tituloFicha, String tituloPrincipal, Path informe, Consumer<String> decir, long fin,
            Runnable soltar) throws IOException {
        decir.accept("Ficha: «" + tituloFicha + "».");
        String numeroAntes = leerEtiqueta(dialogo, "numero");
        // Si UIA no vuelve, el hilo que se queda dentro bloquea el diálogo y Guardar tampoco contesta.
        // Con las etiquetas Win32 a la vista se rellena y se pulsa antes de llamar a COM.
        boolean porWin32 = etiqueta(dialogo, "nombre") != null;
        boolean uiaViva = !porWin32;
        Plano plano = null;
        String arbol = null;
        if (porWin32) {
            decir.accept("Los cuadros se ven por Win32. No se llama a UIA: si esa llamada no vuelve, Guardar deja de contestar.");
        } else if (fin - System.currentTimeMillis() > 8_000) {
            try {
                arbol = Uia.conTope(SEGUNDOS_UIA_FICHA, () -> controles.arbol(tituloFicha, 12, 500), null);
            } catch (RuntimeException e) {
                arbol = null;
            }
            if (arbol == null) {
                uiaViva = false;
                decir.accept("UIA no ha contestado al leer la ficha; se rellena por Win32.");
            } else {
                plano = encajar(lectura, leerControles(arbol));
                if (!plano.listoParaRellenar()) {
                    decir.accept("UIA ha contestado, pero la ficha no encaja"
                            + (plano.inciertos().isEmpty() ? "." : " (" + String.join("; ", plano.inciertos()) + ").")
                            + " Se rellena por Win32.");
                    plano = null;
                }
            }
        }
        if (plano != null && uiaViva) {
            for (String dato : List.of("nif", "nombre", "apellidos", "telefono", "correo", "domicilio", "codigo_postal", "poblacion")) {
                if (fin - System.currentTimeMillis() < 8_000) {
                    decir.accept("Queda poco tiempo: el resto de la ficha se escribe por Win32.");
                    break;
                }
                Encaje e = plano.de(dato);
                if (e == null) {
                    continue;
                }
                String texto = textoPara(lectura, dato, plano);
                if (texto.isBlank()) {
                    continue;
                }
                String apodo = e.control().apodo();
                String hecho;
                try {
                    hecho = Uia.conTope(3, () -> controles.escribir(tituloFicha, apodo, texto), null);
                } catch (RuntimeException ex) {
                    hecho = null;
                }
                if (hecho == null) {
                    uiaViva = false;
                    decir.accept("UIA no ha contestado al escribir «" + dato + "»; se sigue por Win32.");
                    break;
                }
                decir.accept(hecho);
            }
        }
        String fallo = cuadrarPorWin32(dialogo, lectura, numeroAntes, decir);
        if (fallo != null) {
            guardarInforme(informe, tituloFicha, tituloPrincipal, arbol, plano, fallo);
            decir.accept("Mapa escrito en " + informe.toAbsolutePath());
            return parar(decir, fallo, true);
        }
        soltar.run();
        long finCierre = System.currentTimeMillis() + TOPE_SEGUNDOS * 1000L;
        AtomicBoolean acabadoCierre = new AtomicBoolean(false);
        Thread topeCierre = new Thread(() -> vigilarCierre(finCierre, acabadoCierre), "tope-cierre");
        topeCierre.setDaemon(true);
        topeCierre.start();
        try {
            String pila = cerrarPila(lectura, true, false, decir);
            if (pila != null) {
                guardarInforme(informe, tituloFicha, tituloPrincipal, arbol, plano, pila);
                decir.accept("Mapa escrito en " + informe.toAbsolutePath());
                return parar(decir, pila, true);
            }
            List<String> delante = titulosContactoVisibles();
            if (!sePuedeGuardarLaFicha(delante)) {
                String cual = delante.isEmpty() ? "un diálogo" : delante.get(0);
                return parar(decir, "no se pulsa Guardar de «Alta Cliente»: sigue delante «" + cual + "». No se ha guardado.", true);
            }
            String nifLectura = valorDe(lectura, "nif");
            String nifPantalla = leerEtiqueta(dialogo, "nifcif", "nif", "dni", "cif");
            String tituloAhora = marcoAhora();
            String paraCandado = tituloDePrueba(tituloAhora) ? tituloAhora : tituloPrincipal;
            if (!nifLectura.equals(nifPantalla)) {
                String porque = "el NIF en pantalla es «" + nifPantalla + "» y tiene que ser exactamente «" + nifLectura + "»";
                guardarInforme(informe, tituloFicha, paraCandado, arbol, plano, porque);
                decir.accept("Mapa escrito en " + informe.toAbsolutePath());
                return parar(decir, "no se guarda: " + porque + ".", true);
            }
            if (!empresaSigueBien(tituloAhora, ventanas.activa(), tituloPrincipal)) {
                String porque = motivoCandado(tituloAhora, ventanas.activa());
                guardarInforme(informe, tituloFicha, paraCandado, arbol, plano, porque);
                decir.accept("Mapa escrito en " + informe.toAbsolutePath());
                return parar(decir, "no se guarda: " + porque + ".", true);
            }
            ControlFicha guardar = new ControlFicha("boton", "Guardar", "", "", "", true);
            String porque = motivoParaNoGuardar(paraCandado, tituloFicha, nifPantalla, nifLectura, guardar);
            if (porque != null) {
                guardarInforme(informe, tituloFicha, paraCandado, arbol, plano, porque);
                decir.accept("Mapa escrito en " + informe.toAbsolutePath());
                return parar(decir, "no se guarda: " + porque + ".", true);
            }
            return guardarYMirar(lectura, ventanas, mapa, dialogo, tituloFicha, tituloPrincipal, nifLectura, decir, finCierre);
        } finally {
            acabadoCierre.set(true);
            topeCierre.interrupt();
        }
    }

    /** Si cerrar la pila y guardar se pasan de 25 s, este hilo sí puede salir. */
    private static void vigilarCierre(long fin, AtomicBoolean acabado) {
        long queda = fin - System.currentTimeMillis();
        try {
            if (queda > 0) {
                Thread.sleep(queda);
            }
        } catch (InterruptedException e) {
            return;
        }
        if (acabado.get()) {
            return;
        }
        String linea = "PARADO: no se ha cerrado ni guardado el alta en " + TOPE_SEGUNDOS + " s.";
        System.out.println(linea);
        System.out.flush();
        Aviso.avisar("Asistente Altas", linea, true);
        System.exit(2);
    }

    /**
     * Un solo clic en Guardar de «Alta Cliente», y solo si el correo y el teléfono ya no están delante.
     * No se da por guardado porque el clic haya salido: la ficha tiene que desaparecer y el NIF verse en Clientes.
     * Si la ficha sigue abierta, es PARADO. No hay un segundo Guardar.
     */
    private static int guardarYMirar(Lectura.Resultado lectura, VentanasNativas ventanas, Mapa mapa,
            Pointer dialogo, String tituloFicha, String tituloVisto, String nifLectura, Consumer<String> decir, long finCierre) {
        if (!Win32.visible(dialogo) || !"altacliente".equals(compacto(Win32.titulo(dialogo)))) {
            return parar(decir, "la ficha se ha cerrado antes de Guardar. No se guarda.", true);
        }
        List<String> delante = titulosContactoVisibles();
        if (!sePuedeGuardarLaFicha(delante)) {
            String cual = delante.isEmpty() ? "un diálogo" : delante.get(0);
            return parar(decir, "no se pulsa Guardar de «Alta Cliente»: sigue delante «" + cual + "». No se ha guardado.", true);
        }
        String nifAhora = leerEtiqueta(dialogo, "nifcif", "nif", "dni", "cif");
        String tituloAhora = marcoAhora();
        if (!empresaSigueBien(tituloAhora, ventanas.activa(), tituloVisto) || !nifIgual(nifAhora, nifLectura)) {
            String mostrado = tituloDePrueba(tituloAhora) ? tituloAhora : tituloVisto;
            return parar(decir, "no se pulsa Guardar: título «" + mostrado + "», NIF «" + nifAhora + "».", true);
        }
        ventanas.activar(tituloFicha);
        Map<Long, String> antes = VentanasNativas.instantanea();
        if (!pulsarGuardarUnaVez(tituloFicha, dialogo, decir)) {
            return parar(decir, "no hay un botón Guardar en la ficha, o no se ha podido pulsar. No se guarda.", true);
        }
        decir.accept("Pulsado boton «Guardar».");
        Pointer yaVisto = null;
        long hasta = finCierre - 1_000;
        for (int vuelta = 0; vuelta < 3; vuelta++) {
            Espera espera = esperarDialogo(dialogo, yaVisto, antes, ventanas, mapa, tituloVisto, hasta);
            if ("cerrada".equals(espera.tipo())) {
                return exitoCerrada(ventanas, tituloVisto, nifLectura, decir);
            }
            if ("titulo".equals(espera.tipo())) {
                return parar(decir, "el título ya no contiene «Empresa de prueba» («" + espera.detalle() + "»). No se pulsa nada más.", true);
            }
            if ("prohibida".equals(espera.tipo())) {
                return parar(decir, espera.detalle(), true);
            }
            if (!"dialogo".equals(espera.tipo()) || espera.cara() == null) {
                boolean abierta = fichaSigueAbierta(dialogo);
                if (abierta || !altaQuedoGuardada(abierta, false)) {
                    if (abierta) {
                        return parar(decir, "después de Guardar la ficha «Alta Cliente» sigue abierta. No se da por guardado.", true);
                    }
                    return exitoCerrada(ventanas, tituloVisto, nifLectura, decir);
                }
                return parar(decir, "después de Guardar no se ha podido comprobar la ficha. No se da por guardado.", true);
            }
            Cara cara = espera.cara();
            List<String> botones = nombresSiHaceFalta(cara);
            decir.accept("Diálogo: «" + cara.titulo() + "»");
            decir.accept("Texto: «" + cara.texto() + "»");
            decir.accept("Botones: " + (botones.isEmpty() ? "(ninguno por Win32; se busca el nombre por UIA)" : String.join(" | ", botones)));
            if (!sePuedeGuardarLaFicha(List.of(cara.titulo()))) {
                String boton = botonConocido(cara.titulo(), true, datoYaEnFicha(lectura, cara.titulo()));
                if (boton == null || noSePulsaEnLaPila(cara.titulo(), boton) || !pulsarPorUia(cara.hwnd(), cara.titulo(), boton, decir)) {
                    return parar(decir, "sigue delante «" + cara.titulo() + "» y no se ha podido cerrar. "
                            + "No se pulsa el Guardar de «Alta Cliente» otra vez.", true);
                }
                yaVisto = cara.hwnd();
                antes = VentanasNativas.instantanea();
                continue;
            }
            PasoDialogo paso = decidirDialogo(cara.titulo(), cara.texto(), botones);
            if ("peligro".equals(paso.accion()) || "no-claro".equals(paso.accion())) {
                String porque = "peligro".equals(paso.accion())
                        ? "hay un botón o un texto que no se puede pulsar (Baja, Eliminar, Modificar)."
                        : "no está claro que confirme el alta ni que falte un dato conocido.";
                return parar(decir, "el diálogo «" + cara.titulo() + "» no se toca: " + porque
                        + " Texto: «" + cara.texto() + "».", true);
            }
            if ("confirmar".equals(paso.accion())) {
                if (noSePulsaEnLaPila(cara.titulo(), paso.boton())) {
                    return parar(decir, "no se pulsa «" + paso.boton() + "» en «" + cara.titulo() + "».", true);
                }
                boolean pulsado = pulsarCara(cara, paso.boton(), decir);
                if (!pulsado && !paso.yaGuardado()) {
                    return parar(decir, "no se ha podido pulsar «" + paso.boton() + "» en «" + cara.titulo() + "».", true);
                }
                if (paso.yaGuardado()) {
                    return exitoConfirmado(ventanas, dialogo, tituloVisto, nifLectura, decir);
                }
                yaVisto = cara.hwnd();
                antes = VentanasNativas.instantanea();
                continue;
            }
            if (paso.campo().isBlank()) {
                return parar(decir, "no se guarda: el diálogo pide un dato que no se rellena"
                        + " (no es un campo que ya sepamos, y no se inventa Provincia)."
                        + " Texto: «" + cara.texto() + "».", true);
            }
            if (!paso.boton().isBlank() && !pulsarCara(cara, paso.boton(), decir)) {
                return parar(decir, "no se ha podido cerrar el aviso con «" + paso.boton() + "». Texto: «" + cara.texto() + "».", true);
            }
            if (!paso.boton().isBlank()) {
                for (int i = 0; i < 15 && Win32.visible(cara.hwnd()); i++) {
                    dormir(200);
                }
                if (Win32.visible(cara.hwnd())) {
                    return parar(decir, "el aviso no se ha cerrado después de «" + paso.boton() + "». Texto: «" + cara.texto() + "».", true);
                }
            }
            String fallo = rellenarSolo(dialogo, lectura, paso.campo(), decir);
            if (fallo != null) {
                return parar(decir, fallo, true);
            }
            if (!fichaSigueAbierta(dialogo)) {
                return exitoCerrada(ventanas, tituloVisto, nifLectura, decir);
            }
            return parar(decir, "la ficha «Alta Cliente» sigue abierta después de rellenar «" + paso.campo()
                    + "». No se pulsa Guardar otra vez.", true);
        }
        boolean abiertaAlFinal = fichaSigueAbierta(dialogo);
        if (abiertaAlFinal || !altaQuedoGuardada(abiertaAlFinal, false)) {
            if (abiertaAlFinal) {
                return parar(decir, "la ficha «Alta Cliente» sigue abierta. No se da por guardado.", true);
            }
            return exitoCerrada(ventanas, tituloVisto, nifLectura, decir);
        }
        return parar(decir, "siguen saliendo diálogos después de Guardar. No se pulsa más.", true);
    }

    /** Lo que se decide al leer un diálogo, sin pulsarlo. accion: confirmar, error, peligro o no-claro. */
    public record PasoDialogo(String accion, String boton, String campo, boolean yaGuardado) {
    }

    /**
     * Confirmación de guardar (Sí / Aceptar), aviso de un dato que falta, o algo que no se toca.
     * Provincia, País y Número no se rellenan. Baja, Eliminar y Modificar empresa no se pulsan.
     */
    public static PasoDialogo decidirDialogo(String titulo, String texto, List<String> botones) {
        List<String> bots = botones == null ? List.of() : botones;
        // Teléfono y correo se cierran por su título, aunque la lista tenga Baja: ese botón no se elige.
        // Si Win32 no ve el texto del botón (DevExpress), el nombre conocido manda igual.
        String conocido = botonDeCierre(titulo, bots, true, true);
        if (conocido == null && esHijoDeTrabajo(titulo) && !"altacliente".equals(compacto(titulo))) {
            conocido = botonConocido(titulo, true, false);
        }
        if (conocido != null && esHijoDeTrabajo(titulo) && !"altacliente".equals(compacto(titulo))) {
            return new PasoDialogo("confirmar", conocido, "", false);
        }
        String todo = sinTildes((titulo == null ? "" : titulo) + "\n" + (texto == null ? "" : texto));
        if (textoPeligroso(todo) || hayBotonPeligroso(bots)) {
            return new PasoDialogo("peligro", "", "", false);
        }
        boolean pregunta = todo.contains("desea guardar") || todo.contains("deseas guardar")
                || todo.contains("quieres guardar") || todo.contains("quiere guardar")
                || todo.contains("desea grabar") || todo.contains("guardar los datos")
                || todo.contains("guardar los cambios") || todo.contains("guardar el cliente")
                || todo.contains("confirmar el alta") || todo.contains("confirmar alta")
                || todo.contains("grabar los datos")
                || ((todo.contains("guardar") || todo.contains("grabar"))
                        && (todo.contains("¿") || todo.contains("?") || todo.contains("seguro") || todo.contains("confirma")));
        boolean ya = todo.contains("se ha guardado") || todo.contains("se han guardado")
                || todo.contains("guardado correctamente") || todo.contains("guardados correctamente")
                || todo.contains("se ha grabado") || todo.contains("se han grabado")
                || todo.contains("alta realizada") || todo.contains("cliente guardado")
                || todo.contains("datos guardados") || todo.contains("registro guardado")
                || todo.contains("el cliente se ha guardado") || todo.contains("alta correcta");
        if (pregunta || ya) {
            String boton = elegir(bots, "si", "aceptar", "ok");
            if (boton == null) {
                boton = elegir(bots, "guardar");
            }
            if (boton != null) {
                return new PasoDialogo("confirmar", boton, "", ya && !pregunta);
            }
        }
        if (esAvisoDeDato(todo)) {
            String cerrar = elegir(bots, "aceptar", "ok");
            return new PasoDialogo("error", cerrar == null ? "" : cerrar, campoQueFalta(todo), false);
        }
        return new PasoDialogo("no-claro", "", "", false);
    }

    private static boolean esAvisoDeDato(String todo) {
        return todo.contains("obligatori") || todo.contains("requerid")
                || todo.contains("debe rellenar") || todo.contains("debe indicar")
                || todo.contains("debe introducir") || todo.contains("debe informar")
                || todo.contains("es necesario") || todo.contains("no puede estar vac")
                || todo.contains("no puede quedar vac") || todo.contains("falta el")
                || todo.contains("faltan ") || todo.contains("introduzca")
                || todo.contains("informe el") || todo.contains("campo vacio");
    }

    /** El único campo que el aviso pide, si es uno que ya viene en la lectura. Vacío si hay que inventarlo o hay varios. */
    private static String campoQueFalta(String todo) {
        boolean prohibido = tienePalabra(todo, "provincia") || tienePalabra(todo, "pais")
                || todo.contains("primer contacto") || todo.contains("tipo de cliente") || todo.contains("tipo cliente")
                || (tienePalabra(todo, "numero") && !tienePalabra(todo, "telefono") && !tienePalabra(todo, "nif")
                        && !todo.contains("codigo postal"));
        List<String> vistos = new ArrayList<>();
        if (todo.contains("codigo postal") || todo.contains("c postal")) {
            anotar(vistos, "codigo_postal");
        }
        if (tienePalabra(todo, "poblacion")) {
            anotar(vistos, "poblacion");
        }
        if (tienePalabra(todo, "correo") || tienePalabra(todo, "email")) {
            anotar(vistos, "correo");
        }
        if (tienePalabra(todo, "nif") || tienePalabra(todo, "dni") || tienePalabra(todo, "cif") || tienePalabra(todo, "nie")) {
            anotar(vistos, "nif");
        }
        if (tienePalabra(todo, "movil") || tienePalabra(todo, "telefono")) {
            anotar(vistos, "telefono");
        }
        if (tienePalabra(todo, "direccion") || tienePalabra(todo, "domicilio")) {
            anotar(vistos, "domicilio");
        }
        if (todo.contains("fecha de nacimiento") || todo.contains("fecha nacimiento")) {
            anotar(vistos, "fecha_nacimiento");
        }
        if (tienePalabra(todo, "nombre")) {
            anotar(vistos, "nombre");
        }
        if (prohibido || vistos.size() != 1) {
            return "";
        }
        return vistos.get(0);
    }

    private static void anotar(List<String> vistos, String campo) {
        if (!vistos.contains(campo)) {
            vistos.add(campo);
        }
    }

    private static boolean textoPeligroso(String todo) {
        return todo.contains("modificar empresa") || todo.contains("dar de baja")
                || tienePalabra(todo, "eliminar") || tienePalabra(todo, "borrar");
    }

    private static boolean hayBotonPeligroso(List<String> botones) {
        for (String b : botones) {
            if (esBotonPeligroso(b)) {
                return true;
            }
        }
        return false;
    }

    private static boolean esBotonPeligroso(String nombre) {
        String n = compacto(nombre);
        return n.equals("baja") || n.equals("eliminar") || n.equals("borrar")
                || n.equals("modificar") || n.equals("modificarempresa");
    }

    private static String elegir(List<String> botones, String... compactosOk) {
        for (String q : compactosOk) {
            for (String b : botones) {
                if (b != null && compacto(b).equals(q)) {
                    return b.strip();
                }
            }
        }
        return null;
    }

    private static String sinTildes(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT);
    }

    private static boolean tienePalabra(String texto, String palabra) {
        return Pattern.compile("(^|[^a-z0-9])" + Pattern.quote(palabra) + "([^a-z0-9]|$)").matcher(texto).find();
    }

    private record Cara(Pointer hwnd, String titulo, String texto, List<String> botones) {
    }

    private record Espera(String tipo, Cara cara, String detalle) {
    }

    private static Espera mirarConTope(Pointer ficha, Pointer yaVisto, Map<Long, String> antes, VentanasNativas ventanas, Mapa mapa,
            String tituloVisto) {
        AtomicBoolean acabado = new AtomicBoolean(false);
        long fin = System.currentTimeMillis() + TOPE_SEGUNDOS * 1000L;
        Thread tope = new Thread(() -> vigilarDialogo(fin, acabado), "tope-dialogo");
        tope.setDaemon(true);
        tope.start();
        try {
            return esperarDialogo(ficha, yaVisto, antes, ventanas, mapa, tituloVisto, fin - 1_000);
        } finally {
            acabado.set(true);
            tope.interrupt();
        }
    }

    private static void vigilarDialogo(long fin, AtomicBoolean acabado) {
        long queda = fin - System.currentTimeMillis();
        try {
            if (queda > 0) {
                Thread.sleep(queda);
            }
        } catch (InterruptedException e) {
            return;
        }
        if (acabado.get()) {
            return;
        }
        String linea = "PARADO: un diálogo no se ha resuelto en " + TOPE_SEGUNDOS + " s.";
        System.out.println(linea);
        System.out.flush();
        Aviso.avisar("Asistente Altas", linea, true);
        System.exit(2);
    }

    private static Espera esperarDialogo(Pointer ficha, Pointer yaVisto, Map<Long, String> antes, VentanasNativas ventanas,
            Mapa mapa, String tituloVisto, long fin) {
        while (System.currentTimeMillis() < fin) {
            if (ventanas.handleDe(Controles.DIALOGO_EMPRESA) != null) {
                return new Espera("prohibida", null, "está «Selección de Empresa». No se pulsa nada.");
            }
            if (ventanas.handleDe(Controles.DIALOGO_EJERCICIO) != null) {
                return new Espera("prohibida", null, "está «Selección de Ejercicio Contable». No se sigue.");
            }
            if (!Win32.visible(ficha) || !"altacliente".equals(compacto(Win32.titulo(ficha)))) {
                return new Espera("cerrada", null, "");
            }
            String tituloP = marcoAhora();
            if (!empresaSigueBien(tituloP, ventanas.activa(), tituloVisto)) {
                String detalle = tituloDePrueba(tituloP) ? tituloP : (tituloP == null ? tituloVisto : tituloP);
                return new Espera("titulo", null, detalle == null ? "" : detalle);
            }
            Cara cara = dialogoEncima(ficha, yaVisto, antes, ventanas, mapa);
            if (cara != null) {
                String junto = compacto(cara.titulo() + " " + cara.texto());
                if (junto.contains("contabilidad")) {
                    return new Espera("prohibida", null, "ha salido una ventana de Contabilidad («" + cara.titulo()
                            + "»). No se abre ni se pulsa.");
                }
                return new Espera("dialogo", cara, "");
            }
            dormir(250);
        }
        return new Espera("nada", null, "");
    }

    /** El aviso nuevo, o el que tiene bloqueada la ficha. No es la propia «Alta Cliente» ni la ventana principal. */
    private static Cara dialogoEncima(Pointer ficha, Pointer yaVisto, Map<Long, String> antes, VentanasNativas ventanas, Mapa mapa) {
        Pointer principal = ventanas.handleDe(mapa.ventana());
        long idFicha = Pointer.nativeValue(ficha);
        long idPrincipal = principal == null ? -1 : Pointer.nativeValue(principal);
        long idVisto = yaVisto == null ? -1 : Pointer.nativeValue(yaVisto);
        Pointer consola = Win32.consola();
        long idConsola = consola == null ? -1 : Pointer.nativeValue(consola);
        boolean bloqueada = !Win32.User32.I.IsWindowEnabled(ficha);
        Map<Long, String> ahora = VentanasNativas.instantanea();
        Pointer delante = Win32.User32.I.GetForegroundWindow();
        long idDelante = delante == null ? -1 : Pointer.nativeValue(delante);
        Long elegida = null;
        if (idDelante > 0 && idDelante != idFicha && idDelante != idPrincipal && idDelante != idConsola && idDelante != idVisto
                && ahora.containsKey(idDelante) && !esRuido(ahora.get(idDelante)) && !"#32768".equals(Win32.clase(delante))
                && !antes.containsKey(idDelante)) {
            elegida = idDelante;
        }
        if (elegida == null) {
            for (Map.Entry<Long, String> e : ahora.entrySet()) {
                long id = e.getKey();
                if (id == idFicha || id == idPrincipal || id == idConsola || id == idVisto || esRuido(e.getValue())) {
                    continue;
                }
                if (antes.containsKey(id) || "#32768".equals(Win32.clase(new Pointer(id)))) {
                    continue;
                }
                elegida = id;
                break;
            }
        }
        if (elegida == null && bloqueada) {
            for (Pointer h : Win32.hijos(ficha)) {
                if (Win32.visible(h) && "#32770".equals(Win32.clase(h))) {
                    return leerCara(h);
                }
            }
            return null;
        }
        return elegida == null ? null : leerCara(new Pointer(elegida));
    }

    private static boolean esRuido(String titulo) {
        if (titulo == null || titulo.isBlank()) {
            return true;
        }
        String t = sinTildes(titulo);
        return t.contains("cursor") || t.contains("asistente altas") || t.contains("powershell") || t.contains("windows terminal");
    }

    private static Cara leerCara(Pointer hwnd) {
        String titulo = Win32.titulo(hwnd);
        List<String> botones = new ArrayList<>();
        List<String> lineas = new ArrayList<>();
        for (Pointer h : Win32.hijos(hwnd)) {
            if (!Win32.visible(h)) {
                continue;
            }
            String clase = Win32.clase(h).toLowerCase(Locale.ROOT);
            String t = Win32.titulo(h);
            if (t == null || t.isBlank()) {
                continue;
            }
            t = t.strip();
            if (clase.contains("button")) {
                if (!botones.contains(t)) {
                    botones.add(t);
                }
            } else if (clase.contains("static") && !lineas.contains(t)) {
                lineas.add(t);
            }
        }
        return new Cara(hwnd, titulo == null ? "" : titulo, String.join("\n", lineas), List.copyOf(botones));
    }

    private static boolean pulsarCara(Cara cara, String boton, Consumer<String> decir) {
        if (boton == null || boton.isBlank() || esBotonPeligroso(boton) || noSePulsaEnLaPila(cara.titulo(), boton)) {
            return false;
        }
        return pulsarPorUia(cara.hwnd(), cara.titulo(), boton, decir);
    }

    /**
     * Un solo clic en Guardar de la ficha, por el nombre UIA y el centro de su rectángulo.
     * No se usa el texto Win32 del botón ni Invoke. Si el correo o el teléfono siguen delante, no se pulsa.
     */
    private static boolean pulsarGuardarUnaVez(String tituloFicha, Pointer dialogo, Consumer<String> decir) {
        if (!sePuedeGuardarLaFicha(titulosContactoVisibles())) {
            decir.accept("No se pulsa Guardar de «" + tituloFicha + "»: hay un diálogo de correo o de teléfono delante.");
            return false;
        }
        return pulsarPorUia(dialogo, tituloFicha, "Guardar", decir);
    }

    /** Cada lectura del árbol: unos segundos, en un hilo. Si vuelve vacío, se reinicia UIA una vez. */
    private static final int SEGUNDOS_UIA_BOTON = 4;

    /**
     * Encuentra el control por su nombre en UI Automation y pulsa el centro de su BoundingRectangle
     * con {@link ManosRobot#clicEnPantalla}. Invoke no se llama: en estos botones a menudo no vuelve.
     */
    private static boolean pulsarPorUia(Pointer hwnd, String titulo, String boton, Consumer<String> decir) {
        if (hwnd == null || boton == null || boton.isBlank() || noSePulsaEnLaPila(titulo, boton)) {
            return false;
        }
        if (zonaAlMomento(titulo, boton)) {
            return pulsarPorZona(hwnd, titulo, boton, decir);
        }
        if (fichaTapada(titulo, decir)) {
            return false;
        }
        ArbolBoton arbol = leerArbolBoton(hwnd);
        if (arbol != null && arbol.nodos == 0) {
            decir.accept("UIA vacío en «" + titulo + "». Reinicio una vez.");
            Uia.reiniciar();
            dormir(300);
            arbol = leerArbolBoton(hwnd);
        }
        if (arbol != null && arbol.nodos > 0) {
            PiezaBoton elegido = elegirPieza(arbol, boton, prefiereIzquierda(boton));
            if (elegido != null && compacto(elegido.nombre()).equals(compacto(boton))
                    && !noSePulsaEnLaPila(titulo, elegido.nombre())) {
                int cx = elegido.x() + elegido.w() / 2;
                int cy = elegido.y() + elegido.h() / 2;
                decir.accept("Clic «" + elegido.nombre() + "» de «" + titulo + "» en " + cx + "," + cy + ".");
                new ManosRobot(false).clicEnPantalla(cx, cy);
                return true;
            }
            decir.accept("No hay un control UIA llamado «" + boton + "» en «" + titulo + "».");
        } else if (arbol == null) {
            decir.accept("UIA no ha contestado en " + SEGUNDOS_UIA_BOTON + " s en «" + titulo + "».");
        } else {
            decir.accept("UIA sigue vacío en «" + titulo + "». No se ha visto «" + boton + "».");
        }
        return pulsarPorZona(hwnd, titulo, boton, decir);
    }

    /** No se arranca UIA (ni el clic de la ficha) mientras el correo, la lista o el teléfono sigan abiertos. */
    private static boolean fichaTapada(String titulo, Consumer<String> decir) {
        if (!"altacliente".equals(compacto(titulo))) {
            return false;
        }
        List<String> delante = titulosContactoVisibles();
        if (sePuedeGuardarLaFicha(delante)) {
            return false;
        }
        String cual = delante.isEmpty() ? "un diálogo" : delante.get(0);
        decir.accept("No se llama a UIA en «Alta Cliente»: sigue delante «" + cual + "».");
        return true;
    }

    /**
     * Si UIA no contesta, el clic va al sitio conocido del rectángulo de ESA ventana.
     * Guardar del correo es el botón de la izquierda, abajo. Salir es el de la derecha de la lista.
     * Guardar de la ficha es el de abajo a la izquierda. El texto Win32 del botón DevExpress no existe.
     */
    private static boolean pulsarPorZona(Pointer hwnd, String titulo, String boton, Consumer<String> decir) {
        if (hwnd == null || noSePulsaEnLaPila(titulo, boton) || fichaTapada(titulo, decir)) {
            return false;
        }
        int[] rc = Win32.rectangulo(hwnd);
        if (rc == null) {
            decir.accept("No hay rectángulo de «" + titulo + "». No se pulsa «" + boton + "».");
            return false;
        }
        int[] p = zonaBoton(titulo, boton, rc[0], rc[1], rc[2], rc[3]);
        decir.accept("Clic por zona «" + boton + "» de «" + titulo + "» en " + p[0] + "," + p[1] + ".");
        new ManosRobot(false).clicEnPantalla(p[0], p[1]);
        return true;
    }

    /**
     * Centro del botón dentro del rectángulo de la ventana (x, y, ancho, alto), en píxeles de pantalla.
     * El par Guardar/Cancelar del correo y del teléfono va centrado abajo: Guardar a la izquierda.
     * Salir de la lista va abajo a la derecha. Guardar de «Alta Cliente» va abajo a la izquierda.
     */
    public static int[] zonaBoton(String titulo, String boton, int x, int y, int w, int h) {
        String c = compacto(titulo);
        String b = compacto(boton);
        int cy = y + h - Math.max(28, Math.min(42, h / 8));
        int cx;
        if ("direccionesdecorreoelectronico".equals(c)) {
            cx = x + w - Math.max(52, Math.min(78, w / 8));
        } else if ("altacliente".equals(c)) {
            cx = "cancelar".equals(b) ? x + 150 : x + 58;
        } else if ("cancelar".equals(b)) {
            cx = x + w / 2 + Math.max(48, Math.min(72, w / 8));
        } else {
            cx = x + w / 2 - Math.max(48, Math.min(72, w / 8));
        }
        int minX = x + 20;
        int maxX = x + Math.max(21, w - 20);
        int minY = y + Math.min(48, Math.max(1, h / 3));
        int maxY = y + h - 14;
        if (cx < minX) {
            cx = minX;
        }
        if (cx > maxX) {
            cx = maxX;
        }
        if (cy < minY) {
            cy = minY;
        }
        if (cy > maxY) {
            cy = maxY;
        }
        return new int[] {cx, cy};
    }

    /** Guardar, Aceptar y Cancelar suelen estar abajo a la izquierda; Salir, abajo a la derecha. */
    private static boolean prefiereIzquierda(String boton) {
        String n = compacto(boton);
        return n.equals("guardar") || n.equals("aceptar") || n.equals("cancelar") || n.equals("si") || n.equals("ok");
    }

    private static ArbolBoton leerArbolBoton(Pointer hwnd) {
        try {
            return Uia.conTope(SEGUNDOS_UIA_BOTON, () -> caminarBoton(hwnd), null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ArbolBoton caminarBoton(Pointer hwnd) {
        Uia.Elemento raiz = Uia.deVentana(hwnd);
        if (raiz == null) {
            return new ArbolBoton(0, null, List.of());
        }
        try {
            int[] rr = raiz.recuadro();
            int[] ventana = rr != null && rr.length == 4 && rr[2] > 0 && rr[3] > 0 ? rr : null;
            List<PiezaBoton> piezas = new ArrayList<>();
            int[] nodos = {0};
            bajarBoton(raiz, 0, piezas, nodos);
            return new ArbolBoton(nodos[0], ventana, piezas);
        } finally {
            raiz.close();
        }
    }

    private static void bajarBoton(Uia.Elemento e, int profundidad, List<PiezaBoton> piezas, int[] nodos) {
        if (profundidad > 12 || nodos[0] > 800) {
            return;
        }
        for (Uia.Elemento h : e.hijosHasta(200, null)) {
            try {
                if (nodos[0] > 800) {
                    return;
                }
                nodos[0]++;
                String nombre = h.nombre() == null ? "" : h.nombre();
                String tipo = h.tipo() == null ? "" : h.tipo();
                int[] r = h.recuadro();
                if (r != null && r.length == 4 && r[2] > 0 && r[3] > 0) {
                    piezas.add(new PiezaBoton(nombre, tipo, r[0], r[1], r[2], r[3]));
                } else if (!nombre.isBlank()) {
                    piezas.add(new PiezaBoton(nombre, tipo, 0, 0, 0, 0));
                }
                bajarBoton(h, profundidad + 1, piezas, nodos);
            } finally {
                h.close();
            }
        }
    }

    private static PiezaBoton elegirPieza(ArbolBoton arbol, String boton, boolean izquierda) {
        String quiero = compacto(boton);
        List<PiezaBoton> candidatos = new ArrayList<>();
        for (PiezaBoton p : arbol.piezas()) {
            if (!compacto(p.nombre()).equals(quiero) || noSePulsaEnLaPila(null, p.nombre())) {
                continue;
            }
            if (p.w() < 16 || p.h() < 12 || p.x() < -20 || p.y() < -20) {
                continue;
            }
            candidatos.add(p);
        }
        if (candidatos.isEmpty()) {
            return null;
        }
        int mejor = -1;
        for (PiezaBoton p : candidatos) {
            mejor = Math.max(mejor, rangoBoton(p.tipo()));
        }
        List<PiezaBoton> top = new ArrayList<>();
        for (PiezaBoton p : candidatos) {
            if (rangoBoton(p.tipo()) == mejor) {
                top.add(p);
            }
        }
        List<PiezaBoton> uso = top;
        int[] ventana = arbol.ventana();
        if (ventana != null && ventana.length == 4 && ventana[3] > 80) {
            int corte = ventana[1] + ventana[3] / 2;
            List<PiezaBoton> abajo = new ArrayList<>();
            for (PiezaBoton p : top) {
                if (p.y() + p.h() / 2 >= corte) {
                    abajo.add(p);
                }
            }
            if (!abajo.isEmpty()) {
                uso = abajo;
            }
        }
        PiezaBoton gana = uso.get(0);
        for (PiezaBoton p : uso) {
            if (izquierda) {
                if (p.x() < gana.x() || (p.x() == gana.x() && p.w() * p.h() > gana.w() * gana.h())) {
                    gana = p;
                }
            } else {
                int py = p.y() + p.h();
                int gy = gana.y() + gana.h();
                if (py > gy || (py == gy && p.x() > gana.x())) {
                    gana = p;
                }
            }
        }
        return gana;
    }

    private static int rangoBoton(String tipo) {
        return switch (tipo == null ? "" : tipo) {
            case "boton", "boton-con-flecha" -> 4;
            case "panel" -> 3;
            case "otro", "grupo" -> 2;
            default -> 1;
        };
    }

    private record PiezaBoton(String nombre, String tipo, int x, int y, int w, int h) {
    }

    private record ArbolBoton(int nodos, int[] ventana, List<PiezaBoton> piezas) {
    }

    private static boolean fichaSigueAbierta(Pointer dialogo) {
        return dialogo != null && Win32.visible(dialogo) && "altacliente".equals(compacto(Win32.titulo(dialogo)));
    }

    /** Si Win32 no ha visto botones, los nombres salen del árbol UIA. El clic sigue siendo por el rectángulo. */
    private static List<String> nombresSiHaceFalta(Cara cara) {
        if (cara.botones() != null && !cara.botones().isEmpty()) {
            return cara.botones();
        }
        ArbolBoton arbol = leerArbolBoton(cara.hwnd());
        if (arbol != null && arbol.nodos == 0) {
            Uia.reiniciar();
            arbol = leerArbolBoton(cara.hwnd());
        }
        if (arbol == null) {
            return List.of();
        }
        List<String> nombres = new ArrayList<>();
        for (PiezaBoton p : arbol.piezas()) {
            if (p.nombre().isBlank() || p.w() < 16 || p.h() < 12 || rangoBoton(p.tipo()) < 2) {
                continue;
            }
            if (!nombres.contains(p.nombre())) {
                nombres.add(p.nombre());
            }
        }
        return nombres;
    }

    private static List<String> titulosContactoVisibles() {
        List<String> titulos = new ArrayList<>();
        for (Pointer h : dialogosConocidos()) {
            String t = Win32.titulo(h);
            String c = compacto(t);
            if (!"direccioncorreoelectronico".equals(c) && !"direccionesdecorreoelectronico".equals(c)
                    && !"telefonomovil".equals(c)) {
                continue;
            }
            if (!titulos.contains(t)) {
                titulos.add(t);
            }
        }
        titulos.sort(Comparator.comparingInt(Alta::ordenDeCierre));
        return titulos;
    }

    /**
     * Correo, lista, teléfono y ficha, por el título Win32. No filtra por el nombre del proceso:
     * el título de la principal dice MNprogram y el de estos diálogos no, y aun así son de MN.
     * También mira los hijos, por si el diálogo no es una ventana de primer nivel.
     */
    private static List<Pointer> dialogosConocidos() {
        List<Pointer> r = new ArrayList<>();
        Set<Long> vistos = new HashSet<>();
        List<Pointer> raices = new ArrayList<>();
        Win32.User32.I.EnumWindows(new Win32.User32.EnumProc() {
            @Override
            public boolean invoke(Pointer hwnd, Pointer datos) {
                if (hwnd == null || !Win32.User32.I.IsWindowVisible(hwnd)) {
                    return true;
                }
                Pointer copia = new Pointer(Pointer.nativeValue(hwnd));
                anotarConocido(r, vistos, copia);
                raices.add(copia);
                return true;
            }
        }, null);
        anotarConocido(r, vistos, Win32.User32.I.GetForegroundWindow());
        for (Pointer raiz : raices) {
            String t = Win32.titulo(raiz);
            if (t == null) {
                continue;
            }
            String plano = sinTildes(t);
            if (ordenDeCierre(t) >= 9 && !plano.contains("mnprogram") && !"clientes".equals(compacto(t))) {
                continue;
            }
            for (Pointer h : Win32.hijos(raiz)) {
                anotarConocido(r, vistos, h);
            }
        }
        return r;
    }

    private static void anotarConocido(List<Pointer> r, Set<Long> vistos, Pointer hwnd) {
        if (hwnd == null || !Win32.visible(hwnd)) {
            return;
        }
        long id = Pointer.nativeValue(hwnd);
        if (!vistos.add(id)) {
            return;
        }
        String t = Win32.titulo(hwnd);
        if (t == null || ordenDeCierre(t) >= 9) {
            vistos.remove(id);
            return;
        }
        r.add(new Pointer(id));
    }

    /** Solo el campo que el aviso ha pedido. No toca Provincia, País ni Número. */
    private static String rellenarSolo(Pointer dialogo, Lectura.Resultado lectura, String campo, Consumer<String> decir) {
        Direccion dir = partirDomicilio(valorDe(lectura, "domicilio"));
        String valor = switch (campo) {
            case "nombre" -> textoPara(lectura, "nombre", null);
            case "nif" -> valorDe(lectura, "nif");
            case "telefono" -> valorDe(lectura, "telefono");
            case "correo" -> valorDe(lectura, "correo");
            case "domicilio" -> dir.calle;
            case "codigo_postal" -> dir.cp;
            case "poblacion" -> dir.poblacion;
            case "fecha_nacimiento" -> valorDe(lectura, "fecha_nacimiento");
            default -> "";
        };
        if (valor.isBlank()) {
            return "el dato «" + campo + "» no está en la lectura. No se inventa.";
        }
        return switch (campo) {
            case "nombre" -> exigir(dialogo, decir, "Nombre", valor, "nombre");
            case "nif" -> exigir(dialogo, decir, "NIF/CIF", valor, "nifcif", "nif", "dni", "cif");
            case "telefono" -> etiqueta(dialogo, "movil") != null
                    ? exigir(dialogo, decir, "Móvil", valor, "movil")
                    : exigir(dialogo, decir, "Teléfono", valor, "telefono");
            case "correo" -> exigir(dialogo, decir, "Correo Electrónico", valor, "correoelectronico", "correo", "email");
            case "domicilio" -> exigir(dialogo, decir, "Dirección", valor, "direccion");
            case "codigo_postal" -> exigir(dialogo, decir, "Código Postal", valor, "codigopostal", "cp");
            case "poblacion" -> {
                String puesto = escribirEtiqueta(dialogo, valor, false, "poblacion");
                if (!valor.equals(puesto)) {
                    yield "no se ha podido dejar Población en «" + valor + "».";
                }
                decir.accept("Población: «" + puesto + "».");
                yield null;
            }
            case "fecha_nacimiento" -> exigir(dialogo, decir, "Fecha de nacimiento", valor, "fechanacimiento", "fechadenacimiento");
            default -> "no se rellena el campo «" + campo + "».";
        };
    }

    private static int exitoCerrada(VentanasNativas ventanas, String tituloVisto, String nif, Consumer<String> decir) {
        String titulo = marcoAhora();
        if (!empresaSigueBien(titulo, ventanas.activa(), tituloVisto)) {
            String mostrado = tituloDePrueba(titulo) ? titulo : tituloVisto;
            return parar(decir, "la ficha «Alta Cliente» se ha cerrado, pero el título ya no contiene «Empresa de prueba»"
                    + (mostrado == null ? "." : " («" + mostrado + "»)."), true);
        }
        String sesion = tituloDePrueba(titulo) ? titulo : tituloVisto;
        return publicarEnListado(nif, fraseDeGuardado(sesion), decir);
    }

    private static int exitoConfirmado(VentanasNativas ventanas, Pointer ficha, String tituloVisto, String nif, Consumer<String> decir) {
        String titulo = marcoAhora();
        if (!empresaSigueBien(titulo, ventanas.activa(), tituloVisto)) {
            String mostrado = tituloDePrueba(titulo) ? titulo : tituloVisto;
            return parar(decir, "hay una confirmación de guardado, pero el título ya no contiene «Empresa de prueba»"
                    + (mostrado == null ? "." : " («" + mostrado + "»)."), true);
        }
        boolean abierta = fichaSigueAbierta(ficha);
        if (!altaQuedoGuardada(abierta, !abierta)) {
            return parar(decir, "el diálogo confirma el guardado, pero «Alta Cliente» sigue abierta. No se da por guardado.", true);
        }
        String sesion = tituloDePrueba(titulo) ? titulo : tituloVisto;
        return publicarEnListado(nif, "Guardado. El diálogo confirma que se ha guardado. La ficha «Alta Cliente» se ha cerrado. Sesión: «"
                + sesion + "».", decir);
    }

    /**
     * La ficha ya no está. Se relee la rejilla como el volcado del listado y se reintenta un par de veces:
     * una lectura que no vuelve no demuestra que el NIF falte. Si está y «Alta Cliente» sigue cerrada, es guardado.
     */
    private static int publicarEnListado(String nif, String frase, Consumer<String> decir) {
        if (altaClienteVisible()) {
            return parar(decir, "la ficha «Alta Cliente» sigue abierta. No se da por guardado.", true);
        }
        List<String> intentos = new ArrayList<>();
        String linea = null;
        for (int i = 0; i < 3 && linea == null; i++) {
            dormir(i == 0 ? 250 : 400);
            if (altaClienteVisible()) {
                return parar(decir, "la ficha «Alta Cliente» sigue abierta. No se da por guardado.", true);
            }
            String volcado = volcadoRejillaClientes(nif);
            String v = veredictoNif(nif, volcado);
            intentos.add(v);
            if ("presente".equals(v)) {
                linea = lineaDelVolcado(nif, volcado);
            }
        }
        if (altaClienteVisible()) {
            return parar(decir, "la ficha «Alta Cliente» sigue abierta. No se da por guardado.", true);
        }
        String fin = cierreTrasRejilla(false, intentos);
        if ("guardado".equals(fin) && linea != null && altaQuedoGuardada(false, true)) {
            decir.accept(linea);
            decir.accept(frase);
            return 0;
        }
        if (!"ausente".equals(fin)) {
            return parar(decir, "la ficha «Alta Cliente» se ha cerrado, pero no se ha podido leer la rejilla de Clientes.", true);
        }
        return parar(decir, "la ficha «Alta Cliente» se ha cerrado, pero el NIF " + nif
                + " no se ve en la rejilla de Clientes.", true);
    }

    /**
     * Deja los cuadros de ESE diálogo como la lectura, por la etiqueta Win32 de al lado.
     * No toca Tipo Cliente, Provincia, País ni Número. Si Número cambia, se restaura y no se guarda.
     *
     * @return null si los campos obligatorios han quedado igual que la lectura
     */
    private static String cuadrarPorWin32(Pointer dialogo, Lectura.Resultado lectura, String numeroAntes, Consumer<String> decir) {
        boolean apellidosAparte = etiqueta(dialogo, "apellidos", "primerapellido", "segundoapellido") != null;
        String nombre = apellidosAparte ? valorDe(lectura, "nombre") : textoPara(lectura, "nombre", null);
        Direccion dir = partirDomicilio(valorDe(lectura, "domicilio"));
        boolean hayMovil = etiqueta(dialogo, "movil") != null;
        String fallo = exigir(dialogo, decir, "Nombre", nombre, "nombre");
        if (fallo != null) {
            return fallo;
        }
        fallo = exigir(dialogo, decir, "NIF/CIF", valorDe(lectura, "nif"), "nifcif", "nif", "dni", "cif");
        if (fallo != null) {
            return fallo;
        }
        String tel = valorDe(lectura, "telefono");
        if (!tel.isBlank()) {
            fallo = dejarContacto(dialogo, lectura, decir, hayMovil ? "Móvil" : "Teléfono", tel, false,
                    hayMovil ? new String[] {"movil"} : new String[] {"telefono"});
            if (fallo != null) {
                return fallo;
            }
        }
        String correo = valorDe(lectura, "correo");
        if (!correo.isBlank()) {
            fallo = dejarContacto(dialogo, lectura, decir, "Correo Electrónico", correo, true,
                    "correoelectronico", "correo", "email");
            if (fallo != null) {
                return fallo;
            }
        }
        if (!dir.calle.isBlank() && etiqueta(dialogo, "direccion") != null) {
            fallo = exigir(dialogo, decir, "Dirección", dir.calle, "direccion");
            if (fallo != null) {
                return fallo;
            }
        }
        if (!dir.cp.isBlank() && etiqueta(dialogo, "codigopostal", "cp") != null) {
            fallo = exigir(dialogo, decir, "Código Postal", dir.cp, "codigopostal", "cp");
            if (fallo != null) {
                return fallo;
            }
        }
        if (!dir.poblacion.isBlank() && etiqueta(dialogo, "poblacion") != null) {
            String puesto = escribirEtiqueta(dialogo, dir.poblacion, true, "poblacion");
            if (dir.poblacion.equals(puesto)) {
                decir.accept("Población: «" + puesto + "».");
            } else {
                decir.accept("Población no se ha dejado en «" + dir.poblacion + "»"
                        + (puesto == null ? " (no se ha podido escribir)." : " (ha quedado «" + puesto + "»).")
                        + " No se elige otra del desplegable.");
            }
        }
        String numeroAhora = leerEtiqueta(dialogo, "numero");
        if (numeroAntes != null && numeroAhora != null && !numeroAntes.equals(numeroAhora)) {
            escribirEtiqueta(dialogo, numeroAntes, false, "numero");
            return "el Número ha cambiado de «" + numeroAntes + "» a «" + numeroAhora + "». Se deja como estaba. No se guarda.";
        }
        return null;
    }

    /** Escribe el cuadro de esa etiqueta y exige que al releerlo sea exactamente el texto. */
    private static String exigir(Pointer dialogo, Consumer<String> decir, String rotulo, String esperado, String... etiquetas) {
        String ahora = escribirEtiqueta(dialogo, esperado, false, etiquetas);
        if (!esperado.equals(ahora)) {
            return "no se ha podido dejar " + rotulo + " en «" + esperado + "»"
                    + (ahora == null ? " (el campo no se ha podido leer)." : " (en pantalla ha quedado «" + ahora + "»).")
                    + " No se guarda.";
        }
        decir.accept(rotulo + ": «" + ahora + "».");
        return null;
    }

    /**
     * Escribe el cuadro que está en el mismo grupo que la etiqueta.
     * En un desplegable editable, si el texto no queda exactamente, se restaura y no se adivina un ítem.
     *
     * @return lo que se lee después, o null si no hay campo
     */
    private static String escribirEtiqueta(Pointer dialogo, String texto, boolean comboBlando, String... etiquetas) {
        Pointer lab = etiqueta(dialogo, etiquetas);
        if (lab == null || texto == null) {
            return null;
        }
        Pointer editor = editorDelGrupo(Win32.padre(lab));
        if (editor == null) {
            return null;
        }
        boolean combo = Win32.clase(editor).toLowerCase(Locale.ROOT).contains("combobox");
        Pointer inner = combo ? editInterior(editor) : null;
        String antes = Win32.leerTexto(editor);
        String antesInner = inner == null ? null : Win32.leerTexto(inner);
        String ya = textoVisible(editor, inner);
        if (texto.equals(ya)) {
            return ya;
        }
        // Aunque SendMessageTimeout caduque, el cuadro a veces queda escrito un instante después
        // (le pasó a Móvil, que es de solo lectura: el texto estaba y la lectura en caliente salió vacía).
        Win32.ponerTexto(editor, texto, 1500);
        if (inner != null) {
            Win32.ponerTexto(inner, texto, 1500);
        }
        String leido = leerIgual(editor, inner, texto);
        if (!texto.equals(leido)) {
            Win32.ponerTexto(editor, texto, 2000);
            if (inner != null) {
                Win32.ponerTexto(inner, texto, 1500);
            }
            leido = leerIgual(editor, inner, texto);
        }
        if (combo && comboBlando && !texto.equals(leido)) {
            if (antes != null) {
                Win32.ponerTexto(editor, antes, 1000);
            }
            if (inner != null && antesInner != null) {
                Win32.ponerTexto(inner, antesInner, 1000);
            }
            return textoVisible(editor, inner);
        }
        return leido;
    }

    /** Relee unos instantes: un WM_SETTEXT puede verse vacío si se pregunta antes de que el cuadro lo pinte. */
    private static String leerIgual(Pointer editor, Pointer inner, String esperado) {
        String leido = null;
        for (int i = 0; i < 4; i++) {
            leido = textoVisible(editor, inner);
            if (esperado.equals(leido)) {
                return leido;
            }
            if (i + 1 < 4) {
                dormir(200);
            }
        }
        return leido;
    }

    private static String textoVisible(Pointer editor, Pointer inner) {
        if (inner != null) {
            String a = Win32.leerTexto(inner);
            if (a != null && !a.isBlank()) {
                return a;
            }
        }
        return editor == null ? null : Win32.leerTexto(editor);
    }

    private static String leerEtiqueta(Pointer dialogo, String... etiquetas) {
        Pointer lab = etiqueta(dialogo, etiquetas);
        if (lab == null) {
            return null;
        }
        Pointer editor = editorDelGrupo(Win32.padre(lab));
        if (editor == null) {
            return null;
        }
        boolean combo = Win32.clase(editor).toLowerCase(Locale.ROOT).contains("combobox");
        return textoVisible(editor, combo ? editInterior(editor) : null);
    }

    /** La etiqueta (STATIC) cuyo texto, ya compacto, es uno de estos. */
    private static Pointer etiqueta(Pointer dialogo, String... compactosOk) {
        for (Pointer h : Win32.hijos(dialogo)) {
            if (!Win32.visible(h)) {
                continue;
            }
            if (!Win32.clase(h).toLowerCase(Locale.ROOT).contains("static")) {
                continue;
            }
            String t = compacto(Win32.titulo(h));
            for (String q : compactosOk) {
                if (t.equals(q)) {
                    return h;
                }
            }
        }
        return null;
    }

    /** El cuadro del grupo de la etiqueta: el EDIT de WinForms, o el desplegable si no hay cuadro. */
    private static Pointer editorDelGrupo(Pointer grupo) {
        if (grupo == null) {
            return null;
        }
        Pointer forms = null;
        Pointer combo = null;
        Pointer edit = null;
        for (Pointer h : Win32.hijos(grupo)) {
            if (!Win32.visible(h)) {
                continue;
            }
            String c = Win32.clase(h).toLowerCase(Locale.ROOT);
            if (c.contains("combobox")) {
                combo = h;
            } else if (c.contains("windowsforms10.edit")) {
                forms = h;
            } else if (c.equals("edit")) {
                edit = h;
            }
        }
        if (forms != null) {
            return forms;
        }
        if (combo != null) {
            return combo;
        }
        return edit;
    }

    private static Pointer editInterior(Pointer combo) {
        for (Pointer h : Win32.hijos(combo)) {
            String c = Win32.clase(h).toLowerCase(Locale.ROOT);
            if (c.equals("edit")) {
                return h;
            }
        }
        return null;
    }

    /** El botón visible cuyo texto es el pedido (también «&Guardar»). Si hay uno de clase button, ese. */
    private static Pointer buscarBoton(Pointer dialogo, String compactoBuscado) {
        Pointer otro = null;
        for (Pointer h : Win32.hijos(dialogo)) {
            if (!Win32.visible(h)) {
                continue;
            }
            String clase = Win32.clase(h).toLowerCase(Locale.ROOT);
            if (clase.contains("static")) {
                continue;
            }
            if (!compacto(Win32.titulo(h)).equals(compactoBuscado)) {
                continue;
            }
            if (clase.contains("button")) {
                return h;
            }
            otro = h;
        }
        return otro;
    }

    private static Pointer fichaAlta(VentanasNativas ventanas) {
        Pointer h = ventanas.handleDe("Alta Cliente");
        if (h == null) {
            return null;
        }
        return "altacliente".equals(compacto(Win32.titulo(h))) ? h : null;
    }

    private static Pointer esperarFicha(VentanasNativas ventanas, long ms) {
        long fin = System.currentTimeMillis() + ms;
        Pointer h = fichaAlta(ventanas);
        while (h == null && System.currentTimeMillis() < fin) {
            dormir(200);
            h = fichaAlta(ventanas);
        }
        return h;
    }

    private static boolean tituloDePrueba(String titulo) {
        if (titulo == null || !titulo.toLowerCase(Locale.ROOT).contains(Controles.EMPRESA_DE_PRUEBA.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return !Controles.empresaProhibida(titulo);
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int parar(Consumer<String> decir, String msg, boolean avisar) {
        String linea = msg.startsWith("PARADO") ? msg : "PARADO: " + msg;
        decir.accept(linea);
        if (avisar) {
            String corto = linea.length() > 180 ? linea.substring(0, 180) : linea;
            Aviso.avisar("Asistente Altas", corto, true);
        }
        return 1;
    }

    private static void guardarInforme(Path informe, String ventana, String tituloPrincipal, String arbol, Plano plano, String porque)
            throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("Ventana: ").append(ventana == null ? "" : ventana).append('\n');
        b.append("Título principal: ").append(tituloPrincipal == null ? "" : tituloPrincipal).append('\n');
        b.append("No se ha guardado: ").append(porque).append("\n\n");
        if (plano != null) {
            b.append("Encajes:\n");
            if (plano.encajes().isEmpty()) {
                b.append("  (ninguno)\n");
            }
            for (Encaje e : plano.encajes()) {
                b.append("  ").append(e.dato()).append(" → ").append(e.control().apodo())
                        .append(" («").append(e.control().nombre()).append("»");
                if (!e.control().etiqueta().isBlank()) {
                    b.append(", etiqueta ").append(e.control().etiqueta());
                }
                b.append(")\n");
            }
            b.append("Inciertos:\n");
            if (plano.inciertos().isEmpty()) {
                b.append("  (ninguno)\n");
            }
            for (String i : plano.inciertos()) {
                b.append("  · ").append(i).append('\n');
            }
            b.append('\n');
        }
        b.append(arbol == null ? "" : arbol);
        Files.writeString(informe, b.toString(), StandardCharsets.UTF_8);
    }

    private static String valorDe(Lectura.Resultado lectura, String dato) {
        if (lectura == null || lectura.cliente() == null) {
            return "";
        }
        Object v = lectura.cliente().get(dato);
        return v == null ? "" : String.valueOf(v).strip();
    }

    /** A qué dato de la lectura corresponde este campo, o null si no se puede decir con seguridad. */
    static String datoDe(ControlFicha c) {
        if (c == null || !"campo".equals(c.tipo())) {
            return null;
        }
        String texto = compacto(c.etiqueta().isBlank() ? c.nombre() : c.etiqueta());
        String id = compacto(c.id());
        String porTexto = datoPorTexto(texto);
        String porId = datoPorId(id);
        if (porTexto != null && porId != null && !porTexto.equals(porId)) {
            return null;
        }
        return porId != null ? porId : porTexto;
    }

    private static String datoPorTexto(String compacto) {
        if (compacto.isEmpty()) {
            return null;
        }
        if (contieneAlguno(compacto, "apellido", "comercial", "empresa", "banco", "fiscal")) {
            if (APELLIDOS.contains(compacto) || compacto.equals("primerapellido") || compacto.equals("segundoapellido")) {
                return compacto.equals("primerapellido") || compacto.equals("segundoapellido") ? null : "apellidos";
            }
            return null;
        }
        if (coincide(compacto, NIF)) {
            return "nif";
        }
        if (coincide(compacto, NOMBRE)) {
            return "nombre";
        }
        if (coincide(compacto, APELLIDOS)) {
            return "apellidos";
        }
        if (coincide(compacto, TELEFONO)) {
            return "telefono";
        }
        if (coincide(compacto, CORREO)) {
            return "correo";
        }
        if (coincide(compacto, CODIGO_POSTAL)) {
            return "codigo_postal";
        }
        if (coincide(compacto, POBLACION)) {
            return "poblacion";
        }
        if (coincide(compacto, DOMICILIO)) {
            return "domicilio";
        }
        if (coincide(compacto, NACIMIENTO)) {
            return "fecha_nacimiento";
        }
        return null;
    }

    /** El texto que hay que dejar en ese campo. El nombre suelto se junta con los apellidos si no tienen campo. */
    public static String textoPara(Lectura.Resultado lectura, String dato, Plano plano) {
        if ("nombre".equals(dato) && (plano == null || plano.de("apellidos") == null)) {
            String n = valorDe(lectura, "nombre");
            String a = valorDe(lectura, "apellidos");
            if (n.isBlank()) {
                return a;
            }
            if (a.isBlank()) {
                return n;
            }
            return n + " " + a;
        }
        if ("domicilio".equals(dato) || "codigo_postal".equals(dato) || "poblacion".equals(dato)) {
            Direccion dir = partirDomicilio(valorDe(lectura, "domicilio"));
            boolean partida = plano != null && (plano.de("codigo_postal") != null || plano.de("poblacion") != null);
            return switch (dato) {
                case "codigo_postal" -> dir.cp;
                case "poblacion" -> dir.poblacion;
                default -> partida ? dir.calle : valorDe(lectura, "domicilio");
            };
        }
        return valorDe(lectura, dato);
    }

    private static String valorPrevisto(Lectura.Resultado lectura, String dato) {
        if ("codigo_postal".equals(dato) || "poblacion".equals(dato)) {
            return textoPara(lectura, dato, null);
        }
        return valorDe(lectura, dato);
    }

    /** Calle, código postal y población sacados de un domicilio «calle, 15001 ciudad, provincia». */
    record Direccion(String calle, String cp, String poblacion) {
    }

    static Direccion partirDomicilio(String domicilio) {
        String t = domicilio == null ? "" : domicilio.strip();
        Matcher m = Pattern.compile("^(.*?)[,\\s]+(\\d{5})\\s+(.+)$").matcher(t);
        if (!m.matches()) {
            return new Direccion(t, "", "");
        }
        String calle = m.group(1).strip().replaceAll("[,\\s]+$", "");
        String resto = m.group(3).strip();
        int coma = resto.indexOf(',');
        String poblacion = coma > 0 ? resto.substring(0, coma).strip() : resto;
        return new Direccion(calle, m.group(2), poblacion);
    }

    private static boolean coincide(String compacto, List<String> sinonimos) {
        for (String s : sinonimos) {
            if (compacto.equals(Alta.compacto(s))) {
                return true;
            }
        }
        return false;
    }

    private static String datoPorId(String compacto) {
        return switch (compacto) {
            case "txtnif", "nif", "txtdni", "dni", "txtcif", "cif", "txtnie", "nie" -> "nif";
            case "txtnombre", "nombre" -> "nombre";
            case "txtapellidos", "apellidos", "txtapellido", "apellido" -> "apellidos";
            case "txttelefono", "telefono", "txtmovil", "movil" -> "telefono";
            case "txtcorreo", "correo", "txtemail", "email" -> "correo";
            case "txtdomicilio", "domicilio", "txtdireccion", "direccion" -> "domicilio";
            case "txtfechanacimiento", "fechanacimiento" -> "fecha_nacimiento";
            default -> null;
        };
    }

    private static boolean contieneAlguno(String texto, String... trozos) {
        for (String t : trozos) {
            if (texto.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private static List<ControlFicha> unicos(List<ControlFicha> controles) {
        Map<String, ControlFicha> vistos = new LinkedHashMap<>();
        for (ControlFicha c : controles) {
            String clave = c.id().isBlank()
                    ? c.tipo() + "|" + c.nombre() + "|" + c.etiqueta()
                    : c.tipo() + "|#" + c.id();
            vistos.putIfAbsent(clave, c);
        }
        return new ArrayList<>(vistos.values());
    }

    private static String vacio(String s) {
        return s == null ? "" : s.strip();
    }

    static String compacto(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = Character.toLowerCase(n.charAt(i));
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static String conTope(java.util.concurrent.Callable<String> tarea) {
        return Explorador.conTiempo(TOPE_SEGUNDOS, tarea, null);
    }

    /** El marco de MN que se ve ahora, oculto incluido. Un hijo «Clientes» no cuenta. */
    private static String marcoAhora() {
        return elegirPrincipal(recogerVistas());
    }

    /** La principal de MN. Si el candado ya ve Empresa de prueba, esa manda aunque haya un hijo delante. */
    private static String principalVisible(VentanasNativas ventanas, Mapa mapa) {
        String t = elegirPrincipal(recogerVistas());
        if (tituloDePrueba(t)) {
            return t;
        }
        String visto = ventanas.tituloDe(mapa.ventana());
        if (tituloDePrueba(visto)) {
            return visto;
        }
        return t != null ? t : visto;
    }

    private static boolean hayProcesoMn() {
        return !VentanasNativas.topesMn().isEmpty();
    }

    /** Login, empresa o ejercicio: la sesión que ya existe los resuelve. No es un título de empresa real. */
    private static boolean entradaPendiente(VentanasNativas ventanas) {
        return ventanas.handleDe(Controles.DIALOGO_EMPRESA) != null
                || ventanas.handleDe(Controles.DIALOGO_EJERCICIO) != null
                || ventanas.handleDe("Control de acceso") != null;
    }

    private static List<VentanaVista> recogerVistas() {
        List<VentanaVista> l = new ArrayList<>();
        java.util.Set<String> vistos = new java.util.LinkedHashSet<>();
        for (Pointer h : VentanasNativas.topesMn()) {
            long id = Pointer.nativeValue(h);
            String t = Win32.titulo(h);
            if (t != null && !t.isBlank() && vistos.add(t)) {
                l.add(new VentanaVista(t, true, VentanasNativas.esVentanaDialogo(id)));
            }
            String dueno = VentanasNativas.tituloDueno(id);
            if (dueno != null && vistos.add(dueno)) {
                l.add(new VentanaVista(dueno, true, false));
            }
        }
        return l;
    }

    private static void anunciarMn(List<VentanaVista> vistas, Consumer<String> decir) {
        for (VentanaVista v : vistas) {
            if (v == null || v.titulo() == null || v.titulo().isBlank()) {
                continue;
            }
            boolean hijo = esHijoDeTrabajo(v.titulo());
            boolean marco = sinTildes(v.titulo()).contains("mnprogram");
            if (!hijo && !marco) {
                continue;
            }
            decir.accept("Ventana MN: «" + v.titulo() + "»" + (hijo ? " (diálogo)." : "."));
        }
    }

    /** 0 si el NIF ya está y se han cerrado las sobras. 1 si no se ha podido leer. -1 si no está. */
    private static int siYaEsta(String nif, Pointer lista, Consumer<String> decir) {
        if (lista == null) {
            return -1;
        }
        Boolean visto = nifEnListado(lista, nif);
        if (visto == null) {
            return parar(decir, "no se ha podido leer el listado de Clientes. No se pulsa Alta.", true);
        }
        if (visto) {
            decir.accept("YA ESTÁ: el NIF " + nif + " ya está en Clientes. No se pulsa Alta.");
            String sobra = cerrarPila(null, false, true, decir);
            if (sobra != null) {
                return parar(decir, sobra, true);
            }
            return 0;
        }
        return -1;
    }

    /**
     * True si el NIF sale en la rejilla. False si en lo leído no está: entonces se puede pulsar Alta.
     * Null solo si UIA no contesta (no hay árbol, o se pasa de los 25 s): en ese caso no se pulsa Alta.
     */
    private static Boolean nifEnListado(Pointer lista, String nif) {
        if (lista == null || nif == null || nif.isBlank()) {
            return Boolean.FALSE;
        }
        String aguja = compacto(nif);
        if (nifEnHijos(lista, aguja)) {
            return Boolean.TRUE;
        }
        try {
            return Uia.conTope(TOPE_SEGUNDOS, () -> {
                boolean[] cortado = {false};
                Uia.Elemento raiz = Uia.deVentana(lista);
                if (raiz == null) {
                    return null;
                }
                int[] filasVistas = {0};
                int[] filasAnunciadas = {-1};
                try {
                    if (walkNif(raiz, aguja, 16, new int[] {0}, 8000, cortado, filasVistas, filasAnunciadas)) {
                        return Boolean.TRUE;
                    }
                } finally {
                    raiz.close();
                }
                int vistos = 0;
                for (Pointer h : Win32.hijos(lista)) {
                    if (vistos >= 8) {
                        break;
                    }
                    int[] rc = Win32.rectangulo(h);
                    if (rc == null || rc[2] < 80 || rc[3] < 40) {
                        continue;
                    }
                    vistos++;
                    Boolean uno = walkNifVentana(h, aguja, cortado);
                    if (Boolean.TRUE.equals(uno)) {
                        return Boolean.TRUE;
                    }
                }
                return Boolean.FALSE;
            }, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Volcado de la rejilla de Clientes, leído como el listado: nombre y valor de cada control,
     * profundidad 14 y hasta 3000 nodos. La fila de MN trae el NIF en el valor («330;…;73918462G»).
     * Null si la ventana o UIA no contestan. Si el árbol se corta sin el NIF, el texto lo dice con «hay más»:
     * eso no es un volcado completo.
     */
    private static String volcadoRejillaClientes(String nif) {
        Pointer lista = ventanaClientes();
        if (lista == null || nif == null || nif.isBlank()) {
            return null;
        }
        String aguja = compacto(nif);
        StringBuilder b = new StringBuilder();
        boolean[] cortado = {false};
        boolean[] vioFila = {false};
        try {
            Boolean hallado = leerVolcado(lista, aguja, b, cortado, vioFila);
            // El volcado del listado, si el árbol sale vacío, reinicia UIA una vez. Un corte por tiempo no:
            // ese hilo puede seguir dentro de COM y no se reutiliza el mismo texto.
            if (Boolean.FALSE.equals(hallado) && !vioFila[0] && !cortado[0] && !nifYaListado(nif, b.toString())) {
                Uia.reiniciar();
                dormir(300);
                b.setLength(0);
                cortado[0] = false;
                hallado = leerVolcado(lista, aguja, b, cortado, vioFila);
            }
            if (nifYaListado(nif, b.toString())) {
                return b.toString();
            }
            if (hallado == null || !vioFila[0] || cortado[0]) {
                if (b.length() == 0) {
                    return null;
                }
                b.append("… (hay más; busca uno concreto por su nombre)\n");
                return b.toString();
            }
            return b.toString();
        } catch (RuntimeException e) {
            if (nifYaListado(nif, b.toString())) {
                return b.toString();
            }
            return null;
        }
    }

    /** True si el NIF ya está en el texto, false si el árbol se ha recorrido, null si UIA no ha vuelto. */
    private static Boolean leerVolcado(Pointer lista, String aguja, StringBuilder b, boolean[] cortado, boolean[] vioFila) {
        return Uia.conTope(6, () -> {
            Uia.Elemento raiz = Uia.deVentana(lista);
            if (raiz == null) {
                return Boolean.FALSE;
            }
            try {
                b.append("Controles de «").append(Win32.titulo(lista)).append("»:\n");
                boolean esta = pintarComoListado(raiz, b, 14, new int[] {0}, 3000, cortado, vioFila, aguja);
                return esta ? Boolean.TRUE : Boolean.FALSE;
            } finally {
                raiz.close();
            }
        }, null);
    }

    /**
     * El mismo recorrido que el volcado del listado ({@code Controles#arbol}): tipo, nombre y valor.
     * Para en cuanto el NIF está en un nombre o en un valor. Si la fila entera ya trae valor, no baja a las celdas:
     * el NIF va en esa línea. Si el árbol se corta, {@code cortado} queda a true.
     */
    private static boolean pintarComoListado(Uia.Elemento e, StringBuilder b, int quedan, int[] cuenta, int tope,
            boolean[] cortado, boolean[] vioFila, String aguja) {
        if (e == null || quedan < 0) {
            return false;
        }
        boolean[] hayMas = {false};
        for (Uia.Elemento h : e.hijosHasta(400, hayMas)) {
            try {
                if (cuenta[0]++ >= tope) {
                    cortado[0] = true;
                    return false;
                }
                String nombre = h.nombre() == null ? "" : h.nombre();
                String valor = null;
                try {
                    valor = h.valor();
                } catch (RuntimeException ex) {
                    valor = null;
                }
                if (Controles.esLaFilaEntera(nombre)) {
                    vioFila[0] = true;
                }
                if (!nombre.isBlank() || (valor != null && !valor.isBlank())) {
                    b.append("- ").append(h.tipo());
                    if (!nombre.isBlank()) {
                        b.append(" «").append(nombre).append("»");
                    }
                    if (valor != null && !valor.isBlank()) {
                        String mostrado = valor;
                        boolean traeNif = aguja != null && !aguja.isEmpty() && compacto(valor).contains(aguja);
                        if (!traeNif && mostrado.length() > 300) {
                            mostrado = mostrado.substring(0, 300);
                        }
                        b.append(" = «").append(mostrado.replace('\r', ' ').replace('\n', ' ')).append("»");
                    }
                    b.append('\n');
                }
                if (aguja != null && !aguja.isEmpty()
                        && (compacto(nombre).contains(aguja) || (valor != null && compacto(valor).contains(aguja)))) {
                    return true;
                }
                boolean filaConValor = Controles.esLaFilaEntera(nombre) && valor != null && !valor.isBlank();
                if (!filaConValor && quedan > 1 && pintarComoListado(h, b, quedan - 1, cuenta, tope, cortado, vioFila, aguja)) {
                    return true;
                }
            } finally {
                h.close();
            }
        }
        if (hayMas[0]) {
            cortado[0] = true;
        }
        return false;
    }

    /** La misma ventana que abre el volcado del listado: título «Clientes», no «Alta Cliente». */
    private static Pointer ventanaClientes() {
        Pointer h = new VentanasNativas(false).handleDe("Clientes");
        if (h != null && "clientes".equals(compacto(Win32.titulo(h)))) {
            return h;
        }
        return hwndPorTitulo("clientes");
    }

    private static boolean altaClienteVisible() {
        for (Pointer h : dialogosConocidos()) {
            if (h != null && "altacliente".equals(compacto(Win32.titulo(h))) && Win32.visible(h)) {
                return true;
            }
        }
        return false;
    }

    /** La línea del volcado que trae el NIF. La fila (con «;») gana a una celda suelta. */
    private static String lineaDelVolcado(String nif, String volcado) {
        if (volcado == null || !nifYaListado(nif, volcado)) {
            return null;
        }
        String mejor = null;
        for (String linea : volcado.split("\\R")) {
            if (!nifYaListado(nif, linea)) {
                continue;
            }
            String t = linea.strip();
            if (mejor == null || prefiereLinea(t, mejor)) {
                mejor = t;
            }
        }
        return mejor == null ? volcado.strip() : mejor;
    }

    private static boolean prefiereLinea(String nueva, String actual) {
        int pn = puntosLinea(nueva);
        int pa = puntosLinea(actual);
        if (pn != pa) {
            return pn > pa;
        }
        return nueva.length() > actual.length();
    }

    private static int puntosLinea(String t) {
        int p = 0;
        if (t.indexOf(';') >= 0) {
            p += 4;
        }
        if (t.indexOf(' ') >= 0) {
            p += 2;
        }
        if (t.length() > 12) {
            p += 1;
        }
        return p;
    }

    /** Relee el cuadro unos instantes: a veces el texto se pinta después del diálogo. */
    private static String esperarEtiqueta(Pointer dialogo, String esperado, String... etiquetas) {
        String fin = null;
        for (int i = 0; i < 6; i++) {
            fin = leerEtiqueta(dialogo, etiquetas);
            if (esperado.equals(fin)) {
                return fin;
            }
            if (i + 1 < 6) {
                dormir(200);
            }
        }
        return fin;
    }

    private static boolean nifEnHijos(Pointer lista, String aguja) {
        for (Pointer h : Win32.hijos(lista)) {
            String t = Win32.titulo(h);
            if (t != null && compacto(t).contains(aguja)) {
                return true;
            }
        }
        return false;
    }

    /** True si está, false si no, null si el árbol se ha cortado y no se puede afirmar. */
    private static Boolean walkNifVentana(Pointer hwnd, String aguja, boolean[] cortado) {
        Uia.Elemento raiz = Uia.deVentana(hwnd);
        if (raiz == null) {
            return Boolean.FALSE;
        }
        try {
            if (walkNif(raiz, aguja, 16, new int[] {0}, 8000, cortado, new int[] {0}, new int[] {-1})) {
                return Boolean.TRUE;
            }
        } finally {
            raiz.close();
        }
        return cortado[0] ? null : Boolean.FALSE;
    }

    /** La rejilla dice que hay más filas de las que se han podido leer. No se da el NIF por ausente. */
    private static boolean rejillaIncompleta(int filasVistas, int filasAnunciadas) {
        return filasAnunciadas > filasVistas + 5;
    }

    private static boolean walkNif(Uia.Elemento e, String aguja, int profundidad, int[] cuenta, int tope, boolean[] cortado,
            int[] filasVistas, int[] filasAnunciadas) {
        if (e == null || profundidad < 0) {
            return false;
        }
        if (cuenta[0]++ >= tope) {
            cortado[0] = true;
            return false;
        }
        String nombre = e.nombre();
        String valor = e.valor();
        if ((nombre != null && compacto(nombre).contains(aguja)) || (valor != null && compacto(valor).contains(aguja))) {
            return true;
        }
        String plano = compacto(nombre);
        if (plano.startsWith("fila") && plano.length() > 4 && Character.isDigit(plano.charAt(4))) {
            filasVistas[0]++;
        }
        if (profundidad >= 12) {
            int filas = e.filasDeRejilla();
            if (filas > filasAnunciadas[0]) {
                filasAnunciadas[0] = filas;
            }
        }
        if (profundidad == 0) {
            return false;
        }
        for (Uia.Elemento h : e.hijosHasta(1200, cortado)) {
            try {
                if (walkNif(h, aguja, profundidad - 1, cuenta, tope, cortado, filasVistas, filasAnunciadas)) {
                    return true;
                }
            } finally {
                h.close();
            }
        }
        return false;
    }

    private static Pointer hwndPorTitulo(String compactoTitulo) {
        java.util.Set<Long> mn = new java.util.HashSet<>();
        for (Pointer h : VentanasNativas.topesMn()) {
            mn.add(Pointer.nativeValue(h));
        }
        if (mn.isEmpty()) {
            return null;
        }
        Pointer mejor = null;
        for (Map.Entry<Long, String> e : VentanasNativas.instantanea().entrySet()) {
            if (!mn.contains(e.getKey()) || !compacto(e.getValue()).equals(compactoTitulo)) {
                continue;
            }
            Pointer h = new Pointer(e.getKey());
            if (!Win32.visible(h)) {
                continue;
            }
            mejor = h;
            if (Win32.User32.I.IsWindowEnabled(h)) {
                return h;
            }
        }
        return mejor;
    }

    /**
     * Cierra los diálogos de teléfono y de correo que estén abiertos.
     * Con conservar, se queda el número o la dirección. Sin conservar, Cancelar o Salir.
     * La lista de correos siempre sale con Salir. Nunca Baja.
     * Si {@code tambienFicha}, «Alta Cliente» se cierra con Cancelar (el NIF ya estaba).
     *
     * @return null si no queda ninguno de esos, o el motivo para parar
     */
    private static String cerrarPila(Lectura.Resultado lectura, boolean conservar, boolean tambienFicha, Consumer<String> decir) {
        List<String> vistos = new ArrayList<>();
        for (Pointer h : dialogosConocidos()) {
            String t = Win32.titulo(h);
            if (t != null && !vistos.contains(t)) {
                vistos.add(t);
            }
        }
        if (!vistos.isEmpty()) {
            decir.accept("Ventanas por título: " + String.join(" | ", vistos) + ".");
        }
        for (int vuelta = 0; vuelta < 5; vuelta++) {
            Pointer objetivo = dialogoACerrar(tambienFicha);
            if (objetivo == null) {
                return null;
            }
            if (!Win32.User32.I.IsWindowEnabled(objetivo)) {
                Pointer delante = Win32.User32.I.GetForegroundWindow();
                String tituloDelante = delante == null ? "" : Win32.titulo(delante);
                if (delante != null && ordenDeCierre(tituloDelante) < ordenDeCierre(Win32.titulo(objetivo))) {
                    objetivo = new Pointer(Pointer.nativeValue(delante));
                } else {
                    Cara encima = delante == null ? null : leerCara(delante);
                    if (encima != null && !esHijoDeTrabajo(encima.titulo())) {
                        return "hay un diálogo «" + encima.titulo() + "» encima. Texto: «" + encima.texto() + "». No se pulsa.";
                    }
                    return "«" + Win32.titulo(objetivo) + "» está tapado y no se puede cerrar.";
                }
            }
            Cara cara = leerCara(objetivo);
            boolean enFicha = datoYaEnFicha(lectura, cara.titulo());
            if (conservar) {
                asegurarDato(objetivo, cara.titulo(), datoEsperado(lectura, cara.titulo()));
            }
            String boton = botonConocido(cara.titulo(), conservar, enFicha);
            if (boton == null || noSePulsaEnLaPila(cara.titulo(), boton)) {
                return "el diálogo «" + cara.titulo() + "» no se cierra con un botón seguro.";
            }
            decir.accept("Diálogo de delante: «" + cara.titulo() + "». Se pulsa «" + boton + "».");
            if (!pulsarPorUia(objetivo, cara.titulo(), boton, decir)) {
                return "no se ha podido pulsar «" + boton + "» en «" + cara.titulo() + "».";
            }
            for (int i = 0; i < 15 && Win32.visible(objetivo); i++) {
                dormir(200);
            }
            if (Win32.visible(objetivo) && compacto(Win32.titulo(objetivo)).equals(compacto(cara.titulo()))
                    && "telefonomovil".equals(compacto(cara.titulo())) && enFicha && "guardar".equals(compacto(boton))) {
                if (pulsarPorUia(objetivo, cara.titulo(), "Cancelar", decir)) {
                    for (int j = 0; j < 15 && Win32.visible(objetivo); j++) {
                        dormir(200);
                    }
                }
            }
            if (Win32.visible(objetivo) && compacto(Win32.titulo(objetivo)).equals(compacto(cara.titulo()))) {
                return "«" + cara.titulo() + "» sigue abierto después de «" + boton + "».";
            }
            if (!conservar) {
                String no = rechazarGuardarSiPregunta(decir);
                if (no != null) {
                    return no;
                }
            }
        }
        return "siguen abiertos diálogos de teléfono o de correo. No se pulsa más.";
    }

    /**
     * El diálogo de delante. Si el que tiene el foco es el correo, la lista o el teléfono, ese.
     * Si no, el de menor orden que se vea (correo, lista, teléfono). «Alta Cliente» solo si ya no queda ninguno
     * de esos y se ha pedido cerrarla.
     */
    private static Pointer dialogoACerrar(boolean tambienFicha) {
        List<Pointer> contactos = new ArrayList<>();
        Pointer ficha = null;
        for (Pointer h : dialogosConocidos()) {
            String t = Win32.titulo(h);
            if ("altacliente".equals(compacto(t))) {
                if (ficha == null || !Win32.User32.I.IsWindowEnabled(ficha)) {
                    ficha = h;
                }
            } else {
                contactos.add(h);
            }
        }
        contactos.sort(Comparator.comparingInt(h -> ordenDeCierre(Win32.titulo(h))));
        Pointer fg = Win32.User32.I.GetForegroundWindow();
        if (fg != null) {
            long id = Pointer.nativeValue(fg);
            for (Pointer c : contactos) {
                if (Pointer.nativeValue(c) == id) {
                    return c;
                }
            }
        }
        for (Pointer c : contactos) {
            if (Win32.User32.I.IsWindowEnabled(c)) {
                return c;
            }
        }
        if (!contactos.isEmpty()) {
            return contactos.get(0);
        }
        if (!tambienFicha || ficha == null) {
            return null;
        }
        return ficha;
    }

    private static boolean datoYaEnFicha(Lectura.Resultado lectura, String tituloDialogo) {
        if (lectura == null) {
            return false;
        }
        Pointer ficha = hwndPorTitulo("altacliente");
        if (ficha == null) {
            return false;
        }
        String c = compacto(tituloDialogo);
        if ("telefonomovil".equals(c)) {
            return valorDe(lectura, "telefono").equals(leerEtiqueta(ficha, "movil", "telefono"));
        }
        if ("direccioncorreoelectronico".equals(c)) {
            return valorDe(lectura, "correo").equals(leerEtiqueta(ficha, "correoelectronico", "correo", "email"));
        }
        return true;
    }

    private static String datoEsperado(Lectura.Resultado lectura, String tituloDialogo) {
        if (lectura == null) {
            return "";
        }
        String c = compacto(tituloDialogo);
        if ("telefonomovil".equals(c)) {
            return valorDe(lectura, "telefono");
        }
        if ("direccioncorreoelectronico".equals(c)) {
            return valorDe(lectura, "correo");
        }
        return "";
    }

    /** Escribe el dato en el cuadro del diálogo si está vacío o es el mismo. No toca Tipo ni Estado. */
    private static void asegurarDato(Pointer dialogo, String titulo, String esperado) {
        if (dialogo == null || esperado == null || esperado.isBlank()) {
            return;
        }
        String c = compacto(titulo);
        Pointer lab = "telefonomovil".equals(c)
                ? etiquetaQueContiene(dialogo, "telefono", "movil")
                : etiquetaQueContiene(dialogo, "correoelectronico", "correo", "email");
        Pointer editor = lab == null ? null : editorDelGrupo(Win32.padre(lab));
        if (editor == null && "telefonomovil".equals(c)) {
            editor = unicoEdit(dialogo);
        }
        if (editor == null) {
            return;
        }
        String ahora = Win32.leerTexto(editor);
        if (esperado.equals(ahora)) {
            return;
        }
        if (ahora == null || ahora.isBlank()) {
            Win32.ponerTexto(editor, esperado, 1200);
        }
    }

    private static Pointer etiquetaQueContiene(Pointer dialogo, String... trozos) {
        for (Pointer h : Win32.hijos(dialogo)) {
            if (!Win32.visible(h) || !Win32.clase(h).toLowerCase(Locale.ROOT).contains("static")) {
                continue;
            }
            String t = compacto(Win32.titulo(h));
            for (String q : trozos) {
                if (t.contains(q)) {
                    return h;
                }
            }
        }
        return null;
    }

    private static Pointer unicoEdit(Pointer dialogo) {
        Pointer uno = null;
        int n = 0;
        for (Pointer h : Win32.hijos(dialogo)) {
            if (!Win32.visible(h)) {
                continue;
            }
            if (!Win32.clase(h).toLowerCase(Locale.ROOT).contains("edit")) {
                continue;
            }
            uno = h;
            n++;
        }
        return n == 1 ? uno : null;
    }

    private static String dejarContacto(Pointer ficha, Lectura.Resultado lectura, Consumer<String> decir, String rotulo,
            String esperado, boolean listaCorreo, String... etiquetas) {
        if (!esperado.equals(leerEtiqueta(ficha, etiquetas))) {
            escribirEtiqueta(ficha, esperado, false, etiquetas);
        }
        String fallo = cerrarPila(lectura, true, false, decir);
        if (fallo != null) {
            return fallo;
        }
        if (listaCorreo) {
            fallo = cerrarPila(lectura, true, false, decir);
            if (fallo != null) {
                return fallo;
            }
        }
        String fin = esperarEtiqueta(ficha, esperado, etiquetas);
        if (!esperado.equals(fin)) {
            Pointer lab = etiqueta(ficha, etiquetas);
            Pointer editor = lab == null ? null : editorDelGrupo(Win32.padre(lab));
            if (editor != null) {
                decir.accept(rotulo + " sigue vacío. Se pulsa el cuadro para abrir su diálogo.");
                Win32.clicEn(editor, 1500);
                dormir(400);
                fallo = cerrarPila(lectura, true, false, decir);
                if (fallo != null) {
                    return fallo;
                }
                if (listaCorreo) {
                    fallo = cerrarPila(lectura, true, false, decir);
                    if (fallo != null) {
                        return fallo;
                    }
                }
                fin = esperarEtiqueta(ficha, esperado, etiquetas);
            }
        }
        if (!esperado.equals(fin)) {
            return "no se ha podido dejar " + rotulo + " en «" + esperado + "»"
                    + (fin == null ? " (el campo no se ha podido leer)." : " (en pantalla ha quedado «" + fin + "»).")
                    + " No se guarda.";
        }
        decir.accept(rotulo + ": «" + fin + "».");
        return null;
    }

    /** Si al cancelar pregunta si se guarda, la respuesta es No. No crea otro cliente. */
    private static String rechazarGuardarSiPregunta(Consumer<String> decir) {
        dormir(250);
        Pointer fg = Win32.User32.I.GetForegroundWindow();
        if (fg == null || !Win32.visible(fg)) {
            return null;
        }
        String titulo = Win32.titulo(fg);
        if (titulo == null || esHijoDeTrabajo(titulo) || sinTildes(titulo).contains("mnprogram")) {
            return null;
        }
        Cara c = leerCara(fg);
        String todo = sinTildes(c.titulo() + "\n" + c.texto());
        boolean pregunta = todo.contains("desea guardar") || todo.contains("guardar los cambios") || todo.contains("guardar los datos");
        if (!pregunta) {
            return null;
        }
        String no = elegir(c.botones(), "no");
        if (no == null || esBotonPeligroso(no)) {
            return "pregunta si se guarda y no hay un «No». Texto: «" + c.texto() + "».";
        }
        if (!pulsarCara(c, no, decir)) {
            return "pregunta si se guarda y no se ha podido pulsar «No». Texto: «" + c.texto() + "».";
        }
        return null;
    }
}
