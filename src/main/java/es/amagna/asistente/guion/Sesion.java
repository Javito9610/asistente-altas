package es.amagna.asistente.guion;

import es.amagna.asistente.manos.Controles;
import es.amagna.asistente.manos.VentanasNativas;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private Mapa mapaEntrada;
    private Guion.Rescate rescate;

    /**
     * @param debeDecir texto que tiene que aparecer en el título para dar la sesión por buena (por ejemplo
     *                  «Empresa de prueba»). Es la comprobación que impide trabajar en el sitio equivocado.
     */
    public Sesion(Controles controles, VentanasNativas ventanas, Mapa mapa, String debeDecir, Consumer<String> registro) {
        this.controles = controles;
        this.ventanas = ventanas;
        this.mapa = mapa;
        // Si no se dice otra cosa, manda lo que el propio mapa exija. Para MN es «Empresa de prueba»: si el programa
        // arrancara en otra empresa, la sesión NO se da por buena y no se trabaja. Es el cerrojo que impide tocar la
        // empresa de verdad por un descuido.
        this.debeDecir = debeDecir == null || debeDecir.isBlank() ? mapa.debeDecir() : debeDecir;
        this.registro = registro;
    }

    public Sesion conGuionDeEntrada(Path guion) {
        this.guionEntrada = guion;
        return this;
    }

    /**
     * La IA de guardia para entrar. Un guion no puede adivinar TODAS las pantallas que un programa de gestión decide
     * enseñar un día cualquiera: un aviso nuevo, una pregunta que no estaba, una actualización. Para eso está ella:
     * mira lo que hay, lo resuelve, y el guion sigue por donde iba.
     */
    public Sesion conRescate(Guion.Rescate r) {
        this.rescate = r;
        return this;
    }

    /** La pantalla de entrada (usuario y contraseña) con su propio mapa y el guion que la rellena. */
    public Sesion conEntrada(Mapa mapaEntrada, Path guion) {
        this.mapaEntrada = mapaEntrada;
        this.guionEntrada = guion;
        return this;
    }

    /** Toma la pantalla de entrada del propio mapa, si la trae apuntada. */
    public Sesion conEntradaDelMapa() throws IOException {
        if (mapa.entradaMapa() != null && mapa.entradaGuion() != null) {
            return conEntrada(Mapa.leer(mapa.entradaMapa()), mapa.entradaGuion());
        }
        return this;
    }

    /** Deja escrito todo lo que hay en la pantalla de entrada, para poder corregir el mapa sin adivinar. */
    private String diagnostico() {
        // Se vuelca TODO lo que hay abierto, no solo la pantalla de entrada: cuando un trabajo pasa por cuatro
        // ventanas seguidas, no se sabe de antemano en cuál se va a atascar, y pedir otra vuelta entera para ver la
        // siguiente es perder el tiempo de quien espera.
        try {
            StringBuilder b = new StringBuilder();
            int cuantas = 0;
            for (Map.Entry<Long, String> e : VentanasNativas.instantanea().entrySet()) {
                String t = e.getValue();
                if (t.isBlank() || t.contains("asistente") || t.contains("Símbolo del sistema") || cuantas >= 8) {
                    continue;
                }
                b.append("\n========== ventana: «").append(t).append("» ==========\n")
                        .append(controles.arbol(t, 12, 1200)).append('\n');
                cuantas++;
            }
            Path f = Path.of("diagnostico-entrada.txt");
            Files.writeString(f, "Lo que hay abierto cuando se ha atascado:\n" + b, java.nio.charset.StandardCharsets.UTF_8);
            return "\n   He dejado TODAS las ventanas abiertas (" + cuantas + ") en " + f.toAbsolutePath() + ".";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** ¿Está el programa pidiendo usuario y contraseña ahora mismo? */
    public boolean pideEntrar() {
        return mapaEntrada != null && ventanas.handleDe(mapaEntrada.ventana()) != null;
    }

    /** Rellena la pantalla de entrada y espera a que aparezca la principal. */
    private String entrar() {
        if (mapaEntrada == null || guionEntrada == null) {
            return "está pidiendo usuario y contraseña y no hay nada configurado para entrar solo";
        }
        try {
            Guion g = new Guion(controles, ventanas, mapaEntrada, l -> registro.accept("     " + l));
            if (rescate != null) {
                g.conRescate(rescate);
            }
            var parte = g.ejecutar(guionEntrada, false);
            if (!parte.bien()) {
                // Si no ha encontrado un campo, lo útil no es rendirse: es dejar escrito QUÉ hay en esa pantalla,
                // para poder corregir el mapa en un minuto en vez de ir adivinando.
                return "no se ha podido entrar: " + parte.resumen() + diagnostico();
            }
        } catch (IOException e) {
            return "no se ha podido leer el guion de entrada: " + e.getMessage();
        }
        // Al entrar, los programas de gestión sueltan lo que tengan pendiente: avisos, configuraciones, novedades.
        // Nada de eso lo hemos pedido nosotros, así que se cancela y se sigue; queda apuntado por si interesa.
        long fin = System.currentTimeMillis() + 120_000;
        List<String> apartados = new ArrayList<>();
        while (System.currentTimeMillis() < fin) {
            if (ventanas.handleDe(mapa.ventana()) != null && !ventanas.abiertas().contains("BLOQUEADA")) {
                if (!apartados.isEmpty()) {
                    registro.accept("     (se han apartado " + apartados.size() + " ventanas que salieron al entrar: "
                            + String.join(", ", apartados) + ")");
                }
                return null;
            }
            List<String> cerrados = ventanas.cancelarBloqueos();
            if (!cerrados.isEmpty()) {
                apartados.addAll(cerrados);
                registro.accept("     apartando lo que ha salido al entrar: " + String.join(", ", cerrados));
            }
            esperar(1);
        }
        return "se ha entrado pero la ventana principal no ha llegado a estar libre en 2 minutos"
                + (apartados.isEmpty() ? "" : " (se apartaron: " + String.join(", ", apartados) + ")");
    }

    /** Lo que se encontró y lo que hubo que hacer. */
    public record Estado(boolean lista, String comoEsta, int escalon) {
    }

    /** ¿Está el programa vivo, respondiendo y donde tiene que estar? */
    public Estado mirar() {
        // Lo primero: ¿está pidiendo usuario y contraseña? Es lo que pasa cuando caduca la sesión, y no hay que
        // reiniciar nada: hay que entrar.
        if (pideEntrar()) {
            return new Estado(false, "está pidiendo usuario y contraseña", 0);
        }
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

        // 0) Si solo está pidiendo entrar, se entra. No hay que reiniciar nada.
        if (pideEntrar()) {
            registro.accept("  0. está en la pantalla de entrada: escribiendo usuario y contraseña");
            String fallo = entrar();
            if (fallo != null) {
                return new Estado(false, fallo, 0);
            }
            e = mirar();
            if (e.lista()) {
                return new Estado(true, "listo (ha vuelto a entrar)", 0);
            }
        }

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

        // Al arrancar puede salir la principal o la pantalla de entrada: se espera a cualquiera de las dos.
        registro.accept("     esperando a que arranque…");
        long fin = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < fin) {
            if (pideEntrar()) {
                registro.accept("  5. pide usuario y contraseña: entrando");
                String fallo = entrar();
                if (fallo != null) {
                    return new Estado(false, fallo, 5);
                }
                break;
            }
            if (ventanas.tituloDe(mapa.ventana()) != null) {
                break;
            }
            esperar(1);
        }
        e = mirar();
        if (e.lista()) {
            return new Estado(true, "listo (reiniciado" + (guionEntrada == null ? "" : " y vuelto a entrar") + ")", 5);
        }
        return new Estado(false, pideEntrar()
                ? "el programa pide usuario y contraseña y no hay nada configurado para entrar solo: "
                  + "pon el bloque «entrada» en el mapa y guarda la contraseña con «asistente secreto»"
                : "se ha reiniciado pero sigue sin estar listo: " + e.comoEsta(), 5);
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
