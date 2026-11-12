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
        List<Pointer> todas = visibles();
        List<Pointer> principales = new ArrayList<>();
        List<Pointer> dialogosSueltos = new ArrayList<>();
        for (Pointer h : todas) {
            Pointer dueno = Win32.User32.I.GetAncestor(h, Win32.GA_ROOTOWNER);
            boolean propiedad = dueno != null && !dueno.equals(h);
            if (propiedad || "#32770".equals(Win32.clase(h))) { // #32770: la clase de los diálogos estándar de Windows
                dialogosSueltos.add(h);
            } else {
                principales.add(h);
            }
        }
        // Cada diálogo, con su ventana: la dueña si está a la vista y, si no (pasa en las aplicaciones modernas, como el Bloc
        // de notas de Windows 11, donde la dueña es una ventana oculta), la ventana principal de su mismo programa.
        Map<Pointer, List<String>> dialogos = new LinkedHashMap<>();
        List<String> huerfanos = new ArrayList<>();
        for (Pointer d : dialogosSueltos) {
            Pointer dueno = Win32.User32.I.GetAncestor(d, Win32.GA_ROOTOWNER);
            Pointer casa = principales.contains(dueno) ? dueno : null;
            if (casa == null) {
                long pid = Win32.pid(d);
                for (Pointer p : principales) {
                    if (Win32.pid(p) == pid) {
                        casa = p;
                        break; // la de más delante de ese programa
                    }
                }
            }
            if (casa != null) {
                dialogos.computeIfAbsent(casa, k -> new ArrayList<>()).add(Win32.titulo(d));
            } else {
                huerfanos.add("«" + Win32.titulo(d) + "» (" + programa(d) + ")");
            }
        }
        // Un diálogo (modal) bloquea a todo su programa: en las aplicaciones que abren varias ventanas en el mismo programa
        // (el Bloc de notas de Windows 11, muchos programas de gestión), las demás ventanas no responden a los atajos mientras
        // esté abierto. Se marca, para que se sepa por qué una ventana «no hace caso».
        Map<Long, String> bloqueo = new LinkedHashMap<>();
        dialogos.forEach((casa, d) -> bloqueo.putIfAbsent(Win32.pid(casa), "«" + d.get(0) + "» de «" + Win32.titulo(casa) + "»"));
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
            List<String> d = dialogos.get(h);
            if (d != null) {
                b.append(" — diálogos abiertos dentro: ");
                d.forEach(t -> b.append("«").append(t).append("» "));
            } else if (bloqueo.containsKey(Win32.pid(h))) {
                b.append(" [BLOQUEADA por el diálogo ").append(bloqueo.get(Win32.pid(h))).append(", del mismo programa: ciérralo primero]");
            }
            b.append('\n');
        }
        huerfanos.forEach(t -> b.append("- diálogo ").append(t).append(" sin ventana principal a la vista\n"));
        if (delante != null && dialogosSueltos.contains(delante)) {
            b.append("(Delante, con el foco, está el diálogo «").append(Win32.titulo(delante)).append("»)\n");
        }
        return b.toString().strip();
    }

    /** Los diálogos visibles (ventanas propiedad de otra o de la clase estándar de diálogo). */
    static List<Pointer> dialogos() {
        List<Pointer> r = new ArrayList<>();
        for (Pointer h : visibles()) {
            Pointer dueno = Win32.User32.I.GetAncestor(h, Win32.GA_ROOTOWNER);
            if ((dueno != null && !dueno.equals(h)) || "#32770".equals(Win32.clase(h))) {
                r.add(h);
            }
        }
        return r;
    }

    @Override
    public String cancelarDialogo(String titulo) {
        if (modoVista) {
            System.out.println("    [modo vista, no se hace] cancelar diálogo «" + titulo + "»");
            return null;
        }
        String buscado = titulo.toLowerCase(Locale.ROOT);
        for (Pointer d : dialogos()) {
            String t = Win32.titulo(d);
            if (t.toLowerCase(Locale.ROOT).contains(buscado)) {
                Win32.cerrar(d);
                for (int k = 0; k < 20 && Win32.User32.I.IsWindowVisible(d); k++) {
                    dormir(50);
                }
                return t;
            }
        }
        return null;
    }

    /** Para diagnosticar: todas las ventanas visibles, sin agrupar, con su clase, programa y dueña. */
    public static String diagnostico() {
        StringBuilder b = new StringBuilder();
        Pointer delante = Win32.User32.I.GetForegroundWindow();
        b.append("   delante: «").append(delante == null ? "" : Win32.titulo(delante)).append("» clase ").append(delante == null ? "" : Win32.clase(delante))
                .append(" (").append(delante == null ? "" : programa(delante)).append(")\n");
        for (Pointer h : visibles()) {
            Pointer dueno = Win32.User32.I.GetAncestor(h, Win32.GA_ROOTOWNER);
            b.append("   · «").append(Win32.titulo(h)).append("» clase ").append(Win32.clase(h)).append(" (").append(programa(h)).append(", pid ")
                    .append(Win32.pid(h)).append(")").append(dueno != null && !dueno.equals(h) ? " dueña «" + Win32.titulo(dueno) + "» clase " + Win32.clase(dueno) : "")
                    .append('\n');
        }
        return b.toString();
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
