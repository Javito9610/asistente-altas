package es.amagna.asistente.jarvis;

import java.awt.Robot;
import java.awt.event.KeyEvent;

/**
 * Despejar el escritorio: minimizar todas las ventanas (Windows+D) y volver a ponerlas (Windows+Mayús+M). NUNCA cierra nada: cerrar
 * ventanas podría perder trabajo sin guardar, y eso no se hace sin que la persona lo pida con todas las letras.
 */
public final class Escritorio {
    private Escritorio() {
    }

    public static void minimizarTodo() {
        teclas(KeyEvent.VK_WINDOWS, KeyEvent.VK_D);
    }

    public static void restaurarTodo() {
        teclas(KeyEvent.VK_WINDOWS, KeyEvent.VK_SHIFT, KeyEvent.VK_M);
    }

    private static void teclas(int... t) {
        try {
            Robot r = new Robot();
            for (int k : t) {
                r.keyPress(k);
            }
            for (int i = t.length - 1; i >= 0; i--) {
                r.keyRelease(t[i]);
            }
            r.delay(300);
        } catch (java.awt.AWTException | RuntimeException e) {
            throw new IllegalStateException(String.valueOf(e.getMessage()), e);
        }
    }
}
