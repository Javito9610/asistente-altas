package es.amagna.asistente.jarvis;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * El cerebro de Jarvis como servicio: recibe mensajes de cualquier canal (Telegram, carpeta, ventana…), los entiende, los apunta
 * en el almacén y los hace UNO A UNO (un ordenador tiene un solo ratón). Reglas de seguridad:
 * <ul>
 *   <li>Solo atiende a personas autorizadas; el resto se ignora (y queda dicho en el registro).</li>
 *   <li>Las tareas que el mapa sabe hacer en la Empresa de prueba se hacen directamente; lo «libre» (cualquier otra cosa en el
 *       PC) siempre pide un «sí» antes.</li>
 *   <li>Un trabajo cortado por un apagado no se repite solo.</li>
 * </ul>
 */
public final class Servicio {
    /** Quien hace el trabajo de verdad. Devuelve true si salió bien; el mensaje es lo que se le cuenta a la persona. */
    public interface Hace {
        Resultado hacer(Trabajo t);
    }

    public record Resultado(boolean bien, String mensaje) {
    }

    /** Por dónde se habla con la persona. */
    public interface Salida {
        void enviar(String origen, String quien, String texto);

        /** Entrega un fichero. Por defecto solo dice dónde está. */
        default void fichero(String origen, String quien, java.nio.file.Path f) {
            enviar(origen, quien, "📎 " + f);
        }
    }

    static final String AYUDA = """
            Esto es lo que sé hacer (dímelo con tus palabras):
            📋 MN (por ahora solo en la Empresa de prueba): «da de alta al cliente…», «crea un expediente…», «cuántos expedientes hay» (solo mirar).
            ✉️ Correo: «mándale un correo a… diciendo…» (te enseño el borrador y solo sale con tu «sí»), «léeme los correos nuevos» (solo leo, no marco nada).
            🖥️ Ordenador: «captura» (te mando lo que se ve en pantalla), «limpia la pantalla» (minimiza todo, sin cerrar nada) y «restaura las ventanas»; «abre…», «busca en google…», «pon X en youtube», «busca en maps…» y otras cosas de escritorio (te pido el sí antes; nunca consolas, instaladores ni claves).
            📄 Documentos: «hazme un PDF / Word / Excel de…», «rellena la plantilla…», «lee este PDF» (también puedes MANDARME un fichero por Telegram con lo que quieres que haga), «prepárame un borrador de correo para…» (guardado, sin enviar), «una cita para el lunes a las 10».
            🌐 Internet: «busca en internet…» (solo leer).
            🗂️ Ficheros: «lista / busca / mueve / copia…» dentro de mis carpetas (nada se borra: va a una papelera).
            ⏰ Tiempo: «cada día laborable a las 9, mira…», «recuérdame mañana a las 9 llamar a Ana», «programadas», «quita p123».
            🧠 Memoria: «apunta que…», «notas», «olvida 2».
            🛠️ Control: «estado», «diagnóstico», «pausa» / «sigue».
            Lo que toque dinero o claves, no lo hago; y un correo o mensaje en tu nombre nunca sale sin tu «sí».""";

    private final Almacen almacen;
    private final Interprete interprete;
    private final Hace tareas;
    private final Hace libre;
    private final Salida salida;
    private final Set<String> autorizados;
    private final Consumer<String> registro;
    private volatile boolean pausado;
    private Programadas programadas;
    private Notas notas;
    private java.util.function.Supplier<String> diagnostico;
    private boolean correoListo;
    private String correoDe = "";
    private java.util.function.Supplier<java.nio.file.Path> captura;
    private Abrir abridor;
    private Runnable minimizar;
    private Runnable restaurar;

    /** Despejar el escritorio (minimizar todo) y devolverlo a como estaba. Nunca cierra ventanas. */
    public Servicio conEscritorio(Runnable minimizar, Runnable restaurar) {
        this.minimizar = minimizar;
        this.restaurar = restaurar;
        return this;
    }

    /** Cómo abrir páginas, programas y carpetas sin IA (lo que no sepa abrir pasa a «libre», con confirmación). */
    public Servicio conAbrir(Abrir a) {
        this.abridor = a;
        return this;
    }

    /** Que hay un correo configurado (dirección desde la que se envía). Sin esto, «correo» pide configurarlo. */
    public Servicio conCorreo(String direccion) {
        this.correoListo = direccion != null && !direccion.isBlank();
        this.correoDe = direccion == null ? "" : direccion;
        return this;
    }

    /** Cómo hacer una captura de la pantalla del ordenador (se manda por el chat al pedirla). */
    public Servicio conCaptura(java.util.function.Supplier<java.nio.file.Path> c) {
        this.captura = c;
        return this;
    }
    private java.util.function.Supplier<String> conocimiento = () -> "";

    /** Lo que Jarvis ya sabe hacer solo (tareas libres aprendidas): se le cuenta al intérprete para que las reutilice con los mismos nombres. */
    public Servicio conConocimiento(java.util.function.Supplier<String> c) {
        this.conocimiento = c;
        return this;
    }
    private final Map<String, java.util.ArrayDeque<String>> historial = new java.util.concurrent.ConcurrentHashMap<>();

    /** De dónde sacar los ficheros que hay que entregar al acabar cada encargo (vacía la lista al llamarla). */
    public Servicio conEntregas(java.util.function.Supplier<List<java.nio.file.Path>> entregas) {
        this.entregas = entregas;
        return this;
    }

    private java.util.function.Supplier<List<java.nio.file.Path>> entregas = List::of;

    /** Lo que se cuenta con «diagnóstico»: cómo está Jarvis por dentro (lo prepara quien lo arranca). */
    public Servicio conDiagnostico(java.util.function.Supplier<String> d) {
        this.diagnostico = d;
        return this;
    }

    public Servicio conNotas(Notas n) {
        this.notas = n;
        return this;
    }

    public Programadas programadas() {
        return programadas;
    }

    public Servicio conProgramadas(Programadas p) {
        this.programadas = p;
        return this;
    }

    private static final int MAX_LINEAS = 40;
    private static final int MAX_LINEA = 2500;
    private static final int MAX_CONTEXTO = 7000;
    private java.nio.file.Path carpetaHistorial;

    /** La conversación se guarda en disco: sobrevive a reinicios y apagones, y Jarvis sigue sabiendo de qué se hablaba. */
    public Servicio conHistorial(java.nio.file.Path carpeta) {
        this.carpetaHistorial = carpeta;
        return this;
    }

    private java.nio.file.Path ficheroHistorial(String quien) {
        return carpetaHistorial.resolve(quien.replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
    }

    @SuppressWarnings("unchecked")
    private java.util.ArrayDeque<String> historialDe(String quien) {
        return historial.computeIfAbsent(quien, k -> {
            var h = new java.util.ArrayDeque<String>();
            if (carpetaHistorial != null) {
                try {
                    var f = ficheroHistorial(k);
                    if (java.nio.file.Files.isRegularFile(f)) {
                        var m = es.amagna.asistente.Json.objeto(java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8));
                        for (Object o : (List<Object>) m.getOrDefault("l", List.of())) {
                            h.addLast(String.valueOf(o));
                        }
                    }
                } catch (java.io.IOException | RuntimeException e) {
                    registro.accept("No he podido leer la conversación guardada: " + e.getMessage());
                }
            }
            return h;
        });
    }

    /** Lo que se han dicho con esa persona (largo y guardado): así «el mismo texto» o «ponle también el teléfono» se entienden. */
    private void apuntar(String quien, String linea) {
        var h = historialDe(quien);
        synchronized (h) {
            h.addLast(linea.length() > MAX_LINEA ? linea.substring(0, MAX_LINEA) + "…" : linea);
            while (h.size() > MAX_LINEAS) {
                h.removeFirst();
            }
            if (carpetaHistorial != null) {
                try {
                    java.nio.file.Files.createDirectories(carpetaHistorial);
                    var f = ficheroHistorial(quien);
                    var tmp = f.resolveSibling(f.getFileName() + ".tmp");
                    java.nio.file.Files.writeString(tmp, es.amagna.asistente.Json.escribir(es.amagna.asistente.Json.obj("l", new ArrayList<>(h))),
                            java.nio.charset.StandardCharsets.UTF_8);
                    java.nio.file.Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.io.IOException e) {
                    registro.accept("No he podido guardar la conversación: " + e.getMessage());
                }
            }
        }
    }

    private String contexto(String quien) {
        var h = historialDe(quien);
        synchronized (h) {
            if (h.isEmpty()) {
                return "";
            }
            java.util.ArrayDeque<String> sel = new java.util.ArrayDeque<>();
            int total = 0;
            var it = h.descendingIterator();
            while (it.hasNext()) {
                String l = it.next();
                if (total + l.length() > MAX_CONTEXTO && !sel.isEmpty()) {
                    break;
                }
                total += l.length();
                sel.addFirst(l);
            }
            return "Lo último de la conversación:\n" + String.join("\n", sel);
        }
    }

    private void decir(String origen, String quien, String texto) {
        apuntar(quien, "Jarvis: " + texto);
        salida.enviar(origen, quien, texto);
    }

    /** Lo llama el bucle principal cada pocos segundos: lanza lo programado que toque. */
    public void tic(java.time.LocalDateTime ahora) {
        if (programadas == null) {
            return;
        }
        for (var p : programadas.vencidas(ahora)) {
            registro.accept("Programada " + p.id() + ": " + p.peticion());
            if (p.peticion().startsWith("🔔")) {
                decir(p.origen(), p.quien(), p.peticion());
                continue;
            }
            decir(p.origen(), p.quien(), "⏰ Toca lo programado: " + p.peticion());
            recibir(p.origen(), p.quien(), p.peticion());
        }
    }

    public Servicio(Almacen almacen, Interprete interprete, Hace tareas, Hace libre, Salida salida, Set<String> autorizados,
            Consumer<String> registro) {
        this.almacen = almacen;
        this.interprete = interprete;
        this.tareas = tareas;
        this.libre = libre;
        this.salida = salida;
        this.autorizados = autorizados;
        this.registro = registro;
    }

    /** Al arrancar: avisa de lo que se cortó. */
    public void arrancar() {
        for (Trabajo t : almacen.marcarInterrumpidos()) {
            decir(t.origen(), t.quien(), "⚠️ Se me apagó el ordenador haciendo esto: «" + t.peticion() + "». No lo repito solo por si ya estaba hecho. "
                    + "Mira en MN y, si falta, pídemelo otra vez.");
        }
    }

    public void pausar(boolean si) {
        pausado = si;
    }

    public boolean pausado() {
        return pausado;
    }

    /** Un mensaje de una persona. */
    public void recibir(String origen, String quien, String texto) {
        if (!autorizados.contains(quien)) {
            registro.accept("Mensaje de «" + quien + "» por " + origen + " ignorado: no está autorizado.");
            return;
        }
        String n = norm(texto);
        if (n.isEmpty()) {
            return;
        }
        // Palabras sueltas que no necesitan IA.
        List<Trabajo> esperando = todosEsperando(quien);
        if (!esperando.isEmpty() && Set.of("si", "sí", "vale", "ok", "confirmo", "adelante", "hazlo", "dale").contains(n)) {
            for (Trabajo w : esperando) {
                almacen.guardar(w.con(Trabajo.PENDIENTE, null));
            }
            decir(origen, quien, esperando.size() == 1 ? "Hecho, me pongo con ello." : "Hecho, me pongo con las " + esperando.size() + " cosas, una detrás de otra.");
            return;
        }
        if (!esperando.isEmpty() && Set.of("no", "cancela", "cancelar", "para", "olvidalo").contains(n)) {
            for (Trabajo w : esperando) {
                almacen.guardar(w.con(Trabajo.RECHAZADO, "Cancelado por la persona."));
            }
            decir(origen, quien, "Vale, lo dejo.");
            return;
        }
        if (Set.of("estado", "cola", "que haces", "que tal").contains(n)) {
            decir(origen, quien, estado());
            return;
        }
        if (Set.of("pausa", "pausar").contains(n)) {
            pausado = true;
            decir(origen, quien, "En pausa: no empiezo nada nuevo hasta que me digas «sigue».");
            return;
        }
        if (Set.of("sigue", "continua", "reanuda").contains(n)) {
            pausado = false;
            decir(origen, quien, "Seguimos.");
            return;
        }
        apuntar(quien, "Persona: " + texto);
        if (programadas != null && Set.of("programadas", "agenda", "que hay programado").contains(n)) {
            var l = programadas.todas();
            decir(origen, quien, l.isEmpty() ? "No hay nada programado." : "Programado:\n" + l.stream()
                    .map(p -> "· " + p.id() + " — " + p.hora() + (p.fecha().isEmpty() ? " " + p.dias().size() + " días/sem: " : " el " + p.fecha() + ": ") + p.peticion()).collect(java.util.stream.Collectors.joining("\n"))
                    + "\n(Para quitar uno: «quita p12345».)");
            return;
        }
        if (Set.of("ayuda", "que sabes hacer", "que puedes hacer", "comandos", "help").contains(n)) {
            decir(origen, quien, AYUDA);
            return;
        }
        if (minimizar != null && n.matches(".*\\b(limpia|limpiar|despeja|despejar|minimiza|minimizar|quita|quitar|oculta|ocultar|esconde|esconder|recoge|recoger)\\b.*\\b(pantalla|escritorio|ventanas|todo)\\b.*|.*\\b(muestra|muestrame|enseña|ensename)\\b.*\\bescritorio\\b.*")) {
            if (!almacen.en(Trabajo.EN_CURSO).isEmpty()) {
                decir(origen, quien, "Ahora mismo estoy trabajando en algo en la pantalla; si minimizo todo me estorbo a mí mismo. Dímelo cuando acabe (o di «pausa»).");
            } else {
                try {
                    minimizar.run();
                    decir(origen, quien, "Hecho: he minimizado todas las ventanas (no he cerrado nada). Para volver a ponerlas, di «restaura las ventanas».");
                } catch (RuntimeException ex) {
                    decir(origen, quien, "No he podido despejar la pantalla: " + ex.getMessage());
                }
            }
            return;
        }
        if (restaurar != null && n.matches(".*\\b(restaura|restaurar|recupera|recuperar|devuelve|devolver|vuelve|volver)\\b.*\\b(ventanas|pantalla|escritorio)\\b.*")) {
            try {
                restaurar.run();
                decir(origen, quien, "Hecho: he vuelto a poner las ventanas.");
            } catch (RuntimeException ex) {
                decir(origen, quien, "No he podido restaurar las ventanas: " + ex.getMessage());
            }
            return;
        }
        if (captura != null && pideCaptura(n)) {
            hacerCaptura(origen, quien);
            return;
        }
        if (diagnostico != null && Set.of("diagnostico", "como estas", "revisate", "autodiagnostico", "salud").contains(n)) {
            String d;
            try {
                d = diagnostico.get();
            } catch (RuntimeException e) {
                d = "No he podido revisarme: " + e.getMessage();
            }
            decir(origen, quien, d);
            return;
        }
        if (notas != null && Set.of("notas", "que recuerdas", "que sabes", "que sabes de la oficina").contains(n)) {
            var l = notas.todas();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < l.size(); i++) {
                b.append(i + 1).append(". ").append(l.get(i)).append('\n');
            }
            decir(origen, quien, l.isEmpty() ? "No tengo ninguna nota. Dime «apunta que…» y la guardo." : "Recuerdo:\n" + b + "(Para quitar una: «olvida 2».)");
            return;
        }
        if (notas != null && n.matches("olvida \\d+")) {
            decir(origen, quien, notas.quitar(Integer.parseInt(n.substring(7))) ? "Olvidado." : "No encuentro esa nota.");
            return;
        }
        if (programadas != null && n.matches("quita p\\d+")) {
            decir(origen, quien, programadas.quitar(n.substring(6)) ? "Quitado." : "No encuentro esa.");
            return;
        }
        String saber = conocimiento.get();
        List<Entendido> varios = interprete.entenderVarios(texto, (saber.isBlank() ? "" : saber + "\n") + (notas == null ? "" : notas.comoContexto()) + contexto(quien));
        for (Entendido e : varios) {
            atender(origen, quien, texto, e);
        }
    }

    /** «captura», «pásame una captura de lo que ves en el pc», «pantallazo»…: sin IA, por las palabras clave. */
    static boolean pideCaptura(String n) {
        boolean palabra = n.matches(".*\\b(captura|capturas|pantallazo|screenshot)\\b.*");
        boolean pantalla = n.matches(".*\\b(pantalla|pc|ordenador|escritorio|viendo|ves|ver|foto)\\b.*");
        boolean corto = n.split("\\s+").length <= 3;
        boolean otraCosa = n.matches(".*\\b(cada|programa|programar|guarda|guardar|crea|crear|documento|word|pdf|excel)\\b.*");
        boolean foto = n.matches(".*\\b(foto|imagen)\\b.*") && n.matches(".*\\b(pantalla|pc|ordenador|escritorio)\\b.*");
        return !otraCosa && (palabra && (pantalla || corto) || foto || n.matches("(que ves|que se ve|que hay en pantalla)"));
    }

    private void hacerCaptura(String origen, String quien) {
        try {
            var f = captura.get();
            decir(origen, quien, "Esto es lo que se ve ahora en el ordenador (cuidado: puede salir algo del trabajo).");
            salida.fichero(origen, quien, f);
        } catch (RuntimeException ex) {
            decir(origen, quien, "No he podido hacer la captura: " + ex.getMessage());
        }
    }

    private void atender(String origen, String quien, String texto, Entendido e) {
        switch (e.tipo()) {
            case Entendido.PROGRAMAR -> {
                String id = programadas == null ? null : programadas.anadir(origen, quien, e.params().getOrDefault("hora", ""),
                        e.params().getOrDefault("dias", "todos"), e.texto());
                decir(origen, quien, id == null ? "No he podido programarlo: no entiendo la hora o los días. ¿Me lo repites (por ejemplo «cada día laborable a las 9:00»)?"
                        : "Programado (" + id + "): " + e.params().getOrDefault("hora", "") + " · " + e.params().getOrDefault("dias", "todos") + " → " + e.texto());
            }
            case Entendido.RECORDATORIO -> {
                String id = programadas == null ? null : programadas.anadir(origen, quien, e.params().getOrDefault("hora", ""),
                        e.params().getOrDefault("dias", "hoy"), "🔔 Recordatorio: " + e.texto());
                decir(origen, quien, id == null ? "No he podido apuntar el recordatorio: no entiendo la hora o el día. ¿Me lo repites?"
                        : "Hecho (" + id + "): te lo recordaré " + e.params().getOrDefault("dias", "hoy") + " a las " + e.params().getOrDefault("hora", "") + ": " + e.texto());
            }
            case Entendido.RECORDAR -> {
                String motivo = notas == null ? "No tengo dónde apuntar cosas." : notas.anadir(e.texto());
                decir(origen, quien, motivo == null ? "Apuntado: " + e.texto() : motivo);
            }
            case Entendido.TAREA -> {
                Trabajo t = almacen.nuevo(origen, quien, texto, e.tarea(), e.params(), Trabajo.PENDIENTE);
                decir(origen, quien, "Entendido: «" + e.tarea() + "» (por ahora solo en la Empresa de prueba de MN). " + (e.texto().isBlank() ? "" : e.texto() + " ")
                        + "Te aviso cuando acabe. (" + pendientes() + " en cola)");
                registro.accept("Trabajo " + t.id() + " apuntado: " + e.tarea());
            }
            case Entendido.OFICINA -> {
                almacen.nuevo(origen, quien, texto, "oficina", Map.of("encargo", e.texto(), "_contexto", contexto(quien)), Trabajo.PENDIENTE);
                decir(origen, quien, "Me pongo con ello (documentos y ficheros, sin tocar ninguna ventana): " + e.texto() + " (" + pendientes() + " en cola)");
            }
            case Entendido.CORREO -> {
                if (!correoListo) {
                    decir(origen, quien, "Todavía no tengo ningún correo configurado. Ejecuta guardar-correo.cmd en el ordenador (te pide la dirección y la contraseña de aplicación, cifradas) y reinicia Jarvis.");
                    break;
                }
                Map<String, String> ps = new java.util.LinkedHashMap<>(e.params());
                ps.put("cuerpo", e.texto());
                almacen.nuevo(origen, quien, texto, "correo", ps, Trabajo.ESPERANDO);
                decir(origen, quien, "Voy a enviar este correo, desde " + correoDe + ":\n\nPara: " + ps.get("para") + "\nAsunto: " + ps.get("asunto")
                        + (ps.getOrDefault("adjuntos", "").isBlank() ? "" : "\nAdjuntos: " + ps.get("adjuntos")) + "\n\n" + e.texto()
                        + "\n\n¿Lo envío? (sí / no)");
            }
            case Entendido.CAPTURA -> {
                if (captura == null) {
                    decir(origen, quien, "No puedo hacer capturas en este momento.");
                } else {
                    hacerCaptura(origen, quien);
                }
            }
            case Entendido.ABRIR -> {
                String que = e.params().getOrDefault("que", "");
                String valor = e.params().getOrDefault("valor", "");
                if (abridor != null && abridor.sabe(que, valor)) {
                    decir(origen, quien, abridor.abrir(que, valor));
                } else {
                    // No está en lo que abro sin preguntar: lo hace la vía «libre», pidiendo el sí.
                    Map<String, String> ps = new java.util.LinkedHashMap<>();
                    ps.put("encargo", "Abrir " + (e.texto().isBlank() ? valor : e.texto()) + " (" + que + ": " + valor + ")");
                    almacen.nuevo(origen, quien, texto, "libre", ps, Trabajo.ESPERANDO);
                    decir(origen, quien, "Eso no está en mi lista de cosas que abro sin preguntar. Lo haría en el ordenador a mano: " + ps.get("encargo") + "\n¿Lo hago? (sí / no)");
                }
            }
            case Entendido.CONSULTAR -> {
                almacen.nuevo(origen, quien, texto, "consulta", Map.of("pregunta", e.texto(), "_contexto", contexto(quien)), Trabajo.PENDIENTE);
                decir(origen, quien, "Voy a mirarlo (solo miro, no toco nada)…");
            }
            case Entendido.LIBRE -> {
                Map<String, String> ps = new java.util.LinkedHashMap<>(e.params());
                ps.put("encargo", e.texto());
                ps.put("_contexto", contexto(quien));
                if (!e.tarea().isBlank()) {
                    ps.put("_receta", e.tarea());
                }
                Trabajo t = almacen.nuevo(origen, quien, texto, "libre", ps, Trabajo.ESPERANDO);
                decir(origen, quien, "Voy a hacer esto en el ordenador: " + e.texto() + "\n¿Lo hago? (sí / no)");
                registro.accept("Trabajo " + t.id() + " espera confirmación.");
            }
            default -> decir(origen, quien, e.texto());
        }
    }

    /** Hace el siguiente trabajo pendiente, si hay. Devuelve true si hizo algo. */
    public boolean procesarUno() {
        if (pausado) {
            return false;
        }
        List<Trabajo> pend = almacen.en(Trabajo.PENDIENTE);
        if (pend.isEmpty()) {
            return false;
        }
        Trabajo t = pend.get(0);
        almacen.guardar(t.con(Trabajo.EN_CURSO, null));
        entregas.get(); // lo que quedara de antes no es de este encargo
        Resultado r;
        try {
            r = ("libre".equals(t.tarea()) ? libre : tareas).hacer(t);
        } catch (RuntimeException e) {
            r = new Resultado(false, "Se me ha roto algo por dentro: " + e.getMessage());
        }
        almacen.guardar(t.con(r.bien() ? Trabajo.HECHO : Trabajo.FALLO, r.mensaje()));
        decir(t.origen(), t.quien(), (r.bien() ? "✅ " : "❌ ") + r.mensaje());
        if (r.bien()) {
            for (java.nio.file.Path f : entregas.get()) {
                try {
                    salida.fichero(t.origen(), t.quien(), f);
                } catch (RuntimeException e) {
                    registro.accept("No pude entregar «" + f.getFileName() + "»: " + e.getMessage());
                }
            }
        } else {
            entregas.get();
        }
        registro.accept("Trabajo " + t.id() + (r.bien() ? " hecho" : " con fallo") + ".");
        return true;
    }

    public String estado() {
        List<String> l = new ArrayList<>();
        for (Trabajo t : almacen.todos()) {
            if (!t.acabado()) {
                l.add("· " + t.estado() + ": " + t.peticion());
            }
        }
        List<Trabajo> todos = almacen.todos();
        Trabajo ultimo = todos.stream().filter(Trabajo::acabado).reduce((a, b) -> b).orElse(null);
        return (pausado ? "En pausa. " : "") + (l.isEmpty() ? "No tengo nada pendiente." : "Pendiente:\n" + String.join("\n", l))
                + (ultimo == null ? "" : "\nLo último: " + ultimo.estado() + " — " + ultimo.peticion());
    }

    private int pendientes() {
        return almacen.en(Trabajo.PENDIENTE).size();
    }

    private List<Trabajo> todosEsperando(String quien) {
        List<Trabajo> r = new ArrayList<>();
        for (Trabajo t : almacen.en(Trabajo.ESPERANDO)) {
            if (t.quien().equals(quien)) {
                r.add(t);
            }
        }
        return r;
    }


    static String norm(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[¿?¡!.,]", "").strip();
    }
}
