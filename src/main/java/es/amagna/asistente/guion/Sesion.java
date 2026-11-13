package es.amagna.asistente.guion;

import es.amagna.asistente.manos.Controles;
import es.amagna.asistente.manos.VentanasNativas;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * La sesión: mantener el programa vivo y dentro, sin que haya nadie delante.
 *
 * Esto es lo que convierte la herramienta en un servicio. De noche pasan cosas: MN caduca la sesión, se queda con un
 * aviso encima esperando respuesta, o directamente alguien reinicia el ordenador. Si cuando eso ocurre el asistente
 * se para y espera a que alguien lo arregle por la mañana, no sirve para nada.
 *
 * Antes de cada trabajo se llama a {@link #asegurar}, que comprueba que el programa está vivo, responde y está donde
 * tiene que estar. Si no lo está, lo arregla subiendo escalones, del más suave al más bruto, y sin saltarse ninguno:
 *
 * <ol>
 *   <li>Cerrar avisos y diálogos que hayan quedado encima.</li>
 *   <li>Escape, por si quedó un menú desplegado esperando a que alguien elija.</li>
 *   <li>Cerrar las pantallas que se hayan quedado abiertas de antes.</li>
 *   <li>Si sigue sin responder: cerrar el programa a lo bruto y volver a abrirlo.</li>
 *   <li>Volver a entrar con el usuario y la contraseña (el guion de entrada del mapa de sesión), y comprobar que el
 *       título dice lo que tiene que decir: la empresa correcta.</li>
 * </ol>
 *
 * La contraseña no está aquí ni en ningún fichero del proyecto: el guion de entrada la pide como «secreto:mn» y la
 * descifra Windows en el momento (ver {@link es.amagna.asistente.manos.Secreto}).
 *
 * El mapa de sesión (sesion.json) es un mapa normal, con el programa y los controles de la pantalla de entrada:
 * <pre>
 * {"nombre": "MN", "ventana": "MNprogram", "programa": {"ejecutable": "C:\\…\\PlaJuSQL.exe"},
 *  "controles": {"usuario": ["#txtUsuario"], "clave": ["#txtClave"], "entrar": ["boton:Aceptar"]}}
 * </pre>
 * y el guion de entrada escribe el usuario, escribe «secreto:mn» y pulsa entrar.
 *
 * @package Amagna\Altas
 */
public final class Sesion {
    private final Controles controles;
    private final VentanasNativas ventanas;
    private final Mapa mapa;
    private final Consumer<String> registro;
    private final String debeDecir;
    private Path guionEntrada;

    /**
     * @param debeDecir texto que tiene que aparecer en el título para dar la sesión por buena (por ejemplo
     *                  «Empresa de prueba»). Es la comprobación que impide trabajar en el sitio equivocado.
     */
    public Sesion(Controles controles, VentanasNativas ventanas, Mapa mapa, String debeDecir, Consumer<String> registro) {
        this.controles = controles;
        this.ventanas = ventanas;
        this.mapa = mapa;
        this.debeDecir = debeDecir == null ? "" : debeDecir;
        this.registro = registro;
    }

    public Sesion conGuionDeEntrada(Path guion) {
        this.guionEntrada = guion;
        return this;
    }

    /** Lo que se encontró y lo que hubo que hacer. */
    public record Estado(boolean lista, String comoEsta, int escalon) {
    }

    /** ¿Está el programa vivo, respondiendo y donde tiene que estar? */
    public Estado mirar() {
        String titulo = ventanas.tituloDe(mapa.ventana());
        if (titulo == null) {
            return new Estado(false, "el programa no está abierto", 0);
        }
        if (!debeDecir.isBlank() && !titulo.toLowerCase(Locale.ROOT).contains(debeDecir.toLowerCase(Locale.ROOT))) {
            return new Estado(false, "está abierto pero en el sitio equivocado: el título dice «" + titulo
                    + "» y tiene que decir «" + debeDecir + "»", 0);
        }
        if (ventanas.abiertas().contains("BLOQUEADA")) {
            return new Estado(false, "tiene un aviso o un diálogo encima esperando respuesta", 0);
        }
        // La prueba de verdad: que conteste. Un programa colgado sigue teniendo ventana.
        Boolean contesta = Explorador.conTiempo(12, () -> !controles.arbol(mapa.ventana(), 2).startsWith("No se pueden"), Boolean.FALSE);
        return Boolean.TRUE.equals(contesta) ? new Estado(true, "listo", 0)
                : new Estado(false, "está abierto pero no contesta (colgado)", 0);
    }

    /** Lo deja listo para trabajar, subiendo escalones solo si hace falta. */
    public Estado asegurar() {
        Estado e = mirar();
        if (e.lista()) {
            return e;
        }
        registro.accept("La sesión no está lista: " + e.comoEsta() + ". Arreglándolo…");

        // 1) Avisos y diálogos encima.
        List<String> cerrados = ventanas.cancelarBloqueos();
        if (!cerrados.isEmpty()) {
            registro.accept("  1. cerrados avisos: " + String.join(", ", cerrados));
            e = mirar();
            if (e.lista()) {
                return new Estado(true, "listo (había avisos encima)", 1);
            }
        }

        // 2) Un menú desplegado esperando a que alguien elija.
        escapar();
        e = mirar();
        if (e.lista()) {
            return new Estado(true, "listo (había un menú abierto)", 2);
        }

        // 3) Pantallas que se quedaron abiertas de antes.
        int cerradas = cerrarPantallasSueltas();
        if (cerradas > 0) {
            registro.accept("  3. cerradas " + cerradas + " pantallas que estaban abiertas");
            e = mirar();
            if (e.lista()) {
                return new Estado(true, "listo (quedaban " + cerradas + " pantallas abiertas)", 3);
            }
        }

        // 4) Cerrar a lo bruto y volver a abrir.
        if (mapa.ejecutable().isBlank()) {
            return new Estado(false, e.comoEsta() + ", y el mapa no dice con qué programa se abre: "
                    + "no se puede reiniciar solo. Hay que mirarlo.", 4);
        }
        registro.accept("  4. no responde: se cierra el programa y se vuelve a abrir");
        matar(mapa.ejecutable());
        esperar(3);
        ventanas.abrir(mapa.ejecutable(), mapa.argumentos());
        String apareció = controles.esperar(mapa.ventana(), null, Controles.Que.APAREZCA, null, 120);
        if (!apareció.startsWith("Ya está")) {
            return new Estado(false, "se ha reiniciado el programa pero no ha llegado a abrirse: " + apareció, 4);
        }

        // 5) Volver a entrar.
        if (guionEntrada == null) {
            e = mirar();
            return e.lista() ? new Estado(true, "listo (reiniciado)", 4)
                    : new Estado(false, "reiniciado, pero hay que entrar y no hay guion de entrada: " + e.comoEsta(), 5);
        }
        registro.accept("  5. entrando con el usuario y la contraseña guardada");
        try {
            var parte = new Guion(controles, ventanas, mapa, l -> registro.accept("     " + l)).ejecutar(guionEntrada, false);
            if (!parte.bien()) {
                return new Estado(false, "no se ha podido entrar: " + parte.resumen(), 5);
            }
        } catch (IOException ex) {
            return new Estado(false, "no se ha podido leer el guion de entrada: " + ex.getMessage(), 5);
        }
        e = mirar();
        return e.lista() ? new Estado(true, "listo (reiniciado y vuelto a entrar)", 5)
                : new Estado(false, "se ha entrado pero sigue sin estar listo: " + e.comoEsta(), 5);
    }

    private int cerrarPantallasSueltas() {
        int n = 0;
        for (String linea : ventanas.abiertas().split("\n")) {
            int a = linea.indexOf('«');
            int b = linea.indexOf('»');
            if (a < 0 || b <= a) {
                continue;
            }
            String t = linea.substring(a + 1, b);
            if (t.toLowerCase(Locale.ROOT).contains(mapa.ventana().toLowerCase(Locale.ROOT))) {
                continue; // la principal no se toca
            }
            if (ventanas.mismoProgramaQue(mapa.ventana(), t) && ventanas.cerrarVentana(t) != null) {
                n++;
            }
        }
        return n;
    }

    /** Cerrar el programa a lo bruto. Solo el ejecutable del mapa, nunca nada más. */
    private void matar(String ejecutable) {
        String nombre = Path.of(ejecutable).getFileName().toString();
        try {
            new ProcessBuilder("taskkill.exe", "/F", "/IM", nombre).redirectErrorStream(true).start().waitFor();
        } catch (IOException ex) {
            registro.accept("     (no se ha podido cerrar " + nombre + ": " + ex.getMessage() + ")");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void escapar() {
        try {
            new es.amagna.asistente.manos.ManosRobot(false).tecla("Escape", 2);
            esperar(0.5);
        } catch (RuntimeException ex) {
            // Si no se puede, se sigue al siguiente escalón.
        }
    }

    private static void esperar(double s) {
        try {
            Thread.sleep((long) (s * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
