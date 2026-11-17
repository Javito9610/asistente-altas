package es.amagna.asistente.manos;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.ArrayList;
import java.util.List;

/**
 * Las funciones de Windows que usa el asistente, llamadas directamente desde Java con JNA (sin PowerShell ni otros
 * programas). Solo las imprescindibles, con las versiones «W» (texto Unicode) y los tipos más simples.
 */
public final class Win32 {
    private Win32() {
    }

    public interface User32 extends StdCallLibrary {
        User32 I = Native.load("user32", User32.class);

        interface EnumProc extends StdCallCallback {
            boolean invoke(Pointer hwnd, Pointer datos);
        }

        Pointer GetForegroundWindow();

        int GetWindowTextW(Pointer hwnd, char[] texto, int max);

        int GetClassNameW(Pointer hwnd, char[] texto, int max);

        boolean IsWindowVisible(Pointer hwnd);

        boolean IsIconic(Pointer hwnd);

        boolean IsWindowEnabled(Pointer hwnd);

        boolean EnumWindows(EnumProc proc, Pointer datos);

        boolean EnumChildWindows(Pointer padre, EnumProc proc, Pointer datos);

        Pointer GetParent(Pointer hwnd);

        boolean GetWindowRect(Pointer hwnd, RECT rect);

        boolean IsWindow(Pointer hwnd);

        int GetWindowThreadProcessId(Pointer hwnd, IntByReference pid);

        Pointer GetAncestor(Pointer hwnd, int tipo);

        boolean SetForegroundWindow(Pointer hwnd);

        boolean BringWindowToTop(Pointer hwnd);

        boolean ShowWindow(Pointer hwnd, int modo);

        boolean AttachThreadInput(int hilo, int conHilo, boolean unir);

        boolean GetGUIThreadInfo(int hilo, GuiThreadInfo info);

        Pointer SendMessageTimeoutW(Pointer hwnd, int msg, Pointer wParam, Pointer lParam, int opciones, int msTope, PointerByReference resultado);

        int SendInput(int cuantos, Pointer entradas, int tamano);

        int MapVirtualKeyW(int codigo, int tipo);

        short VkKeyScanW(char c);

        /**
         * El aviso de Windows cuando pasa algo en la pantalla.
         *
         * <p>Corre en NUESTRO hilo y deja esperando a quien lo manda hasta que volvemos, así que aquí dentro no se
         * llama a nada que pueda tardar (y pedir el título de una ventana puede tardar: es un mensaje a otro
         * programa, y un programa colgado nos colgaría a nosotros).
         */
        interface WinEventProc extends StdCallCallback {
            void invoke(Pointer enganche, int evento, Pointer hwnd, int idObjeto, int idHijo, int hiloEvento, int cuando);
        }

        Pointer SetWinEventHook(int desde, int hasta, Pointer dll, WinEventProc aviso, int pid, int hilo, int opciones);

        boolean UnhookWinEvent(Pointer enganche);

        int GetMessageW(MSG msg, Pointer hwnd, int min, int max);

        boolean TranslateMessage(MSG msg);

        Pointer DispatchMessageW(MSG msg);

        /**
         * Dibuja la ventana en un lienzo nuestro, pidiéndoselo a ella.
         *
         * <p>Es la forma de ver una ventana que NO se puede fotografiar de la pantalla: tapada por otra, en un
         * escritorio al que nadie está mirando, o en un servidor del que te acabas de desconectar por escritorio
         * remoto. Ahí una foto de la pantalla sale negra y esto sigue dando la ventana.
         */
        boolean PrintWindow(Pointer hwnd, Pointer hdc, int opciones);

        Pointer GetWindowDC(Pointer hwnd);

        int ReleaseDC(Pointer hwnd, Pointer hdc);
    }

    /** Un mensaje de Windows. Solo hace falta para el bucle que escucha los avisos. */
    @Structure.FieldOrder({"hwnd", "mensaje", "wParam", "lParam", "cuando", "x", "y", "relleno"})
    public static class MSG extends Structure {
        public Pointer hwnd;
        public int mensaje;
        public Pointer wParam;
        public Pointer lParam;
        public int cuando;
        public int x;
        public int y;
        public int relleno;
    }

    public interface Kernel32 extends StdCallLibrary {
        Kernel32 I = Native.load("kernel32", Kernel32.class);

        int GetCurrentThreadId();

        void SetLastError(int error);

        int GetLastError();

        /** La consola desde la que nos han lanzado (para no confundirla nunca con la ventana del programa). */
        Pointer GetConsoleWindow();
    }

    /** La ventana de nuestra propia consola, o null si no hay. */
    public static Pointer consola() {
        try {
            return Kernel32.I.GetConsoleWindow();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** RECT de Win32. Lo rellena GetWindowRect (coordenadas de pantalla). */
    @Structure.FieldOrder({"left", "top", "right", "bottom"})
    public static class RECT extends Structure {
        public int left;
        public int top;
        public int right;
        public int bottom;
    }

    /** GUITHREADINFO: qué control tiene el foco del teclado en el hilo de la ventana de delante. */
    @Structure.FieldOrder({"cbSize", "flags", "hwndActive", "hwndFocus", "hwndCapture", "hwndMenuOwner", "hwndMoveSize", "hwndCaret",
            "left", "top", "right", "bottom"})
    public static class GuiThreadInfo extends Structure {
        public int cbSize;
        public int flags;
        public Pointer hwndActive;
        public Pointer hwndFocus;
        public Pointer hwndCapture;
        public Pointer hwndMenuOwner;
        public Pointer hwndMoveSize;
        public Pointer hwndCaret;
        public int left;
        public int top;
        public int right;
        public int bottom;
    }

    static final int GA_ROOT = 2;
    static final int GA_ROOTOWNER = 3;
    static final int SW_RESTORE = 9;
    public static final int SW_MINIMIZE = 6;
    static final int WM_CLOSE = 0x0010;
    static final int WM_COMMAND = 0x0111;
    static final int IDCANCEL = 2;
    static final int WM_SETTEXT = 0x000C;
    static final int WM_GETTEXT = 0x000D;
    static final int WM_GETTEXTLENGTH = 0x000E;
    static final int WM_LBUTTONDOWN = 0x0201;
    static final int WM_LBUTTONUP = 0x0202;
    static final int MK_LBUTTON = 0x0001;
    /** El clic de un botón de toda la vida. El resultado del mensaje suele ser 0 aunque el clic haya entrado. */
    public static final int BM_CLICK = 0x00F5;
    static final int SMTO_ABORTIFHUNG = 0x0002;
    static final int ERROR_TIMEOUT = 1460;
    /** Tope de espera de cada pregunta a un programa: si no contesta en este tiempo, está colgado o bloqueado. */
    static final int MS_TOPE = 2000;

    /** Cierra una ventana como si se pulsara su aspa (en un diálogo equivale a Cancelar: no guarda ni confirma nada). */
    static boolean cerrar(Pointer hwnd) {
        PointerByReference r = new PointerByReference();
        return User32.I.SendMessageTimeoutW(hwnd, WM_CLOSE, null, null, SMTO_ABORTIFHUNG, MS_TOPE, r) != null;
    }

    /** Manda un mensaje a una ventana sin quedarse esperando si el programa está colgado. */
    static boolean mandar(Pointer hwnd, int msg, long wParam, long lParam) {
        PointerByReference r = new PointerByReference();
        return User32.I.SendMessageTimeoutW(hwnd, msg, new Pointer(wParam), new Pointer(lParam), SMTO_ABORTIFHUNG, MS_TOPE, r) != null;
    }

    /** ¿Sigue siendo una ventana visible? No pregunta a UI Automation. */
    public static boolean visible(Pointer hwnd) {
        try {
            return hwnd != null && User32.I.IsWindow(hwnd) && User32.I.IsWindowVisible(hwnd);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Pulsa el botón hijo de ESE diálogo cuyo texto es el pedido (Seleccionar).
     * SendMessageTimeout: si el programa no contesta, se deja a los pocos segundos.
     * No es un clic a ciegas en la pantalla: solo ese hijo.
     *
     * @return true si el mensaje no ha caducado (el botón lo ha recibido). El diálogo hay que mirarlo después.
     */
    public static boolean pulsarBoton(Pointer dialogo, String texto, int ms) {
        if (dialogo == null || texto == null || texto.isBlank()) {
            return false;
        }
        String buscado = plano(texto);
        Pointer[] mejor = {null};
        int[] puntos = {0};
        User32.EnumProc cb = new User32.EnumProc() {
            @Override
            public boolean invoke(Pointer hwnd, Pointer datos) {
                if (hwnd == null || !User32.I.IsWindowVisible(hwnd)) {
                    return true;
                }
                String t = titulo(hwnd);
                if (t == null || t.isBlank() || !plano(t).equals(buscado)) {
                    return true;
                }
                int p = clase(hwnd).toLowerCase(java.util.Locale.ROOT).contains("button") ? 2 : 1;
                if (p > puntos[0]) {
                    puntos[0] = p;
                    mejor[0] = hwnd;
                }
                return true;
            }
        };
        User32.I.EnumChildWindows(dialogo, cb, null);
        if (mejor[0] == null) {
            return false;
        }
        Kernel32.I.SetLastError(0);
        PointerByReference r = new PointerByReference();
        User32.I.SendMessageTimeoutW(mejor[0], BM_CLICK, null, null, SMTO_ABORTIFHUNG, Math.max(500, ms), r);
        // BM_CLICK devuelve 0 aunque haya entrado. El caducado se distingue por ERROR_TIMEOUT.
        return Kernel32.I.GetLastError() != ERROR_TIMEOUT;
    }

    /**
     * BM_CLICK una sola vez sobre ESE botón, con tope.
     * Si abre un diálogo modal, la llamada caduca y el clic aun así ha entrado: se devuelve true.
     * El diálogo hay que mirarlo después. No manda un segundo clic.
     */
    public static boolean bmClick(Pointer hwnd, int ms) {
        if (!visible(hwnd)) {
            return false;
        }
        try {
            Kernel32.I.SetLastError(0);
            PointerByReference r = new PointerByReference();
            Pointer ret = User32.I.SendMessageTimeoutW(hwnd, BM_CLICK, null, null, SMTO_ABORTIFHUNG, Math.max(400, ms), r);
            return ret != null || Kernel32.I.GetLastError() == ERROR_TIMEOUT;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String plano(String s) {
        String n = java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace("&", "").trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static String titulo(Pointer hwnd) {
        char[] b = new char[512];
        int n = User32.I.GetWindowTextW(hwnd, b, b.length);
        return new String(b, 0, Math.max(0, n));
    }

    public static String clase(Pointer hwnd) {
        char[] b = new char[256];
        int n = User32.I.GetClassNameW(hwnd, b, b.length);
        return new String(b, 0, Math.max(0, n));
    }

    static long pid(Pointer hwnd) {
        IntByReference p = new IntByReference();
        User32.I.GetWindowThreadProcessId(hwnd, p);
        return p.getValue() & 0xFFFFFFFFL;
    }

    /** Texto de un control con WM_GETTEXT, sin quedarse esperando a un programa colgado (null si no contesta). */
    static String textoDe(Pointer hwnd) {
        PointerByReference r = new PointerByReference();
        if (User32.I.SendMessageTimeoutW(hwnd, WM_GETTEXTLENGTH, null, null, SMTO_ABORTIFHUNG, MS_TOPE, r) == null) {
            return null;
        }
        long largo = Pointer.nativeValue(r.getValue());
        Memory buf = new Memory((largo + 1) * 2);
        buf.clear();
        PointerByReference r2 = new PointerByReference();
        if (User32.I.SendMessageTimeoutW(hwnd, WM_GETTEXT, new Pointer(largo + 1), buf, SMTO_ABORTIFHUNG, MS_TOPE, r2) == null) {
            return null;
        }
        long copiados = Pointer.nativeValue(r2.getValue());
        char[] cs = buf.getCharArray(0, (int) Math.min(copiados, largo));
        return new String(cs);
    }

    /** Texto de un control (WM_GETTEXT). Null si el programa no contesta a tiempo. */
    public static String leerTexto(Pointer hwnd) {
        if (hwnd == null) {
            return null;
        }
        try {
            return textoDe(hwnd);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Escribe el texto de un control con WM_SETTEXT y no espera más de lo dicho.
     * No pulsa nada: el control tiene que ser el cuadro de esa ficha.
     *
     * @return true si el mensaje no ha caducado. Hay que releer el control para saber si ha quedado.
     */
    public static boolean ponerTexto(Pointer hwnd, String texto, int ms) {
        if (hwnd == null || texto == null) {
            return false;
        }
        Memory buf = new Memory(((long) texto.length() + 1) * 2);
        buf.setWideString(0, texto);
        return mensaje(hwnd, WM_SETTEXT, null, buf, ms);
    }

    /** Todos los hijos de esa ventana, nietos incluidos. Cada identificador es una copia: el callback no lo reutiliza. */
    public static List<Pointer> hijos(Pointer padre) {
        List<Pointer> lista = new ArrayList<>();
        if (padre == null) {
            return lista;
        }
        User32.EnumProc cb = new User32.EnumProc() {
            @Override
            public boolean invoke(Pointer hwnd, Pointer datos) {
                if (hwnd != null) {
                    lista.add(new Pointer(Pointer.nativeValue(hwnd)));
                }
                return true;
            }
        };
        try {
            User32.I.EnumChildWindows(padre, cb, null);
        } catch (RuntimeException e) {
            return lista;
        }
        return lista;
    }

    /** {x, y, ancho, alto} en píxeles de pantalla, o null. */
    public static int[] rectangulo(Pointer hwnd) {
        if (!visible(hwnd)) {
            return null;
        }
        RECT rc = new RECT();
        try {
            if (!User32.I.GetWindowRect(hwnd, rc)) {
                return null;
            }
            rc.read();
        } catch (RuntimeException e) {
            return null;
        }
        int ancho = rc.right - rc.left;
        int alto = rc.bottom - rc.top;
        if (ancho <= 0 || alto <= 0) {
            return null;
        }
        return new int[] {rc.left, rc.top, ancho, alto};
    }

    /** La ventana padre, o null. */
    public static Pointer padre(Pointer hwnd) {
        if (hwnd == null) {
            return null;
        }
        try {
            Pointer p = User32.I.GetParent(hwnd);
            return p == null ? null : new Pointer(Pointer.nativeValue(p));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Un clic en ESE control: el botón de ratón, no un clic en la pantalla.
     * SendMessageTimeout: si el programa no contesta, se deja a los pocos segundos.
     */
    public static boolean clicEn(Pointer hwnd, int ms) {
        if (!visible(hwnd)) {
            return false;
        }
        int x = 8;
        int y = 8;
        RECT rc = new RECT();
        try {
            if (User32.I.GetWindowRect(hwnd, rc)) {
                rc.read();
                int ancho = rc.right - rc.left;
                int alto = rc.bottom - rc.top;
                if (ancho > 2 && alto > 2) {
                    x = ancho / 2;
                    y = alto / 2;
                }
            }
        } catch (RuntimeException e) {
            // Se manda igual, al centro aproximado del control.
        }
        long lp = ((long) (y & 0xFFFF) << 16) | (x & 0xFFFF);
        Pointer punto = new Pointer(lp);
        boolean abajo = mensaje(hwnd, WM_LBUTTONDOWN, new Pointer(MK_LBUTTON), punto, ms);
        boolean arriba = mensaje(hwnd, WM_LBUTTONUP, new Pointer(0), punto, ms);
        return abajo && arriba;
    }

    /** Manda un mensaje y distingue el caducado. El búfer de lParam tiene que seguir vivo durante la llamada. */
    private static boolean mensaje(Pointer hwnd, int msg, Pointer wParam, Pointer lParam, int ms) {
        try {
            Kernel32.I.SetLastError(0);
            PointerByReference r = new PointerByReference();
            Pointer ret = User32.I.SendMessageTimeoutW(hwnd, msg, wParam, lParam, SMTO_ABORTIFHUNG, Math.max(200, ms), r);
            return ret != null && Kernel32.I.GetLastError() != ERROR_TIMEOUT;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
