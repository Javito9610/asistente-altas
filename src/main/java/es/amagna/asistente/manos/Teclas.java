package es.amagna.asistente.manos;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Nombres de teclas que usa Claude (estilo xdotool: «Return», «ctrl+s», «alt+F4», «Page_Down»…) → códigos de tecla de Java.
 * Una combinación se pulsa en orden y se suelta al revés.
 */
public final class Teclas {
    private static final Map<String, Integer> NOMBRES = Map.ofEntries(
            Map.entry("return", KeyEvent.VK_ENTER), Map.entry("enter", KeyEvent.VK_ENTER), Map.entry("kp_enter", KeyEvent.VK_ENTER),
            Map.entry("tab", KeyEvent.VK_TAB), Map.entry("escape", KeyEvent.VK_ESCAPE), Map.entry("esc", KeyEvent.VK_ESCAPE),
            Map.entry("backspace", KeyEvent.VK_BACK_SPACE), Map.entry("delete", KeyEvent.VK_DELETE), Map.entry("del", KeyEvent.VK_DELETE),
            Map.entry("insert", KeyEvent.VK_INSERT), Map.entry("space", KeyEvent.VK_SPACE),
            Map.entry("up", KeyEvent.VK_UP), Map.entry("down", KeyEvent.VK_DOWN), Map.entry("left", KeyEvent.VK_LEFT), Map.entry("right", KeyEvent.VK_RIGHT),
            Map.entry("home", KeyEvent.VK_HOME), Map.entry("end", KeyEvent.VK_END),
            Map.entry("page_up", KeyEvent.VK_PAGE_UP), Map.entry("prior", KeyEvent.VK_PAGE_UP), Map.entry("pageup", KeyEvent.VK_PAGE_UP),
            Map.entry("page_down", KeyEvent.VK_PAGE_DOWN), Map.entry("next", KeyEvent.VK_PAGE_DOWN), Map.entry("pagedown", KeyEvent.VK_PAGE_DOWN),
            Map.entry("ctrl", KeyEvent.VK_CONTROL), Map.entry("control", KeyEvent.VK_CONTROL), Map.entry("control_l", KeyEvent.VK_CONTROL),
            Map.entry("alt", KeyEvent.VK_ALT), Map.entry("alt_l", KeyEvent.VK_ALT), Map.entry("shift", KeyEvent.VK_SHIFT), Map.entry("shift_l", KeyEvent.VK_SHIFT),
            Map.entry("super", KeyEvent.VK_WINDOWS), Map.entry("win", KeyEvent.VK_WINDOWS), Map.entry("cmd", KeyEvent.VK_WINDOWS), Map.entry("meta", KeyEvent.VK_WINDOWS),
            Map.entry("menu", KeyEvent.VK_CONTEXT_MENU), Map.entry("caps_lock", KeyEvent.VK_CAPS_LOCK), Map.entry("print", KeyEvent.VK_PRINTSCREEN));

    private Teclas() {
    }

    /** «ctrl+shift+s» → [CTRL, SHIFT, S]. Falla con un nombre que no conoce (mejor parar que pulsar otra cosa). */
    public static List<Integer> combinacion(String texto) {
        List<Integer> r = new ArrayList<>();
        for (String parte : texto.trim().split("\\+")) {
            r.add(codigo(parte.trim()));
        }
        return r;
    }

    public static int codigo(String nombre) {
        if (nombre.isEmpty()) {
            throw new IllegalArgumentException("Tecla vacía");
        }
        String n = nombre.toLowerCase(Locale.ROOT);
        Integer c = NOMBRES.get(n);
        if (c != null) {
            return c;
        }
        if (n.matches("f([1-9]|1[0-9]|2[0-4])")) {
            return KeyEvent.VK_F1 + Integer.parseInt(n.substring(1)) - 1;
        }
        if (nombre.length() == 1) {
            char ch = nombre.charAt(0);
            if (Character.isLetterOrDigit(ch) && ch < 128) {
                return Character.isDigit(ch) ? KeyEvent.VK_0 + (ch - '0') : KeyEvent.VK_A + (Character.toLowerCase(ch) - 'a');
            }
            int ext = KeyEvent.getExtendedKeyCodeForChar(ch);
            if (ext != KeyEvent.VK_UNDEFINED) {
                return ext;
            }
        }
        throw new IllegalArgumentException("Tecla desconocida: «" + nombre + "»");
    }
}
