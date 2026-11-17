package es.amagna.asistente.manos;

import com.sun.jna.Pointer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Lo que pasa en la pantalla, según pasa.
 *
 * <h2>Por qué esto cambia la forma de trabajar</h2>
 * Hasta ahora el asistente preguntaba. Pulsaba un botón y se ponía a mirar cada 400 ms a ver si había salido una
 * ventana nueva, hasta que salía o hasta que se acababa el tiempo. Eso tiene dos problemas, y los dos se notan:
 * tarda —en el peor caso, 400 ms de más en cada paso, y en una ficha con diez pasos eso son cuatro segundos
 * regalados— y se le escapan las cosas rápidas: un diálogo que se abre y se cierra entre dos miradas no ha existido
 * nunca para él.
 *
 * <p>Windows sabe perfectamente cuándo se abre una ventana, cuándo cambia de nombre y cuándo pasa al frente, y lo
 * puede avisar. Se llama {@code SetWinEventHook} y es lo que usa el propio lector de pantalla de Windows. Aquí se
 * engancha una vez, al arrancar, y a partir de ese momento el asistente se entera de todo en cuanto ocurre, sin
 * preguntar. Esperar una ventana deja de costar 400 ms y pasa a costar lo que tarde Windows en avisar.
 *
 * <h2>Lo que se hace dentro del aviso, y lo que no</h2>
 * El aviso de Windows llega en un hilo nuestro y bloquea a quien lo manda mientras no volvamos. Si dentro del aviso
 * pidiéramos el título de la ventana —que es un mensaje a otro programa— un programa colgado nos colgaría a
 * nosotros. Así que dentro del aviso solo se apunta qué pasó, cuándo y en qué ventana, que es inmediato. El título se
 * lee después, cuando hace falta, fuera del aviso.
 *
 * <h2>Si no se puede enganchar</h2>
 * En algún Windows con la política apretada, o si falla, {@link #disponible()} dice que no y todo sigue funcionando
 * como antes, preguntando. Esto acelera y afina; no es un sitio donde se pueda quedar atascado.
 *
 * @package Amagna\Manos
 */
public final class Percepcion {
    private Percepcion() {
    }

    /** Una ventana ha pasado al frente. */
    public static final int AL_FRENTE = 0x0003;
    /** Se ha abierto un diálogo. */
    public static final int DIALOGO = 0x0010;
    /** Algo se ha creado. */
    public static final int CREADO = 0x8000;
    /** Algo se ha destruido. */
    public static final int DESTRUIDO = 0x8001;
    /** Algo se ha mostrado. */
    public static final int MOSTRADO = 0x8002;
    /** Algo se ha ocultado. */
    public static final int OCULTADO = 0x8003;
    /** Algo ha cogido el foco. */
    public static final int FOCO = 0x8005;
    /** Algo ha cambiado de nombre. */
    public static final int RENOMBRADO = 0x800C;

    /** Cuántos cambios se guardan. Pasado esto, se tiran los más viejos: lo de hace un minuto ya no decide nada. */
    static final int RECUERDO = 4000;

    private static final Object CANDADO = new Object();
    private static final List<Cambio> HISTORIAL = new ArrayList<>();
    /** Cuántos se han tirado ya, para que los números de una vigilia sigan valiendo. */
    private static long tirados;
    private static boolean arrancada;
    private static String porQueNo;
    /** El objeto del aviso tiene que seguir vivo mientras el enganche exista, o Java se lo lleva y Windows se cae. */
    private static Win32.User32.WinEventProc avisoVivo;
    private static Pointer engancheSistema;
    private static Pointer engancheObjetos;
    private static Thread hilo;

    /** Algo que ha pasado en la pantalla. El título NO se lee aquí: ver la explicación de arriba. */
    public record Cambio(long cuando, int evento, long ventana) {
        /** El título de esa ventana, leído ahora. Puede estar ya cerrada, y entonces viene vacío. */
        public String titulo() {
            Pointer h = new Pointer(ventana);
            return Win32.User32.I.IsWindow(h) ? Win32.titulo(h) : "";
        }

        public Pointer puntero() {
            return new Pointer(ventana);
        }

        /** ¿Sigue existiendo? */
        public boolean viva() {
            return Win32.User32.I.IsWindow(new Pointer(ventana));
        }

        public String comoSeLlama() {
            return switch (evento) {
                case AL_FRENTE -> "al frente";
                case DIALOGO -> "diálogo";
                case CREADO -> "creada";
                case DESTRUIDO -> "destruida";
                case MOSTRADO -> "mostrada";
                case OCULTADO -> "ocultada";
                case FOCO -> "foco";
                case RENOMBRADO -> "renombrada";
                default -> "0x" + Integer.toHexString(evento);
            };
        }
    }

    /**
     * El sitio por el que vas mirando.
     *
     * <p>Se abre ANTES de hacer lo que sea que vaya a provocar el cambio, y así no se pierde nada de lo que pase
     * entre medias: aunque la ventana salga antes de que nos pongamos a esperarla, ya está apuntada.
     */
    public static final class Vigilia {
        private long siguiente;

        private Vigilia(long desde) {
            this.siguiente = desde;
        }
    }

    /** Empieza a mirar desde ahora. */
    public static Vigilia mirar() {
        synchronized (CANDADO) {
            return new Vigilia(tirados + HISTORIAL.size());
        }
    }

    /** ¿Está enganchado a los avisos de Windows? */
    public static boolean disponible() {
        synchronized (CANDADO) {
            return arrancada && engancheObjetos != null;
        }
    }

    /** Por qué no se pudo enganchar, o null si todo bien. */
    public static String porQueNo() {
        synchronized (CANDADO) {
            return porQueNo;
        }
    }

    /**
     * Se engancha a Windows. Se puede llamar las veces que haga falta: solo la primera hace algo.
     *
     * @return true si a partir de ahora se entera de todo sin preguntar.
     */
    public static boolean arrancar() {
        synchronized (CANDADO) {
            if (arrancada) {
                return engancheObjetos != null;
            }
            arrancada = true;
        }
        Thread t = new Thread(Percepcion::bucle, "percepcion");
        t.setDaemon(true);
        t.start();
        // El enganche se hace en ese hilo; se espera un momento a saber si ha salido bien.
        long fin = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < fin) {
            synchronized (CANDADO) {
                if (engancheObjetos != null || porQueNo != null) {
                    hilo = t;
                    return engancheObjetos != null;
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        synchronized (CANDADO) {
            if (porQueNo == null) {
                porQueNo = "Windows no ha contestado al enganche en 3 segundos.";
            }
            return false;
        }
    }

    /**
     * El hilo que se engancha y se queda escuchando.
     *
     * <p>Windows manda los avisos al hilo que se enganchó, y solo mientras ese hilo esté cogiendo mensajes. Por eso
     * esto no puede ser una llamada y ya: tiene que ser un hilo con su bucle de mensajes, para siempre.
     */
    private static void bucle() {
        try {
            Win32.User32.WinEventProc aviso = (enganche, evento, hwnd, idObjeto, idHijo, hiloEvento, cuando) -> {
                // idObjeto 0 es la ventana en sí. Lo demás (el cursor, las barras, cada celda de una rejilla) es
                // ruido a cientos por segundo y no nos dice nada que no sepamos.
                if (idObjeto != 0 || hwnd == null) {
                    return;
                }
                apuntar(new Cambio(System.currentTimeMillis(), evento, Pointer.nativeValue(hwnd)));
            };
            // WINEVENT_OUTOFCONTEXT (0): el aviso llega a nuestro hilo, no se mete nuestro código en los otros
            // programas. Más lento que meterse dentro, pero no puede tirar a nadie, y aquí eso vale más.
            Pointer sistema = Win32.User32.I.SetWinEventHook(AL_FRENTE, DIALOGO, null, aviso, 0, 0, 0);
            Pointer objetos = Win32.User32.I.SetWinEventHook(CREADO, RENOMBRADO, null, aviso, 0, 0, 0);
            synchronized (CANDADO) {
                avisoVivo = aviso;
                engancheSistema = sistema;
                engancheObjetos = objetos;
                if (objetos == null) {
                    porQueNo = "SetWinEventHook no ha enganchado (error " + Win32.Kernel32.I.GetLastError() + ").";
                }
            }
            if (objetos == null) {
                return;
            }
            Win32.MSG msg = new Win32.MSG();
            while (Win32.User32.I.GetMessageW(msg, null, 0, 0) > 0) {
                Win32.User32.I.TranslateMessage(msg);
                Win32.User32.I.DispatchMessageW(msg);
            }
        } catch (Throwable e) {
            synchronized (CANDADO) {
                if (porQueNo == null) {
                    porQueNo = "No se ha podido escuchar a Windows: " + e;
                }
            }
        }
    }

    /**
     * Apunta un cambio a mano. Lo usa el aviso de Windows y las pruebas, que necesitan poder fabricar «ha pasado
     * esto» sin tener una pantalla delante.
     */
    public static void registrar(Cambio c) {
        apuntar(c);
    }

    private static void apuntar(Cambio c) {
        synchronized (CANDADO) {
            HISTORIAL.add(c);
            if (HISTORIAL.size() > RECUERDO) {
                int sobran = HISTORIAL.size() - RECUERDO / 2;
                HISTORIAL.subList(0, sobran).clear();
                tirados += sobran;
            }
            CANDADO.notifyAll();
        }
    }

    /**
     * Espera a que pase algo que encaje, mirando desde donde se abrió la vigilia.
     *
     * @return el primer cambio que encaja, o null si no ha pasado en el tiempo dado.
     */
    public static Cambio esperar(Vigilia v, Predicate<Cambio> encaja, double segundos) {
        long fin = System.currentTimeMillis() + (long) (Math.max(0, segundos) * 1000);
        synchronized (CANDADO) {
            while (true) {
                while (v.siguiente < tirados + HISTORIAL.size()) {
                    int i = (int) Math.max(0, v.siguiente - tirados);
                    Cambio c = HISTORIAL.get(i);
                    v.siguiente = tirados + i + 1;
                    if (encaja.test(c)) {
                        return c;
                    }
                }
                long queda = fin - System.currentTimeMillis();
                if (queda <= 0) {
                    return null;
                }
                // Se espera haya enganche o no. Sin enganche nadie nos despertará antes de tiempo y esto es
                // simplemente dormir hasta el tope, igual que el Thread.sleep que sustituye. Devolver aquí al
                // instante cuando no hay enganche convertía a quien llama (que espera para no martillear UI
                // Automation) en un bucle sin pausa.
                try {
                    CANDADO.wait(Math.min(queda, 250));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
    }

    /**
     * Lo mismo, pero la condición se comprueba también sola de vez en cuando.
     *
     * <p>Es el cinturón y los tirantes: normalmente contesta el aviso de Windows, al instante; pero si el enganche no
     * está o Windows no avisa de algo, {@code logrado} se mira igualmente cada poco y el asistente no se queda ahí
     * plantado. Nunca va peor que preguntando, y casi siempre va mucho mejor.
     *
     * @return true si se logró, false si se acabó el tiempo.
     */
    public static boolean esperarA(java.util.function.BooleanSupplier logrado, double segundos) {
        if (logrado.getAsBoolean()) {
            return true;
        }
        Vigilia v = mirar();
        long fin = System.currentTimeMillis() + (long) (Math.max(0, segundos) * 1000);
        while (System.currentTimeMillis() < fin) {
            double queda = (fin - System.currentTimeMillis()) / 1000.0;
            // Con enganche: se despierta en cuanto Windows avisa (0,4 s es solo el tope). Sin él: duerme 0,25 s.
            esperar(v, c -> true, Math.min(queda, disponible() ? 0.4 : 0.25));
            if (logrado.getAsBoolean()) {
                return true;
            }
        }
        return false;
    }

    /** Los últimos cambios, para contar qué ha pasado cuando algo sale mal. */
    public static List<Cambio> ultimos(int cuantos) {
        synchronized (CANDADO) {
            int desde = Math.max(0, HISTORIAL.size() - cuantos);
            return List.copyOf(HISTORIAL.subList(desde, HISTORIAL.size()));
        }
    }

    /** Cuántos cambios se han visto desde que arrancó. */
    public static long vistos() {
        synchronized (CANDADO) {
            return tirados + HISTORIAL.size();
        }
    }

    // No hay «soltar». UnhookWinEvent solo funciona llamado desde el mismo hilo que enganchó, y ese hilo está
    // parado esperando mensajes para siempre; un método que lo intentara desde fuera fallaría en silencio y
    // parecería que libera. El enganche vive lo que vive el proceso y Windows lo recoge al terminar. El hilo es
    // «daemon», así que no impide que el programa acabe.
}
