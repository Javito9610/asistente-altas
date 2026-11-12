package es.amagna.asistente.manos;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.ArrayList;
import java.util.List;

/**
 * UI Automation: la forma en que Windows deja «leer por dentro» cualquier programa. Es lo que usan los lectores de
 * pantalla para ciegos y las herramientas profesionales de automatización, y es parte de Windows desde hace veinte años.
 *
 * Con esto el asistente no adivina mirando una foto: pregunta al programa qué controles tiene, cómo se llama cada uno,
 * qué vale y si está activo; escribe en un campo concreto y pulsa un botón por su nombre, sin coordenadas. Si MN lo
 * permite (los programas de gestión de Windows suelen hacerlo), las altas se rellenan campo a campo, no a ciegas.
 *
 * Por dentro son llamadas directas a UIAutomationCore de Windows (COM), sin programas intermedios. Cada interfaz de COM
 * es una tabla de funciones: se llama por su posición, que es fija y pública desde Windows 7.
 *
 * @package Amagna\Altas
 */
public final class Uia {

    // ---------- Windows ----------

    public interface Ole32 extends StdCallLibrary {
        Ole32 I = Native.load("ole32", Ole32.class);

        int CoInitializeEx(Pointer reservado, int modo);

        int CoCreateInstance(Guid.ByReference clsid, Pointer exterior, int contexto, Guid.ByReference iid, PointerByReference destino);
    }

    public interface OleAut32 extends StdCallLibrary {
        OleAut32 I = Native.load("oleaut32", OleAut32.class);

        Pointer SysAllocString(WString texto);

        void SysFreeString(Pointer cadena);
    }

    @Structure.FieldOrder({"d1", "d2", "d3", "d4"})
    public static class Guid extends Structure {
        public int d1;
        public short d2;
        public short d3;
        public byte[] d4 = new byte[8];

        public static class ByReference extends Guid implements Structure.ByReference {
        }

        /** «FF48DBA4-60EF-4201-AA87-54103EEF594E» → la estructura que espera Windows. */
        public static ByReference de(String s) {
            String h = s.replace("-", "");
            ByReference g = new ByReference();
            g.d1 = (int) Long.parseLong(h.substring(0, 8), 16);
            g.d2 = (short) Integer.parseInt(h.substring(8, 12), 16);
            g.d3 = (short) Integer.parseInt(h.substring(12, 16), 16);
            for (int i = 0; i < 8; i++) {
                g.d4[i] = (byte) Integer.parseInt(h.substring(16 + i * 2, 18 + i * 2), 16);
            }
            g.write();
            return g;
        }
    }

    @Structure.FieldOrder({"izq", "arriba", "der", "abajo"})
    public static class Rect extends Structure {
        public int izq;
        public int arriba;
        public int der;
        public int abajo;

        public static class ByReference extends Rect implements Structure.ByReference {
        }
    }

    private static final String CLSID_AUTOMATION = "FF48DBA4-60EF-4201-AA87-54103EEF594E";
    private static final String IID_IUIAUTOMATION = "30CBE57D-D9D0-452A-AB13-7AC5AC4825EE";
    private static final int CLSCTX_INPROC_SERVER = 1;
    private static final int COINIT_MULTITHREADED = 0;
    private static final int RPC_E_CHANGED_MODE = 0x80010106;

    /** Trozos de programa con los que se puede hablar (patrones de UI Automation). */
    private static final int PATRON_INVOCAR = 10000;
    private static final int PATRON_VALOR = 10002;
    private static final int PATRON_DESPLEGAR = 10005;
    private static final int PATRON_ELEGIR = 10010;
    private static final int PATRON_TEXTO = 10014;
    private static final int PATRON_MARCAR = 10015;
    private static final int PATRON_CLASICO = 10018; // para los controles de toda la vida

    private static final int HIJOS = 2; // TreeScope_Children

    /** Llamada a una función de COM por su posición en la tabla. Devuelve el código de Windows (0 = bien). */
    private static int llamar(Pointer objeto, int posicion, Object... argumentos) {
        Pointer tabla = objeto.getPointer(0);
        Pointer funcion = tabla.getPointer((long) posicion * Native.POINTER_SIZE);
        Object[] todos = new Object[argumentos.length + 1];
        todos[0] = objeto;
        System.arraycopy(argumentos, 0, todos, 1, argumentos.length);
        int convenio = Native.POINTER_SIZE == 4 ? Function.ALT_CONVENTION : Function.C_CONVENTION;
        return Function.getFunction(funcion, convenio).invokeInt(todos);
    }

    private static void soltar(Pointer objeto) {
        if (objeto != null) {
            llamar(objeto, 2); // IUnknown::Release
        }
    }

    /** Lee una cadena de Windows (BSTR) y libera su memoria. */
    private static String cadena(PointerByReference r) {
        Pointer p = r.getValue();
        if (p == null) {
            return "";
        }
        String s = p.getWideString(0);
        OleAut32.I.SysFreeString(p);
        return s;
    }

    // ---------- arranque ----------

    private static Pointer automation;
    private static String motivoNoDisponible;

    /** Enciende UI Automation una sola vez. Devuelve false (y el motivo en noDisponible()) si este Windows no deja. */
    public static synchronized boolean disponible() {
        if (automation != null) {
            return true;
        }
        if (motivoNoDisponible != null) {
            return false;
        }
        try {
            int ini = Ole32.I.CoInitializeEx(null, COINIT_MULTITHREADED);
            if (ini != 0 && ini != 1 && ini != RPC_E_CHANGED_MODE) { // S_OK, S_FALSE o «ya estaba en otro modo»
                motivoNoDisponible = "no se puede arrancar COM (" + Integer.toHexString(ini) + ")";
                return false;
            }
            PointerByReference r = new PointerByReference();
            int hr = Ole32.I.CoCreateInstance(Guid.de(CLSID_AUTOMATION), null, CLSCTX_INPROC_SERVER, Guid.de(IID_IUIAUTOMATION), r);
            if (hr != 0 || r.getValue() == null) {
                motivoNoDisponible = "este Windows no ofrece UI Automation (" + Integer.toHexString(hr) + ")";
                return false;
            }
            automation = r.getValue();
            return true;
        } catch (Throwable e) { // UnsatisfiedLinkError incluido: en un Windows raro, se sigue sin esto
            motivoNoDisponible = e.getClass().getSimpleName() + ": " + e.getMessage();
            return false;
        }
    }

    public static String noDisponible() {
        return motivoNoDisponible == null ? "" : motivoNoDisponible;
    }

    /** El árbol de controles de una ventana, o null si no se puede. */
    public static Elemento deVentana(Pointer hwnd) {
        if (!disponible()) {
            return null;
        }
        PointerByReference r = new PointerByReference();
        return llamar(automation, 6, hwnd, r) == 0 && r.getValue() != null ? new Elemento(r.getValue()) : null; // ElementFromHandle
    }

    /** El control que tiene el foco del teclado, o null. */
    public static Elemento conFoco() {
        if (!disponible()) {
            return null;
        }
        PointerByReference r = new PointerByReference();
        return llamar(automation, 8, r) == 0 && r.getValue() != null ? new Elemento(r.getValue()) : null; // GetFocusedElement
    }

    private static Pointer condicionTodo() {
        PointerByReference r = new PointerByReference();
        return llamar(automation, 21, r) == 0 ? r.getValue() : null; // CreateTrueCondition
    }

    private Uia() {
    }

    // ---------- un control ----------

    /** Un control de un programa: su nombre, su tipo, lo que vale y lo que se puede hacer con él. */
    public static final class Elemento implements AutoCloseable {
        private final Pointer p;

        Elemento(Pointer p) {
            this.p = p;
        }

        private String texto(int posicion) {
            PointerByReference r = new PointerByReference();
            return llamar(p, posicion, r) == 0 ? cadena(r) : "";
        }

        private int entero(int posicion) {
            IntByReference r = new IntByReference();
            return llamar(p, posicion, r) == 0 ? r.getValue() : 0;
        }

        public String nombre() {
            return texto(23);
        }

        public String automationId() {
            return texto(29);
        }

        public String clase() {
            return texto(30);
        }

        /** El tipo en palabras y en el idioma de Windows: «botón», «cuadro de edición»… */
        public String tipoLargo() {
            String t = texto(22);
            return t.isBlank() ? tipo() : t;
        }

        public int tipoId() {
            return entero(21);
        }

        public boolean activo() {
            return entero(28) != 0;
        }

        public boolean fueraDePantalla() {
            return entero(38) != 0;
        }

        /** Dónde está en la pantalla: {x, y, ancho, alto} en píxeles reales, o null. */
        public int[] recuadro() {
            Rect.ByReference r = new Rect.ByReference();
            if (llamar(p, 43, r) != 0) {
                return null;
            }
            r.read();
            return new int[] {r.izq, r.arriba, r.der - r.izq, r.abajo - r.arriba};
        }

        public List<Elemento> hijos() {
            List<Elemento> l = new ArrayList<>();
            Pointer cond = condicionTodo();
            if (cond == null) {
                return l;
            }
            PointerByReference r = new PointerByReference();
            try {
                if (llamar(p, 6, HIJOS, cond, r) != 0 || r.getValue() == null) { // FindAll
                    return l;
                }
                Pointer lista = r.getValue();
                try {
                    IntByReference n = new IntByReference();
                    if (llamar(lista, 3, n) == 0) { // get_Length
                        for (int i = 0; i < Math.min(n.getValue(), 400); i++) {
                            PointerByReference h = new PointerByReference();
                            if (llamar(lista, 4, i, h) == 0 && h.getValue() != null) { // GetElement
                                l.add(new Elemento(h.getValue()));
                            }
                        }
                    }
                } finally {
                    soltar(lista);
                }
            } finally {
                soltar(cond);
            }
            return l;
        }

        private Pointer patron(int id) {
            PointerByReference r = new PointerByReference();
            return llamar(p, 16, id, r) == 0 ? r.getValue() : null; // GetCurrentPattern
        }

        /** Lo que vale el control, exacto. null si este control no tiene un valor que leer. */
        public String valor() {
            Pointer pat = patron(PATRON_VALOR);
            if (pat != null) {
                try {
                    PointerByReference r = new PointerByReference();
                    if (llamar(pat, 4, r) == 0) { // get_CurrentValue
                        return cadena(r);
                    }
                } finally {
                    soltar(pat);
                }
            }
            Pointer txt = patron(PATRON_TEXTO);
            if (txt != null) {
                try {
                    PointerByReference rango = new PointerByReference();
                    if (llamar(txt, 7, rango) == 0 && rango.getValue() != null) { // get_DocumentRange
                        try {
                            PointerByReference r = new PointerByReference();
                            if (llamar(rango.getValue(), 12, -1, r) == 0) { // GetText(-1)
                                return cadena(r);
                            }
                        } finally {
                            soltar(rango.getValue());
                        }
                    }
                } finally {
                    soltar(txt);
                }
            }
            Pointer viejo = patron(PATRON_CLASICO);
            if (viejo != null) {
                try {
                    PointerByReference r = new PointerByReference();
                    if (llamar(viejo, 8, r) == 0) { // get_CurrentValue
                        String s = cadena(r);
                        return s.isEmpty() ? null : s;
                    }
                } finally {
                    soltar(viejo);
                }
            }
            return null;
        }

        /** Escribe el valor exacto, sin teclear: no se pierde ni un carácter y da igual el teclado del PC. */
        public boolean escribir(String texto) {
            Pointer pat = patron(PATRON_VALOR);
            if (pat != null) {
                Pointer bs = OleAut32.I.SysAllocString(new WString(texto));
                try {
                    if (llamar(pat, 3, bs) == 0) { // SetValue
                        return true;
                    }
                } finally {
                    OleAut32.I.SysFreeString(bs);
                    soltar(pat);
                }
            }
            Pointer viejo = patron(PATRON_CLASICO);
            if (viejo != null) {
                Pointer bs = OleAut32.I.SysAllocString(new WString(texto));
                try {
                    return llamar(viejo, 5, bs) == 0; // SetValue
                } finally {
                    OleAut32.I.SysFreeString(bs);
                    soltar(viejo);
                }
            }
            return false;
        }

        /** Hace lo que hace un clic en este control: pulsar el botón, elegir la opción del menú o de la lista. */
        public boolean pulsar() {
            for (int[] par : new int[][] {{PATRON_INVOCAR, 3}, {PATRON_ELEGIR, 3}, {PATRON_MARCAR, 3}, {PATRON_CLASICO, 4}}) {
                Pointer pat = patron(par[0]);
                if (pat != null) {
                    try {
                        if (llamar(pat, par[1]) == 0) {
                            return true;
                        }
                    } finally {
                        soltar(pat);
                    }
                }
            }
            return false;
        }

        /** Despliega un menú o una lista. */
        public boolean desplegar() {
            Pointer pat = patron(PATRON_DESPLEGAR);
            if (pat == null) {
                return false;
            }
            try {
                return llamar(pat, 3) == 0; // Expand
            } finally {
                soltar(pat);
            }
        }

        public boolean enfocar() {
            return llamar(p, 3) == 0; // SetFocus
        }

        /** El tipo, en una palabra fija (no depende del idioma de Windows). */
        public String tipo() {
            switch (tipoId()) {
                case 50000: return "boton";
                case 50002: return "casilla";
                case 50003: return "desplegable";
                case 50004: return "campo";
                case 50005: return "enlace";
                case 50007: return "opcion";
                case 50008: return "lista";
                case 50009: return "menu";
                case 50010: return "barra-de-menu";
                case 50011: return "opcion-de-menu";
                case 50013: return "boton-de-radio";
                case 50017: return "barra-de-estado";
                case 50018: return "pestanas";
                case 50019: return "pestana";
                case 50020: return "texto";
                case 50021: return "barra-de-herramientas";
                case 50023: return "arbol";
                case 50024: return "rama";
                case 50026: return "grupo";
                case 50028: return "tabla";
                case 50029: return "fila";
                case 50030: return "documento";
                case 50031: return "boton-con-flecha";
                case 50032: return "ventana";
                case 50033: return "panel";
                case 50036: return "tabla-simple";
                case 50037: return "barra-de-titulo";
                default: return "otro";
            }
        }

        @Override
        public void close() {
            soltar(p);
        }
    }
}
