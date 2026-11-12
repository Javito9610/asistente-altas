package es.amagna.asistente.manos;

import com.sun.jna.Pointer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Las ventanas de Windows, preguntadas directamente a Windows (JNA): rápido (milisegundos) y sin depender de PowerShell.
 *  - abiertas(): todas las ventanas visibles con título y, debajo de cada una, sus diálogos (ventanas «propiedad» de ella,
 *    como «Abrir» o «Guardar»), aunque estén detrás de otras.
 *  - campoConFoco(): el control que tiene el foco del teclado y su texto exacto (WM_GETTEXT, el mensaje estándar de los
 *    controles de Windows). Con tope de 2 s: si el programa no contesta, se dice y no se queda colgado.
 *  - activar(): pone delante una ventana aunque Windows ponga pegas (une momentáneamente la entrada con la ventana de delante).
 */
public final class VentanasNativas implements Ventanas {
    private final boolean modoVista;

    public VentanasNativas(boolean modoVista) {
        this.modoVista = modoVista;
    }

    @Override
    public void abrir(String programa, List<String> argumentos) {
        if (modoVista) {
            System.out.println("    [modo vista, no se hace] abrir " + programa + " " + argumentos);
            return;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(programa);
        cmd.addAll(argumentos);
        try {
            new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            throw new IllegalStateException("No se puede abrir «" + programa + "»: " + e.getMessage(), e);
        }
    }

    /** Ventanas visibles y con título, en el orden de Windows (de la de delante a la de detrás). */
    static List<Pointer> visibles() {
        List<Pointer> r = new ArrayList<>();
        Win32.User32.I.EnumWindows(new Win32.User32.EnumProc() {
            @Override
            public boolean invoke(Pointer hwnd, Pointer datos) {
                if (Win32.User32.I.IsWindowVisible(hwnd) && !Win32.titulo(hwnd).isBlank()) {
                    r.add(hwnd);
                }
                return true;
            }
        }, null);
        return r;
    }

    static String programa(Pointer hwnd) {
        long pid = Win32.pid(hwnd);
        return ProcessHandle.of(pid).flatMap(p -> p.info().command())
                .map(c -> Path.of(c).getFileName().toString().replaceFirst("(?i)\\.exe$", "")).orElse("pid " + pid);
    }

    @Override
    public String abiertas() {
        Map<Pointer, List<String>> dialogos = new LinkedHashMap<>();
        List<Pointer> principales = new ArrayList<>();
        for (Pointer h : visibles()) {
            Pointer dueno = Win32.User32.I.GetAncestor(h, Win32.GA_ROOTOWNER);
            if (dueno != null && !dueno.equals(h)) {
                dialogos.computeIfAbsent(dueno, k -> new ArrayList<>()).add(Win32.titulo(h));
            } else {
                principales.add(h);
            }
        }
        Pointer delante = Win32.User32.I.GetForegroundWindow();
        StringBuilder b = new StringBuilder();
        for (Pointer h : principales) {
            b.append("- «").append(Win32.titulo(h)).append("» (").append(programa(h)).append(")");
            if (h.equals(delante)) {
                b.append(" [delante, con el foco]");
            }
            if (Win32.User32.I.IsIconic(h)) {
                b.append(" [minimizada]");
            }
            List<String> d = dialogos.remove(h);
            if (d != null) {
                b.append(" — diálogos abiertos dentro: ");
                d.forEach(t -> b.append("«").append(t).append("» "));
            }
            b.append('\n');
        }
        // Diálogos cuyo dueño no es visible (raro, pero se enseñan igual).
        dialogos.forEach((dueno, d) -> d.forEach(t -> b.append("- «").append(t).append("» (diálogo de una ventana oculta)\n")));
        return b.toString().strip();
    }

    @Override
    public String activa() {
        Pointer h = Win32.User32.I.GetForegroundWindow();
        return h == null ? "(ninguna)" : Win32.titulo(h);
    }

    @Override
    public boolean activar(String titulo) {
        if (modoVista) {
            System.out.println("    [modo vista, no se hace] activar ventana «" + titulo + "»");
            return false;
        }
        String buscado = titulo.toLowerCase(Locale.ROOT);
        Pointer h = null;
        for (Pointer v : visibles()) {
            if (Win32.titulo(v).toLowerCase(Locale.ROOT).contains(buscado)) {
                h = v;
                break; // la de más delante que coincide
            }
        }
        if (h == null) {
            return false;
        }
        if (Win32.User32.I.IsIconic(h)) {
            Win32.User32.I.ShowWindow(h, Win32.SW_RESTORE);
        }
        Pointer delante = Win32.User32.I.GetForegroundWindow();
        int yo = Win32.Kernel32.I.GetCurrentThreadId();
        int suyo = delante == null ? 0 : Win32.User32.I.GetWindowThreadProcessId(delante, null);
        boolean unido = suyo != 0 && suyo != yo && Win32.User32.I.AttachThreadInput(yo, suyo, true);
        try {
            Win32.User32.I.BringWindowToTop(h);
            Win32.User32.I.SetForegroundWindow(h);
        } finally {
            if (unido) {
                Win32.User32.I.AttachThreadInput(yo, suyo, false);
            }
        }
        for (int k = 0; k < 20; k++) { // hasta 1 s para que Windows lo haga efectivo
            if (h.equals(Win32.User32.I.GetForegroundWindow())) {
                return true;
            }
            dormir(50);
        }
        return false;
    }

    @Override
    public Campo campoConFoco() {
        Pointer delante = Win32.User32.I.GetForegroundWindow();
        if (delante == null) {
            return new Campo("", "", null, "(ninguna)");
        }
        Win32.GuiThreadInfo gi = new Win32.GuiThreadInfo();
        gi.cbSize = gi.size();
        int hilo = Win32.User32.I.GetWindowThreadProcessId(delante, null);
        Pointer foco = Win32.User32.I.GetGUIThreadInfo(hilo, gi) && gi.hwndFocus != null ? gi.hwndFocus : delante;
        Pointer raiz = Win32.User32.I.GetAncestor(foco, Win32.GA_ROOT);
        String ventana = Win32.titulo(raiz != null ? raiz : delante);
        String clase = Win32.clase(foco);
        if (foco.equals(raiz)) { // el foco es la ventana entera: o no hay campo con foco o es un control moderno sin ventana propia
            return new Campo("", clase, null, ventana);
        }
        String texto = Win32.textoDe(foco);
        if (texto == null) {
            throw new IllegalStateException("El programa de «" + ventana + "» no ha contestado en 2 s: puede tener un diálogo abierto "
                    + "que lo bloquea (míralo con ventanas_abiertas) o estar colgado.");
        }
        return new Campo(clase, clase, texto, ventana);
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
