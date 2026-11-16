package es.amagna.asistente.manos;

import com.sun.jna.Pointer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Un solo pase para cerrar la pila de Marta y pulsar Guardar de Alta Cliente.
 * El botón se localiza por el nombre UIA y su BoundingRectangle, y el clic es
 * {@link ManosRobot#clicEnPantalla} (ya divide por la escala de Windows).
 * No se usa GetWindowText para encontrar el botón, ni Invoke.
 */
public final class ClicGuardarMarta {
    private static final String NIF = "48291736Q";
    private static final String PROHIBIDO = "00000000T";

    private static ManosRobot manos;
    private static final List<String> clics = new ArrayList<>();
    private static boolean parar;
    private static boolean yaConfirme;

    public static void main(String[] args) {
        List<Ventana> ahora = listar();
        System.out.println("=== Ventanas visibles ===");
        for (Ventana v : ahora) {
            System.out.println("  · «" + v.titulo + "» clase " + v.clase + " pid " + v.pid
                    + (v.iconica ? " [minimizada]" : ""));
        }
        System.out.flush();

        List<String> marcos = titulosMn();
        System.out.println("=== Marcos MN ===");
        if (marcos.isEmpty()) {
            System.out.println("  (ninguno)");
        }
        for (String t : marcos) {
            System.out.println("  · «" + t + "»");
        }
        boolean prueba = false;
        boolean otraEmpresa = false;
        for (String t : marcos) {
            String p = plano(t);
            if (p.contains("empresa de prueba")) {
                prueba = true;
            } else if (p.contains("mnprogram") && (p.contains(" - ") || p.contains(" – "))) {
                otraEmpresa = true;
            }
        }
        for (Ventana v : ahora) {
            if (plano(v.titulo).contains("empresa de prueba")) {
                prueba = true;
            }
        }
        if (!prueba || otraEmpresa) {
            System.out.println("PARADO: la sesión no es solo Empresa de prueba"
                    + (otraEmpresa ? " (hay otro título de empresa)." : " (no veo «Empresa de prueba»).")
                    + " No se pulsa nada.");
            return;
        }
        System.out.println("Sesión: Empresa de prueba.");
        System.out.flush();

        if (buscar(ahora, ClicGuardarMarta::esAlta) != null) {
            String nif = leerIdentidad(buscar(listar(), ClicGuardarMarta::esAlta));
            System.out.println(nif);
            System.out.flush();
            if (nif.startsWith("PROHIBIDO") || nif.startsWith("OTRO")) {
                System.out.println("PARADO: " + nif + " No se pulsa nada.");
                return;
            }
        }

        if (!dialogo("Dirección Correo Electrónico", ClicGuardarMarta::esEdicionCorreo, "Guardar", true)) {
            return;
        }
        if (!dialogo("Direcciones de Correo Electrónico", ClicGuardarMarta::esListaCorreo, "Salir", false)) {
            return;
        }
        if (!dialogo("Teléfono Móvil", ClicGuardarMarta::esTelefono, "Guardar", true)) {
            return;
        }

        List<Ventana> tras = listar();
        Ventana correo = buscar(tras, ClicGuardarMarta::esEdicionCorreo);
        Ventana lista = buscar(tras, ClicGuardarMarta::esListaCorreo);
        Ventana tel = buscar(tras, ClicGuardarMarta::esTelefono);
        Ventana alta = buscar(tras, ClicGuardarMarta::esAlta);
        if (correo != null || lista != null || tel != null) {
            System.out.println("PARADO: siguen abiertos los diálogos de encima. No se pulsa el Guardar de Alta Cliente.");
            informar(false);
            return;
        }
        if (alta != null) {
            String nif = leerIdentidad(alta);
            System.out.println("Antes de Guardar de Alta Cliente: " + nif);
            System.out.flush();
            if (nif.startsWith("PROHIBIDO") || nif.startsWith("OTRO") || nif.startsWith("SIN_DATOS")) {
                System.out.println("PARADO: no confirmo que la ficha sea la de Marta. No se pulsa Guardar.");
                informar(false);
                return;
            }
            if (!pulsar(alta, "Guardar", true)) {
                informar(false);
                return;
            }
            if (!avisosNuevos(titulosDe(tras))) {
                informar(false);
                return;
            }
        } else {
            System.out.println("Alta Cliente no está abierta.");
        }

        boolean altaFuera = buscar(listar(), ClicGuardarMarta::esAlta) == null;
        boolean enRejilla = nifEnClientes();
        if (altaFuera && enRejilla) {
            System.out.println("GUARDADO");
        } else {
            System.out.println("No se da por guardado.");
        }
        informar(enRejilla);
    }

    /** @return false si hay que cortar el pase */
    private static boolean dialogo(String como, java.util.function.Predicate<String> encaja, String boton, boolean izquierda) {
        if (parar) {
            return false;
        }
        Ventana v = buscar(listar(), encaja);
        if (v == null) {
            System.out.println("No está abierto «" + como + "».");
            System.out.flush();
            return true;
        }
        System.out.println("Abierto «" + v.titulo + "». Busco «" + boton + "» por nombre UIA.");
        System.out.flush();
        Set<String> antes = titulosDe(listar());
        if (!pulsar(v, boton, izquierda)) {
            return false;
        }
        return avisosNuevos(antes);
    }

    /** @return false si no se pudo pulsar o UIA está vacío */
    private static boolean pulsar(Ventana v, String boton, boolean izquierda) {
        Arbol arbol = leerArbol(v);
        if (arbol == null || arbol.nodos == 0) {
            System.out.println("UIA vacío en «" + v.titulo + "». Reinicio una vez.");
            System.out.flush();
            Uia.reiniciar();
            dormir(500);
            v = refrescar(v);
            if (v == null) {
                System.out.println("«" + boton + "»: la ventana se cerró al reiniciar UIA.");
                return true;
            }
            arbol = leerArbol(v);
        }
        if (arbol == null || arbol.nodos == 0) {
            System.out.println("UIA_VACIO\t" + v.titulo + "\t" + boton);
            parar = true;
            return false;
        }
        System.out.println("UIA de «" + v.titulo + "»: " + arbol.nodos + " nodos. Ventana UIA "
                + rect(arbol.ventana) + ".");
        List<Pieza> candidatos = candidatos(arbol.piezas, boton);
        if (candidatos.isEmpty()) {
            System.out.println("No hay ningún control UIA llamado «" + boton + "». Botones vistos:");
            int n = 0;
            for (Pieza p : arbol.piezas) {
                if (!esClicable(p) || p.nombre.isBlank()) {
                    continue;
                }
                System.out.println("  - " + p.tipo + " «" + p.nombre + "» " + rect(p));
                if (++n >= 25) {
                    break;
                }
            }
            System.out.println("PARADO: no pulso otro botón.");
            parar = true;
            return false;
        }
        for (Pieza p : candidatos) {
            System.out.println("  candidato " + p.tipo + " «" + p.nombre + "» " + rect(p));
        }
        Pieza elegido = elegir(candidatos, izquierda, arbol.ventana);
        if (elegido == null || plano(elegido.nombre).equals("baja") || !plano(elegido.nombre).equals(plano(boton))) {
            System.out.println("PARADO: el candidato no es «" + boton + "». No se pulsa.");
            parar = true;
            return false;
        }
        int cx = elegido.x + elegido.w / 2;
        int cy = elegido.y + elegido.h / 2;
        ManosRobot robot = robot();
        double escala = robot.escalaWindows();
        String linea = "CLIC «" + elegido.nombre + "» de «" + v.titulo + "» rectángulo UIA x=" + elegido.x
                + " y=" + elegido.y + " ancho=" + elegido.w + " alto=" + elegido.h
                + " centro=" + cx + "," + cy + " escala=" + escala
                + " (clicEnPantalla divide px/escala).";
        System.out.println(linea);
        System.out.flush();
        try {
            robot.clicEnPantalla(cx, cy);
        } catch (FrenoDeEmergencia e) {
            System.out.println("PARADO: " + e.getMessage());
            parar = true;
            return false;
        }
        clics.add(linea);
        dormir(1200);
        return true;
    }

    /** Lee el aviso nuevo. false = hay que parar (error, duda, o confirmación ya usada). */
    private static boolean avisosNuevos(Set<String> antes) {
        dormir(400);
        List<Ventana> ahora = listar();
        List<Ventana> nuevos = new ArrayList<>();
        for (Ventana v : ahora) {
            if (antes.contains(v.titulo)) {
                continue;
            }
            if (esEdicionCorreo(v.titulo) || esListaCorreo(v.titulo) || esTelefono(v.titulo)
                    || esAlta(v.titulo) || esClientes(v.titulo)) {
                continue;
            }
            if (plano(v.titulo).contains("mnprogram")) {
                continue;
            }
            if (!esDeMn(v.pid)) {
                continue;
            }
            nuevos.add(v);
        }
        if (nuevos.isEmpty()) {
            return true;
        }
        for (Ventana v : nuevos) {
            String texto = textoVentana(v);
            System.out.println("Aviso nuevo «" + v.titulo + "»: " + texto);
            System.out.flush();
            String clase = clasificar(v.titulo + " " + texto);
            if ("error".equals(clase) || "duda".equals(clase)) {
                System.out.println("PARADO: el aviso no es una confirmación. No se pulsa Aceptar.");
                parar = true;
                return false;
            }
            if ("exito".equals(clase)) {
                System.out.println("El aviso parece de éxito. No se pulsa; sigo a mirar la rejilla.");
                return true;
            }
            Pieza ok = botonConfirmacion(v);
            if (ok == null) {
                System.out.println("PARADO: confirmación sin botón Aceptar ni Sí.");
                parar = true;
                return false;
            }
            if (yaConfirme) {
                System.out.println("PARADO: ya se pulsó una confirmación. No se pulsa otra.");
                parar = true;
                return false;
            }
            yaConfirme = true;
            Set<String> base = titulosDe(listar());
            System.out.println("Confirmación. Pulso «" + ok.nombre + "» una vez.");
            if (!pulsar(v, ok.nombre, true)) {
                return false;
            }
            base.add(v.titulo);
            if (!avisosNuevos(base)) {
                return false;
            }
        }
        return true;
    }

    private static Pieza botonConfirmacion(Ventana v) {
        Arbol arbol = leerArbol(v);
        if (arbol == null || arbol.nodos == 0) {
            Uia.reiniciar();
            dormir(400);
            arbol = leerArbol(v);
        }
        if (arbol == null) {
            return null;
        }
        Pieza si = elegir(candidatos(arbol.piezas, "Sí"), true, arbol.ventana);
        if (si != null) {
            return si;
        }
        return elegir(candidatos(arbol.piezas, "Aceptar"), true, arbol.ventana);
    }

    private static String clasificar(String texto) {
        String p = plano(texto);
        boolean error = p.contains("error") || p.contains("no se puede") || p.contains("obligatori")
                || p.contains("incorrect") || p.contains("no valido") || p.contains("ya existe")
                || p.contains("duplic") || p.contains("debe indicar") || p.contains("no se ha podido")
                || p.contains("excepcion") || p.contains("no es correcto");
        boolean exito = p.contains("se ha guardado") || p.contains("guardado correctamente")
                || p.contains("se ha creado") || p.contains("alta realizada") || p.contains("correctamente");
        boolean pregunta = texto.contains("¿") || texto.contains("?") || p.contains("desea")
                || p.contains("esta seguro") || p.contains("confirma");
        if (error && !exito) {
            return "error";
        }
        if (pregunta && !error) {
            return "confirm";
        }
        if (exito) {
            return "exito";
        }
        return "duda";
    }

    private static String leerIdentidad(Ventana alta) {
        if (alta == null) {
            return "SIN_DATOS (no hay Alta Cliente)";
        }
        Arbol arbol = leerArbol(alta);
        if (arbol == null || arbol.nodos == 0) {
            Uia.reiniciar();
            dormir(400);
            alta = refrescar(alta);
            arbol = alta == null ? null : leerArbol(alta);
        }
        if (arbol == null || arbol.nodos == 0) {
            return "SIN_DATOS (UIA de Alta Cliente vacío)";
        }
        boolean marta = false;
        boolean cero = false;
        String otro = null;
        StringBuilder campos = new StringBuilder();
        int mostrados = 0;
        for (Pieza p : arbol.piezas) {
            String bolsa = (p.nombre == null ? "" : p.nombre) + " " + (p.valor == null ? "" : p.valor);
            String u = bolsa.toUpperCase(Locale.ROOT);
            if (u.contains(PROHIBIDO)) {
                cero = true;
            }
            if (u.contains(NIF) || u.contains("MARTA LOPEZ")) {
                marta = true;
            }
            String nif = nifEn(p.valor);
            if (nif == null) {
                nif = nifEn(p.nombre);
            }
            if (nif != null && !nif.equalsIgnoreCase(NIF) && !nif.equalsIgnoreCase(PROHIBIDO)) {
                otro = nif;
            }
            if (p.valor != null && !p.valor.isBlank() && mostrados < 20) {
                campos.append(" [").append(p.tipo).append(" «").append(recortar(p.nombre)).append("»=")
                        .append(recortar(p.valor)).append("]");
                mostrados++;
            }
        }
        if (cero) {
            return "PROHIBIDO: la ficha contiene " + PROHIBIDO + "." + campos;
        }
        if (otro != null && !marta) {
            return "OTRO: NIF «" + otro + "», no es Marta." + campos;
        }
        if (!marta) {
            return "SIN_DATOS: no veo " + NIF + " ni MARTA LOPEZ en la ficha." + campos;
        }
        return "Ficha de Marta (" + NIF + ")." + campos;
    }

    private static boolean nifEnClientes() {
        Ventana clientes = buscar(listar(), ClicGuardarMarta::esClientes);
        if (clientes == null) {
            System.out.println("No veo la ventana Clientes.");
            return false;
        }
        Arbol arbol = leerArbol(clientes);
        if (arbol == null || arbol.nodos == 0) {
            Uia.reiniciar();
            dormir(400);
            clientes = buscar(listar(), ClicGuardarMarta::esClientes);
            arbol = clientes == null ? null : leerArbol(clientes);
        }
        if (arbol == null) {
            System.out.println("No se ha podido leer la rejilla de Clientes.");
            return false;
        }
        boolean nif = false;
        boolean nombre = false;
        for (Pieza p : arbol.piezas) {
            String bolsa = ((p.nombre == null ? "" : p.nombre) + " " + (p.valor == null ? "" : p.valor)).toUpperCase(Locale.ROOT);
            if (bolsa.contains(NIF)) {
                nif = true;
            }
            if (bolsa.contains("MARTA LOPEZ")) {
                nombre = true;
            }
        }
        System.out.println("Rejilla Clientes: " + (nif ? NIF + " está." : NIF + " no está en el árbol UIA.")
                + (nombre ? " El nombre MARTA LOPEZ sí aparece." : " El nombre MARTA LOPEZ no aparece."));
        return nif;
    }

    private static void informar(boolean enRejilla) {
        System.out.println("=== Clics ===");
        if (clics.isEmpty()) {
            System.out.println("  (ninguno)");
        }
        for (String c : clics) {
            System.out.println("  " + c);
        }
        System.out.println("Marta en la rejilla: " + (enRejilla ? "sí, " + NIF : "no"));
        System.out.println("=== Ventanas que quedan ===");
        for (Ventana v : listar()) {
            if (esDeMn(v.pid) || plano(v.titulo).contains("mnprogram") || esAlta(v.titulo) || esClientes(v.titulo)) {
                System.out.println("  · «" + v.titulo + "»");
            }
        }
        System.out.flush();
    }

    private static Arbol leerArbol(Ventana v) {
        if (v == null) {
            return null;
        }
        try {
            return Uia.conTope(12, () -> caminar(v.hwnd, true), null);
        } catch (RuntimeException e) {
            System.out.println("UIA falló en «" + v.titulo + "»: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private static String textoVentana(Ventana v) {
        Arbol arbol = leerArbol(v);
        if (arbol == null) {
            return "(sin árbol UIA)";
        }
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Pieza p : arbol.piezas) {
            if (p.nombre != null && !p.nombre.isBlank()) {
                b.append(" «").append(recortar(p.nombre)).append("»");
                n++;
            }
            if (p.valor != null && !p.valor.isBlank()) {
                b.append("=").append(recortar(p.valor));
            }
            if (n >= 30) {
                break;
            }
        }
        return b.toString().strip();
    }

    private static Arbol caminar(Pointer hwnd, boolean conValor) {
        Uia.Elemento raiz = Uia.deVentana(hwnd);
        if (raiz == null) {
            return new Arbol(0, null, List.of());
        }
        try {
            int[] rr = raiz.recuadro();
            int[] ventana = rr != null && rr[2] > 0 && rr[3] > 0 ? rr : null;
            List<Pieza> piezas = new ArrayList<>();
            int[] nodos = {0};
            int[] valores = {0};
            bajar(raiz, 0, piezas, nodos, valores, conValor);
            return new Arbol(nodos[0], ventana, piezas);
        } finally {
            raiz.close();
        }
    }

    private static void bajar(Uia.Elemento e, int profundidad, List<Pieza> piezas, int[] nodos, int[] valores, boolean conValor) {
        if (profundidad > 12 || nodos[0] > 1200) {
            return;
        }
        for (Uia.Elemento h : e.hijosHasta(200, null)) {
            try {
                if (nodos[0] > 1200) {
                    return;
                }
                nodos[0]++;
                Pieza p = new Pieza();
                p.nombre = h.nombre() == null ? "" : h.nombre();
                p.tipo = h.tipo() == null ? "" : h.tipo();
                int[] r = h.recuadro();
                if (r != null && r[2] > 0 && r[3] > 0) {
                    p.tieneRect = true;
                    p.x = r[0];
                    p.y = r[1];
                    p.w = r[2];
                    p.h = r[3];
                }
                if (conValor && valores[0] < 80 && ("campo".equals(p.tipo) || "documento".equals(p.tipo) || "texto".equals(p.tipo)
                        || "fila".equals(p.tipo))) {
                    try {
                        p.valor = h.valor();
                        valores[0]++;
                    } catch (RuntimeException ex) {
                        p.valor = null;
                    }
                }
                piezas.add(p);
                bajar(h, profundidad + 1, piezas, nodos, valores, conValor);
            } finally {
                h.close();
            }
        }
    }

    private static List<Pieza> candidatos(List<Pieza> piezas, String nombre) {
        String quiero = plano(nombre);
        List<Pieza> c = new ArrayList<>();
        for (Pieza p : piezas) {
            if (!plano(p.nombre).equals(quiero)) {
                continue;
            }
            if (plano(p.nombre).equals("baja")) {
                continue;
            }
            if (!p.tieneRect || p.w < 20 || p.h < 12 || p.x < -20 || p.y < -20) {
                continue;
            }
            c.add(p);
        }
        return c;
    }

    private static Pieza elegir(List<Pieza> candidatos, boolean izquierdaAbajo, int[] ventana) {
        if (candidatos.isEmpty()) {
            return null;
        }
        int mejor = -1;
        for (Pieza p : candidatos) {
            mejor = Math.max(mejor, rango(p));
        }
        List<Pieza> top = new ArrayList<>();
        for (Pieza p : candidatos) {
            if (rango(p) == mejor) {
                top.add(p);
            }
        }
        List<Pieza> uso = top;
        if (izquierdaAbajo && ventana != null && ventana.length == 4 && ventana[3] > 80) {
            int corte = ventana[1] + ventana[3] * 50 / 100;
            List<Pieza> abajo = new ArrayList<>();
            for (Pieza p : top) {
                if (p.y + p.h / 2 >= corte) {
                    abajo.add(p);
                }
            }
            if (!abajo.isEmpty()) {
                uso = abajo;
            }
        }
        Pieza gana = uso.get(0);
        for (Pieza p : uso) {
            if (izquierdaAbajo) {
                if (p.x < gana.x || (p.x == gana.x && p.w * p.h > gana.w * gana.h)) {
                    gana = p;
                }
            } else {
                int py = p.y + p.h;
                int gy = gana.y + gana.h;
                if (py > gy || (py == gy && p.x > gana.x)) {
                    gana = p;
                }
            }
        }
        return gana;
    }

    private static int rango(Pieza p) {
        return switch (p.tipo) {
            case "boton", "boton-con-flecha" -> 4;
            case "panel" -> 3;
            case "otro", "grupo" -> 2;
            case "texto" -> 1;
            default -> 1;
        };
    }

    private static boolean esClicable(Pieza p) {
        return "boton".equals(p.tipo) || "panel".equals(p.tipo) || "boton-con-flecha".equals(p.tipo)
                || "otro".equals(p.tipo);
    }

    private static List<Ventana> listar() {
        List<Ventana> r = new ArrayList<>();
        Win32.User32.I.EnumWindows((hwnd, datos) -> {
            if (hwnd == null || !Win32.User32.I.IsWindowVisible(hwnd)) {
                return true;
            }
            Pointer copia = new Pointer(Pointer.nativeValue(hwnd));
            if (VentanasNativas.esConsola(copia)) {
                return true;
            }
            String t = Win32.titulo(copia);
            if (t == null || t.isBlank()) {
                return true;
            }
            Ventana v = new Ventana();
            v.hwnd = copia;
            v.titulo = t;
            v.clase = Win32.clase(copia);
            v.pid = Win32.pid(copia);
            v.iconica = Win32.User32.I.IsIconic(copia);
            r.add(v);
            return true;
        }, null);
        return r;
    }

    private static List<String> titulosMn() {
        List<String> r = new ArrayList<>();
        Set<String> vistos = new LinkedHashSet<>();
        for (Pointer h : VentanasNativas.topesMn()) {
            Pointer copia = new Pointer(Pointer.nativeValue(h));
            String t = Win32.titulo(copia);
            if (t != null && !t.isBlank() && vistos.add(t)) {
                r.add(t);
            }
            String dueno = VentanasNativas.tituloDueno(Pointer.nativeValue(copia));
            if (dueno != null && vistos.add(dueno)) {
                r.add(dueno);
            }
        }
        return r;
    }

    private static boolean esDeMn(long pid) {
        for (Pointer h : VentanasNativas.topesMn()) {
            if (Win32.pid(new Pointer(Pointer.nativeValue(h))) == pid) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> titulosDe(List<Ventana> vs) {
        Set<String> s = new LinkedHashSet<>();
        for (Ventana v : vs) {
            s.add(v.titulo);
        }
        return s;
    }

    private static Ventana buscar(List<Ventana> vs, java.util.function.Predicate<String> encaja) {
        Ventana mejor = null;
        int mejorArea = Integer.MAX_VALUE;
        for (Ventana v : vs) {
            if (v.iconica || !encaja.test(v.titulo)) {
                continue;
            }
            int[] rc = Win32.rectangulo(v.hwnd);
            int area = rc == null ? Integer.MAX_VALUE - 1 : rc[2] * rc[3];
            if (mejor == null || area < mejorArea) {
                mejor = v;
                mejorArea = area;
            }
        }
        return mejor;
    }

    private static Ventana refrescar(Ventana vieja) {
        String titulo = vieja.titulo;
        return buscar(listar(), t -> t.equals(titulo));
    }

    private static boolean esEdicionCorreo(String t) {
        String p = plano(t);
        if (p.contains("direcciones")) {
            return false;
        }
        return p.equals("direccion correo electronico") || p.equals("direccion de correo electronico")
                || p.startsWith("direccion correo electronico") || p.startsWith("direccion de correo electronico");
    }

    private static boolean esListaCorreo(String t) {
        String p = plano(t);
        return p.contains("direcciones") && p.contains("correo");
    }

    private static boolean esTelefono(String t) {
        String p = plano(t);
        return p.contains("telefono") && p.contains("movil");
    }

    private static boolean esAlta(String t) {
        String p = plano(t);
        return p.equals("alta cliente") || p.startsWith("alta cliente ");
    }

    private static boolean esClientes(String t) {
        String p = plano(t);
        return p.equals("clientes") || p.startsWith("clientes ");
    }

    private static String nifEn(String s) {
        if (s == null) {
            return null;
        }
        String u = s.toUpperCase(Locale.ROOT).replace(" ", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{8}[A-Z]").matcher(u);
        return m.find() ? m.group() : null;
    }

    private static String plano(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace("&", "").toLowerCase(Locale.ROOT).trim();
    }

    private static String rect(Pieza p) {
        return p.tieneRect ? "x=" + p.x + " y=" + p.y + " ancho=" + p.w + " alto=" + p.h : "(sin rectángulo)";
    }

    private static String rect(int[] r) {
        return r == null ? "(sin rectángulo)" : "x=" + r[0] + " y=" + r[1] + " ancho=" + r[2] + " alto=" + r[3];
    }

    private static String recortar(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ').strip();
        return t.length() > 80 ? t.substring(0, 80) + "…" : t;
    }

    private static ManosRobot robot() {
        if (manos == null) {
            manos = new ManosRobot(false);
        }
        return manos;
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Ventana {
        Pointer hwnd;
        String titulo;
        String clase;
        long pid;
        boolean iconica;
    }

    private static final class Pieza {
        String nombre = "";
        String tipo = "";
        String valor;
        boolean tieneRect;
        int x, y, w, h;
    }

    private record Arbol(int nodos, int[] ventana, List<Pieza> piezas) {
    }
}
