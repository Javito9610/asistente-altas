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
        apuntarLasNuestras();
        yaEstaban.clear();
        yaEstaban.addAll(instantanea().keySet());
        cuandoAbrimos = System.currentTimeMillis();
        try {
            nuestrosPid.add(new ProcessBuilder(cmd).start().pid());
        } catch (IOException e) {
            throw new IllegalStateException("No se puede abrir «" + programa + "»: " + e.getMessage(), e);
        }
    }

    // --- Qué ventanas son nuestras (para no cerrar nunca algo de otro) ---
    private final java.util.Set<Long> nuestrosPid = new java.util.HashSet<>();
    private final java.util.Set<Long> nuestras = new java.util.HashSet<>();
    private final java.util.Set<Long> yaEstaban = new java.util.HashSet<>();
    private long cuandoAbrimos;
    private static final long MARGEN_MS = 60_000;

    /** Las ventanas que han salido justo después de abrir nosotros un programa se apuntan como nuestras. */
    private void apuntarLasNuestras() {
        if (cuandoAbrimos == 0 || System.currentTimeMillis() - cuandoAbrimos > MARGEN_MS) {
            return;
        }
        for (Long h : instantanea().keySet()) {
            if (!yaEstaban.contains(h)) {
                nuestras.add(h);
            }
        }
    }

    /**
     * Cierra una ventana SOLO si la hemos abierto nosotros: o es de un programa que hemos arrancado, o salió justo
     * después de arrancarlo. Si no, no se toca y se dice por qué. Así el asistente no puede cerrarle a nadie su trabajo.
     */
    public String cerrarSiEsNuestra(String titulo) {
        apuntarLasNuestras();
        Pointer h = handleDe(titulo);
        if (h == null) {
            return "No hay ninguna ventana cuyo título contenga «" + titulo + "».";
        }
        String nombre = Win32.titulo(h);
        boolean mismaApp = nuestrosPid.contains(Win32.pid(h));
        if (!mismaApp && !nuestras.contains(Pointer.nativeValue(h))) {
            return "NO SE CIERRA «" + nombre + "»: esa ventana no la has abierto tú, así que no se toca. "
                    + "Solo puedes cerrar lo que hayas abierto en esta tarea.";
        }
        String cerrada = cerrarVentana(nombre);
        return cerrada == null ? "No se ha podido cerrar «" + nombre + "»." : "Cerrada «" + cerrada + "».";
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

    /**
     * Qué ventana está bloqueando a esta: la que le pertenece (su diálogo), o si no, cualquier otra ventana suelta de
     * su mismo programa que sí responda. De la más reciente a la más antigua: si hay varios diálogos encadenados, el
     * que bloquea es el último.
     */
    private static Pointer culpableDe(Pointer presa) {
        long pid = Win32.pid(presa);
        List<Pointer> candidatas = new ArrayList<>();
        for (Pointer d : visibles()) {
            if (!d.equals(presa) && !bloqueada(d) && Win32.pid(d) == pid) {
                candidatas.add(d);
            }
        }
        for (Pointer d : candidatas) { // primero, una que sea suya de verdad
            if (presa.equals(Win32.User32.I.GetAncestor(d, Win32.GA_ROOTOWNER))) {
                return d;
            }
        }
        for (Pointer d : candidatas) { // si no, un diálogo estándar del mismo programa
            if ("#32770".equals(Win32.clase(d))) {
                return d;
            }
        }
        return candidatas.isEmpty() ? null : candidatas.get(0);
    }

    /** Cancela una ventana de diálogo: IDCANCEL si es un diálogo estándar de Windows y, si no se va, su aspa. */
    private static String cancelar(Pointer d) {
        String t = Win32.titulo(d);
        if ("#32770".equals(Win32.clase(d))) {
            Win32.mandar(d, Win32.WM_COMMAND, Win32.IDCANCEL, 0);
            if (esperarCierre(d)) {
                return t;
            }
        }
        Win32.cerrar(d);
        esperarCierre(d);
        return t;
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
            Pointer culpable = culpableDe(presa);
            if (culpable == null) {
                hecho.add("hay una ventana bloqueada («" + Win32.titulo(presa) + "») pero no se ve qué diálogo la bloquea");
                return hecho;
            }
            hecho.add("«" + cancelar(culpable) + "»");
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

    /** La ventana (a la vista) cuyo título contiene el texto: la de más delante que coincide. */
    public Pointer handleDe(String titulo) {
        return buscar(titulo, visibles());
    }

    /**
     * Encuentra la ventana que mejor encaja con el título, no la primera que lo contenga.
     *
     * Dos cosas que parecen tonterías y no lo son:
     *  - NUNCA devuelve nuestra propia consola. El título de la consola lleva el comando entero, así que al buscar
     *    «MNprogram» encontraba la consola en la que se había escrito «MNprogram»: se leía a sí misma.
     *  - Puntúa: el título exacto vale más que empezar igual, y eso más que contenerlo en medio. Y a igualdad,
     *    gana la que se está viendo.
     */
    private Pointer buscar(String titulo, List<Pointer> donde) {
        String buscado = titulo.toLowerCase(Locale.ROOT).trim();
        Pointer consola = Win32.consola();
        Pointer mejor = null;
        int masPuntos = 0;
        for (Pointer h : donde) {
            if (consola != null && Pointer.nativeValue(h) == Pointer.nativeValue(consola)) {
                continue;
            }
            String t = Win32.titulo(h).toLowerCase(Locale.ROOT).trim();
            int p = t.equals(buscado) ? 100 : t.startsWith(buscado) ? 80 : t.contains(buscado) ? 60 : 0;
            if (p == 0) {
                continue;
            }
            if (!Win32.User32.I.IsIconic(h)) {
                p += 5;
            }
            if (p > masPuntos) {
                masPuntos = p;
                mejor = h;
            }
        }
        return mejor;
    }

    /**
     * Si la ventana está minimizada, la restaura: para leer sus controles Windows tiene que tenerla dibujada.
     * Antes esto se le pedía a la persona, que es justo lo que no tiene que pasar. Devuelve true si la ha restaurado.
     */
    /** El título completo de la ventana que encaja con ese texto, o null. */
    public String tituloDe(String titulo) {
        Pointer h = handleDe(titulo);
        return h == null ? null : Win32.titulo(h);
    }

    /** ¿Estas dos ventanas son del mismo programa? (para no cerrar la de otro). */
    public boolean mismoProgramaQue(String unTitulo, String otroTitulo) {
        Pointer a = handleDe(unTitulo);
        Pointer b = handleDe(otroTitulo);
        return a != null && b != null && Win32.pid(a) == Win32.pid(b);
    }

    public boolean despertar(String titulo) {
        Pointer h = handleDe(titulo);
        if (h == null || !Win32.User32.I.IsIconic(h)) {
            return false;
        }
        Win32.User32.I.ShowWindow(h, Win32.SW_RESTORE);
        for (int i = 0; i < 20 && Win32.User32.I.IsIconic(h); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return true;
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
        return cancelar(d);
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
