package es.amagna.asistente.manos;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Teclado por SendInput de Windows, con código de tecla virtual + código de exploración (lo que manda un teclado físico)
 * o solo código de exploración. Algunas aplicaciones modernas (el Bloc de notas de Windows 11, apps WinUI) no hacen caso
 * a los atajos (Ctrl+N, Ctrl+O) que manda el robot de Java; con esto, sí. Qué modo funciona en cada PC lo dice
 * «asistente probar-windows».
 */
public final class TecladoNativo {

    public enum Modo { VK, SCAN }

    private static final int INPUT_KEYBOARD = 1;
    private static final int KEYEVENTF_EXTENDEDKEY = 0x0001;
    private static final int KEYEVENTF_KEYUP = 0x0002;
    private static final int KEYEVENTF_SCANCODE = 0x0008;

    /** Nombres de teclas de Claude (estilo xdotool) → teclas virtuales de Windows. */
    private static final Map<String, Integer> VK = Map.ofEntries(
            Map.entry("return", 0x0D), Map.entry("enter", 0x0D), Map.entry("kp_enter", 0x0D), Map.entry("tab", 0x09),
            Map.entry("escape", 0x1B), Map.entry("esc", 0x1B), Map.entry("backspace", 0x08), Map.entry("delete", 0x2E), Map.entry("del", 0x2E),
            Map.entry("insert", 0x2D), Map.entry("space", 0x20), Map.entry("up", 0x26), Map.entry("down", 0x28), Map.entry("left", 0x25),
            Map.entry("right", 0x27), Map.entry("home", 0x24), Map.entry("end", 0x23), Map.entry("page_up", 0x21), Map.entry("prior", 0x21),
            Map.entry("pageup", 0x21), Map.entry("page_down", 0x22), Map.entry("next", 0x22), Map.entry("pagedown", 0x22),
            Map.entry("ctrl", 0xA2), Map.entry("control", 0xA2), Map.entry("control_l", 0xA2), Map.entry("alt", 0xA4), Map.entry("alt_l", 0xA4),
            Map.entry("shift", 0xA0), Map.entry("shift_l", 0xA0), Map.entry("super", 0x5B), Map.entry("win", 0x5B), Map.entry("cmd", 0x5B),
            Map.entry("meta", 0x5B), Map.entry("menu", 0x5D), Map.entry("caps_lock", 0x14), Map.entry("print", 0x2C));

    /** Teclas «extendidas» (en un teclado físico van con el prefijo E0): sin esta marca, algunas aplicaciones las confunden. */
    private static final List<Integer> EXTENDIDAS = List.of(0x2D, 0x2E, 0x24, 0x23, 0x21, 0x22, 0x25, 0x26, 0x27, 0x28, 0x5B, 0x5C, 0x5D, 0xA3, 0xA5, 0x2C);

    private TecladoNativo() {
    }

    /** «ctrl+o» → [VK_LCONTROL, 'O']. Falla con un nombre desconocido (mejor parar que pulsar otra cosa). */
    public static List<Integer> combinacion(String texto) {
        List<Integer> r = new ArrayList<>();
        for (String parte : texto.trim().split("\\+")) {
            r.add(vk(parte.trim()));
        }
        return r;
    }

    static int vk(String nombre) {
        if (nombre.isEmpty()) {
            throw new IllegalArgumentException("Tecla vacía");
        }
        String n = nombre.toLowerCase(Locale.ROOT);
        Integer c = VK.get(n);
        if (c != null) {
            return c;
        }
        if (n.matches("f([1-9]|1[0-9]|2[0-4])")) {
            return 0x70 + Integer.parseInt(n.substring(1)) - 1;
        }
        if (nombre.length() == 1) {
            char ch = Character.toUpperCase(nombre.charAt(0));
            if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')) {
                return ch;
            }
            short k = Win32.User32.I.VkKeyScanW(nombre.charAt(0));
            if (k != -1) {
                return k & 0xFF;
            }
        }
        throw new IllegalArgumentException("Tecla desconocida: «" + nombre + "»");
    }

    /** Pulsa la combinación (en orden) y la suelta (al revés), en una sola entrada a Windows para que no se cuele nada en medio. */
    public static void pulsar(List<Integer> vks, Modo modo) {
        int n = vks.size() * 2;
        int tam = Native.POINTER_SIZE == 8 ? 40 : 28;
        Memory m = new Memory((long) n * tam);
        m.clear();
        int i = 0;
        for (int vk : vks) {
            escribir(m, i++ * tam, vk, false, modo);
        }
        for (int k = vks.size() - 1; k >= 0; k--) {
            escribir(m, i++ * tam, vks.get(k), true, modo);
        }
        int enviados = Win32.User32.I.SendInput(n, m, tam);
        if (enviados != n) {
            throw new IllegalStateException("Windows no ha aceptado las teclas (" + enviados + " de " + n + ")");
        }
    }

    private static void escribir(Memory m, long o, int vk, boolean soltar, Modo modo) {
        int scan = Win32.User32.I.MapVirtualKeyW(vk, 0) & 0xFFFF;
        int flags = (soltar ? KEYEVENTF_KEYUP : 0) | (EXTENDIDAS.contains(vk) ? KEYEVENTF_EXTENDEDKEY : 0) | (modo == Modo.SCAN ? KEYEVENTF_SCANCODE : 0);
        int base = Native.POINTER_SIZE == 8 ? 8 : 4; // INPUT: type (DWORD) + relleno hasta la unión
        m.setInt(o, INPUT_KEYBOARD);
        m.setShort(o + base, (short) (modo == Modo.SCAN ? 0 : vk));
        m.setShort(o + base + 2, (short) scan);
        m.setInt(o + base + 4, flags);
        m.setInt(o + base + 8, 0); // time
        // dwExtraInfo = 0 (la memoria ya está a cero)
    }
}
