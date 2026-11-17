package es.amagna.asistente.manos;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Qué puede hacer esta máquina ahora mismo.
 *
 * <h2>El problema que resuelve</h2>
 * El mismo asistente va a correr en sitios que no se parecen en nada: el portátil de Javi con su pantalla delante, y
 * un servidor al que se entra por escritorio remoto y del que te desconectas en cuanto has terminado. Y en el
 * servidor pasa una cosa que confunde a todo el mundo: al cerrar el escritorio remoto, Windows no apaga la sesión,
 * pero deja de dibujarla. A partir de ese momento el programa sigue abierto, sus ventanas siguen existiendo y se las
 * puede seguir manejando por dentro, pero <b>una foto de la pantalla sale negra y el ratón no va a ninguna parte</b>.
 *
 * <p>Un asistente que no sepa esto hace lo peor que puede hacer: cree que ha pulsado y no ha pulsado. Este mira
 * primero dónde está, y cuando el ratón no sirve lo dice en vez de fingir.
 *
 * <h2>Las tres preguntas</h2>
 * <ol>
 *   <li><b>¿Hay sesión de verdad?</b> Un servicio de Windows corre en la sesión 0, donde no hay escritorio ni
 *       ventanas. Por eso el asistente se instala como tarea al iniciar sesión y no como servicio.</li>
 *   <li><b>¿Está la sesión conectada?</b> Si está desconectada o bloqueada, hay ventanas pero nadie las dibuja.</li>
 *   <li><b>¿Hay escritorio de entrada?</b> Es el que recibe el ratón y el teclado. Sin él, el ratón no existe.</li>
 * </ol>
 *
 * <p>Cuando la respuesta a las dos últimas es que no, el asistente <b>sigue trabajando igual</b>: entra en MN, lee
 * las fichas y las rellena, porque todo eso se hace hablando con las ventanas, no tocándolas. Lo único que pierde es
 * el ratón de último recurso y la foto de pantalla completa, y para la foto hay otra forma
 * ({@code PrintWindow}, que se la pide a la ventana) que sí funciona ahí.
 *
 * @package Amagna\Manos
 */
public final class Entorno {
    private Entorno() {
    }

    /** El escritorio puede recibir ratón y teclado. */
    private static final int DESKTOP_SWITCHDESKTOP = 0x0100;
    /** ¿Estamos dentro de una sesión de escritorio remoto? */
    private static final int SM_REMOTESESSION = 0x1000;
    /** Lo que se le pregunta a Windows sobre la sesión: en qué estado está. */
    private static final int WTS_CONNECT_STATE = 8;
    private static final Pointer ESTE_SERVIDOR = null;

    public interface Wtsapi32 extends StdCallLibrary {
        Wtsapi32 I = Native.load("wtsapi32", Wtsapi32.class);

        boolean WTSQuerySessionInformationW(Pointer servidor, int sesion, int queSeQuiere, PointerByReference buffer,
                IntByReference bytes);

        void WTSFreeMemory(Pointer memoria);
    }

    public interface User32Extra extends StdCallLibrary {
        User32Extra I = Native.load("user32", User32Extra.class);

        Pointer OpenInputDesktop(int opciones, boolean heredar, int acceso);

        boolean CloseDesktop(Pointer escritorio);

        int GetSystemMetrics(int indice);
    }

    public interface Kernel32Extra extends StdCallLibrary {
        Kernel32Extra I = Native.load("kernel32", Kernel32Extra.class);

        int GetCurrentProcessId();

        boolean ProcessIdToSessionId(int pid, IntByReference sesion);
    }

    /** En qué estado está la sesión de Windows en la que corremos. */
    public enum Sesion {
        ACTIVA("la sesión está conectada y a la vista"),
        DESCONECTADA("la sesión sigue abierta pero nadie está conectado a ella"),
        BLOQUEADA("la pantalla está bloqueada"),
        SIN_ESCRITORIO("no hay escritorio: esto es la sesión 0, la de los servicios"),
        NO_SE_SABE("no se ha podido averiguar");

        public final String explicacion;

        Sesion(String explicacion) {
            this.explicacion = explicacion;
        }
    }

    /** Lo que se puede y lo que no, aquí y ahora. */
    public record Estado(Sesion sesion, boolean escritorioDeEntrada, boolean escritorioRemoto, int numeroDeSesion,
            boolean javaSinPantalla) {

        /** Hablar con las ventanas por dentro. Esto va casi siempre, y es como trabaja el asistente. */
        public boolean puedeManejarVentanas() {
            return sesion != Sesion.SIN_ESCRITORIO;
        }

        /** Mover el ratón y teclear de verdad. Necesita un escritorio que reciba la entrada. */
        public boolean puedeUsarRaton() {
            return escritorioDeEntrada && !javaSinPantalla && sesion == Sesion.ACTIVA;
        }

        /** Fotografiar la pantalla entera. Si no, hay que pedirle su dibujo a cada ventana. */
        public boolean puedeFotografiarLaPantalla() {
            return puedeUsarRaton();
        }

        /** En una frase, para que quien lea el registro sepa dónde está. */
        public String enUnaFrase() {
            if (!puedeManejarVentanas()) {
                return "Aquí no se puede trabajar: " + sesion.explicacion + ".";
            }
            if (puedeUsarRaton()) {
                return "Todo disponible: " + sesion.explicacion + ".";
            }
            return "Se trabaja por dentro, sin ratón: " + sesion.explicacion
                    + ". Las ventanas se manejan igual; lo que no hay es ratón ni foto de la pantalla.";
        }
    }

    /** Mira dónde estamos. */
    public static Estado mirar() {
        int sesion = -1;
        try {
            IntByReference n = new IntByReference();
            if (Kernel32Extra.I.ProcessIdToSessionId(Kernel32Extra.I.GetCurrentProcessId(), n)) {
                sesion = n.getValue();
            }
        } catch (Throwable e) {
            sesion = -1;
        }

        boolean entrada = false;
        try {
            Pointer d = User32Extra.I.OpenInputDesktop(0, false, DESKTOP_SWITCHDESKTOP);
            if (d != null) {
                entrada = true;
                User32Extra.I.CloseDesktop(d);
            }
        } catch (Throwable e) {
            entrada = false;
        }

        boolean remoto = false;
        try {
            remoto = User32Extra.I.GetSystemMetrics(SM_REMOTESESSION) != 0;
        } catch (Throwable e) {
            remoto = false;
        }

        boolean sinPantalla = true;
        try {
            sinPantalla = java.awt.GraphicsEnvironment.isHeadless();
        } catch (Throwable e) {
            sinPantalla = true;
        }

        return new Estado(deducir(sesion, entrada, estadoDeLaSesion(sesion)), entrada, remoto, sesion, sinPantalla);
    }

    /**
     * Qué dice Windows del estado de la sesión: 0 activa, 1 conectando, 4 desconectada, 5 bloqueada…
     *
     * @return el número que da Windows, o -1 si no contesta.
     */
    static int estadoDeLaSesion(int sesion) {
        if (sesion < 0) {
            return -1;
        }
        PointerByReference buffer = new PointerByReference();
        IntByReference bytes = new IntByReference();
        try {
            if (!Wtsapi32.I.WTSQuerySessionInformationW(ESTE_SERVIDOR, sesion, WTS_CONNECT_STATE, buffer, bytes)
                    || buffer.getValue() == null || bytes.getValue() < 4) {
                return -1;
            }
            int estado = buffer.getValue().getInt(0);
            Wtsapi32.I.WTSFreeMemory(buffer.getValue());
            return estado;
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * De los tres datos sueltos a una respuesta.
     *
     * <p>Se separa de {@link #mirar()} para poder comprobarla sin estar en Windows: lo que importa de verdad no es
     * leer los números, es no equivocarse al juntarlos.
     *
     * @param sesion número de sesión de Windows, o -1 si no se sabe.
     * @param escritorioDeEntrada si hay escritorio que reciba ratón y teclado.
     * @param estadoWindows lo que contesta Windows: 0 activa, 4 desconectada, 5 bloqueada, -1 no se sabe.
     */
    public static Sesion deducir(int sesion, boolean escritorioDeEntrada, int estadoWindows) {
        if (sesion == 0) {
            return Sesion.SIN_ESCRITORIO;
        }
        if (estadoWindows == 4) {
            return Sesion.DESCONECTADA;
        }
        if (estadoWindows == 5) {
            return Sesion.BLOQUEADA;
        }
        if (estadoWindows == 0) {
            // Windows dice que está activa; si además no hay escritorio de entrada, es que está bloqueada.
            return escritorioDeEntrada ? Sesion.ACTIVA : Sesion.BLOQUEADA;
        }
        if (escritorioDeEntrada) {
            return Sesion.ACTIVA;
        }
        return Sesion.NO_SE_SABE;
    }

    /** Lo que se imprime con «asistente entorno». */
    public static String informe() {
        Estado e = mirar();
        StringBuilder b = new StringBuilder("DÓNDE ESTAMOS\n");
        b.append("  ").append(e.enUnaFrase()).append('\n');
        b.append("  sesión de Windows: ").append(e.numeroDeSesion() < 0 ? "no se sabe" : e.numeroDeSesion()).append('\n');
        b.append("  escritorio remoto: ").append(e.escritorioRemoto() ? "sí" : "no").append('\n');
        b.append("  escritorio de entrada (ratón y teclado): ").append(e.escritorioDeEntrada() ? "sí" : "no").append('\n');
        b.append("\nQUÉ SE PUEDE HACER\n");
        b.append("  manejar ventanas por dentro: ").append(si(e.puedeManejarVentanas())).append('\n');
        b.append("  ratón y teclado de verdad: ").append(si(e.puedeUsarRaton())).append('\n');
        b.append("  foto de la pantalla entera: ").append(si(e.puedeFotografiarLaPantalla())).append('\n');
        b.append("  dibujo de una ventana suelta: sí (se le pide a la ventana, va aunque esté tapada)\n");
        b.append("\nAVISOS DE WINDOWS\n");
        b.append("  enganchado: ").append(si(Percepcion.disponible()));
        if (!Percepcion.disponible() && Percepcion.porQueNo() != null) {
            b.append("  (").append(Percepcion.porQueNo()).append(')');
        }
        b.append('\n');
        if (Percepcion.disponible()) {
            b.append("  cambios vistos hasta ahora: ").append(Percepcion.vistos()).append('\n');
        }
        return b.toString();
    }

    private static String si(boolean b) {
        return b ? "sí" : "no";
    }
}
