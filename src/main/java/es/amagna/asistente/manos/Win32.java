package es.amagna.asistente.manos;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

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

        boolean EnumWindows(EnumProc proc, Pointer datos);

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
    }

    public interface Kernel32 extends StdCallLibrary {
        Kernel32 I = Native.load("kernel32", Kernel32.class);

        int GetCurrentThreadId();
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
    static final int WM_GETTEXT = 0x000D;
    static final int WM_GETTEXTLENGTH = 0x000E;
    static final int SMTO_ABORTIFHUNG = 0x0002;
    /** Tope de espera de cada pregunta a un programa: si no contesta en este tiempo, está colgado o bloqueado. */
    static final int MS_TOPE = 2000;

    static String titulo(Pointer hwnd) {
        char[] b = new char[512];
        int n = User32.I.GetWindowTextW(hwnd, b, b.length);
        return new String(b, 0, Math.max(0, n));
    }

    static String clase(Pointer hwnd) {
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
}
