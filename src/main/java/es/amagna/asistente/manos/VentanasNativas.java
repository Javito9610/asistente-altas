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

    /** Los diálogos visibles: ventanas propiedad de otra o de la clase estándar de diálogo. */
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

    /**
     * ¿Tiene esta ventana un diálogo modal encima? Windows deshabilita la ventana mientras hay uno, así que es una señal
     * inmediata y segura: no hace falta encontrar la ventana del diálogo (algunos tardan en aparecer o no se listan).
     */
    static boolean bloqueada(Pointer h) {
        return !Win32.User32.I.IsWindowEnabled(h);
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
        // Cada diálogo, con su ventana: la dueña si está a la vista y, si no (pasa en las aplicaciones modernas, donde la
        // dueña es una ventana oculta), la ventana principal de su mismo programa.
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
            }
            if (bloqueada(h)) {
                b.append(" [BLOQUEADA: tiene un diálogo encima y no responderá hasta que se cierre]");
            }
            b.append('\n');
        }
        huerfanos.forEach(t -> b.append("- diálogo ").append(t).append(" sin ventana principal a la vista\n"));
        if (delante != null && dialogosSueltos.contains(delante)) {
            b.append("(Delante, con el foco, está el diálogo «").append(Win32.titulo(delante)).append("»)\n");
        }
        return b.toString().strip();
    }

    /**
     * Cancela lo que esté bloqueando cualquier ventana: por cada ventana deshabilitada, busca el diálogo de su mismo
     * programa (aunque no se sepa su título) y lo cancela. No hace falta saber cómo se llama ni de quién depende.
     */
    @Override
    public List<String> cancelarBloqueos() {
        List<String> hecho = new ArrayList<>();
        if (modoVista) {
            System.out.println("    [modo vista, no se hace] cancelar lo que bloquee alguna ventana");
            return hecho;
        }
        for (int vuelta = 0; vuelta < 5; vuelta++) {
            Pointer presa = null;
            for (Pointer h : visibles()) {
                if (bloqueada(h)) {
                    presa = h;
                    break;
                }
            }
            if (presa == null) {
                return hecho;
            }
            long pid = Win32.pid(presa);
            Pointer culpable = null;
            for (Pointer d : visibles()) { // el diálogo: del mismo programa, no bloqueado y distinto de la presa
                if (Win32.pid(d) == pid && !d.equals(presa) && !bloqueada(d)
                        && ("#32770".equals(Win32.clase(d)) || !presa.equals(Win32.User32.I.GetAncestor(d, Win32.GA_ROOTOWNER)))) {
                    culpable = d;
                    break;
                }
            }
            if (culpable == null) {
                hecho.add("hay una ventana bloqueada («" + Win32.titulo(presa) + "») pero no se ve qué diálogo la bloquea");
                return hecho;
            }
            String t = Win32.titulo(culpable);
            Win32.mandar(culpable, Win32.WM_COMMAND, Win32.IDCANCEL, 0);
            if (!esperarCierre(culpable)) {
                Win32.cerrar(culpable);
                esperarCierre(culpable);
            }
            hecho.add("«" + t + "»");
        }
        return hecho;
    }

    /** Instantánea de las ventanas visibles: identificador → título. Para saber qué ventana es NUEVA tras hacer algo. */
    public static Map<Long, String> instantanea() {
        Map<Long, String> m = new LinkedHashMap<>();
        for (Pointer h : visibles()) {
            m.put(Pointer.nativeValue(h), Win32.titulo(h));
        }
        return m;
    }

    private Pointer buscar(String titulo, List<Pointer> donde) {
        String buscado = titulo.toLowerCase(Locale.ROOT);
        for (Pointer h : donde) {
            if (Win32.titulo(h).toLowerCase(Locale.ROOT).contains(buscado)) {
                return h;
            }
        }
        return null;
    }

    /**
     * Cancela un diálogo como lo haría una persona con el botón Cancelar: primero IDCANCEL (lo que entiende un diálogo
     * estándar de Windows) y, si sigue ahí, su aspa. Así el programa se entera de que el diálogo se ha cerrado y vuelve
     * a funcionar (cerrarlo «a lo bruto» puede dejarlo creyendo que sigue abierto).
     */
    @Override
    public String cancelarDialogo(String titulo) {
        if (modoVista) {
            System.out.println("    [modo vista, no se hace] cancelar diálogo «" + titulo + "»");
            return null;
        }
        Pointer d = buscar(titulo, dialogos());
        if (d == null) {
            return null;
        }
        String t = Win32.titulo(d);
        Win32.mandar(d, Win32.WM_COMMAND, Win32.IDCANCEL, 0);
        if (esperarCierre(d)) {
            return t;
        }
        Win32.cerrar(d);
        esperarCierre(d);
        return t;
    }

    @Override
    public String cerrarVentana(String titulo) {
        if (modoVista) {
            System.out.println("    [modo vista, no se hace] cerrar ventana «" + titulo + "»");
            return null;
        }
        Pointer h = buscar(titulo, visibles());
        if (h == null) {
            return null;
        }
        String t = Win32.titulo(h);
        Win32.cerrar(h);
        esperarCierre(h);
        return t;
    }

    private static boolean esperarCierre(Pointer h) {
        for (int k = 0; k < 30; k++) {
            if (!Win32.User32.I.IsWindowVisible(h)) {
                return true;
            }
            dormir(50);
        }
        return false;
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
