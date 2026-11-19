package es.amagna.asistente;

import es.amagna.asistente.jarvis.Almacen;
import es.amagna.asistente.jarvis.Barreras;
import es.amagna.asistente.jarvis.CanalCarpeta;
import es.amagna.asistente.jarvis.CanalTelegram;
import es.amagna.asistente.jarvis.Entendido;
import es.amagna.asistente.jarvis.Interprete;
import es.amagna.asistente.jarvis.Programadas;
import es.amagna.asistente.jarvis.Servicio;
import es.amagna.asistente.jarvis.Trabajo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pruebas de Jarvis (servicio, memoria, canales y barreras) sin tocar el ordenador ni la red. */
final class ComprobacionesJarvis {
    private ComprobacionesJarvis() {
    }

    static void ok(boolean c, String que) {
        Comprobaciones.ok(c, que);
    }

    static void jarvis() {
        try {
            barreras();
            interprete();
            almacenYServicio();
            telegram();
            buzon();
            programadas();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void barreras() {
        var rec = new es.amagna.asistente.cerebro.Receta("x", "t", List.of(new es.amagna.asistente.cerebro.Receta.Paso("escribir_en",
                new LinkedHashMap<>(Map.of("texto", "{frase} y {otra}")), null, null)));
        ok(rec.necesarios().equals(List.of("frase", "otra")), "recetas: sabe qué datos necesita");
        ok(es.amagna.asistente.cerebro.Receta.adaptar(List.of("frase"), new LinkedHashMap<>(Map.of("texto", "hola"))).equals(Map.of("frase", "hola")),
                "recetas: si el dato se llama distinto pero solo hay uno, se empareja");
        ok(es.amagna.asistente.cerebro.Receta.adaptar(List.of("frase", "otra"), Map.of("texto", "hola")).equals(Map.of("texto", "hola")),
                "recetas: si no cuadran las cuentas, no se inventa el emparejamiento");
        es.amagna.asistente.manos.Ventanas falsas = (es.amagna.asistente.manos.Ventanas) java.lang.reflect.Proxy.newProxyInstance(
                ComprobacionesJarvis.class.getClassLoader(), new Class<?>[] {es.amagna.asistente.manos.Ventanas.class},
                (proxy, metodo, args) -> metodo.getName().equals("abiertas") ? "- «nota-nueva.txt: Bloc de notas» (notepad) [delante]\n- «Claude» (claude)" : null);
        ok("nota-nueva.txt: Bloc de notas".equals(es.amagna.asistente.cerebro.Receta.tituloReal(falsas, "justificante-prueba.txt: Bloc de notas")),
                "recetas: una ventana de documento vale aunque el documento sea otro (mismo programa)");
        ok(es.amagna.asistente.cerebro.Receta.tituloReal(falsas, "x.txt: Otro programa") == null, "recetas: pero no vale cualquier ventana");
        ok(new es.amagna.asistente.cerebro.Receta.Grabadora().preparar("abrir_programa", new LinkedHashMap<>(Map.of("programa", "notepad.exe")), null) != null,
                "recetas: abrir un programa se graba (si no, la receta aprendida no sabe empezar desde cero)");
        String mnPrueba = "- «MNprogram - Abogados - Empresa de prueba [\\Usu1]» (MNprogram) [delante, con el foco]";
        String mnReal = "- «MNprogram - Abogados - Amagna [\\Usu1]» (MNprogram) [delante, con el foco]";
        String bloc = "- «Bloc de notas» (notepad) [delante, con el foco]";
        ok(Barreras.veto("screenshot", Map.of(), mnReal) == null, "barreras: mirar siempre se puede, incluso en la empresa real");
        ok(Barreras.veto("left_click", Map.of(), mnReal) != null, "barreras: en la empresa real de MN no se pulsa nada");
        ok(Barreras.veto("left_click", Map.of(), mnPrueba) == null, "barreras: en la Empresa de prueba sí");
        ok(Barreras.veto("left_click", Map.of(), bloc) == null, "barreras: fuera de MN no estorban");
        ok(Barreras.veto("abrir_programa", Map.of("programa", "powershell.exe"), bloc) != null, "barreras: no se abre una consola");
        ok(Barreras.veto("abrir_programa", Map.of("programa", "C:\\Windows\\System32\\cmd.exe"), bloc) != null, "barreras: ni con ruta completa");
        ok(Barreras.veto("abrir_programa", Map.of("programa", "C:\\x\\instalar.msi"), bloc) != null, "barreras: ni instaladores ni scripts");
        ok(Barreras.veto("abrir_programa", Map.of("programa", "notepad.exe"), bloc) == null, "barreras: el Bloc de notas sí");
        ok(Barreras.veto("type", Map.of("text", "4111 1111 1111 1111"), bloc) != null, "barreras: un número de tarjeta no se teclea");
        ok(Barreras.veto("type", Map.of("text", "ES91 2100 0418 4502 0005 1332"), bloc) != null, "barreras: un IBAN tampoco");
        ok(Barreras.veto("type", Map.of("text", "Reunión el 12 de marzo a las 10:30, sala 4"), bloc) == null, "barreras: un texto normal sí");
        ok(Barreras.veto("escribir_en", Map.of("control", "campo Contraseña", "valor", "x"), bloc) != null, "barreras: no se rellenan contraseñas");
    }

    static void interprete() {
        var e = Interprete.leer("{\"tipo\":\"tarea\",\"tarea\":\"crear-cliente\",\"params\":{\"cliente_nombre\":\"ANA PRUEBA\",\"cliente_nif\":\"12345678Z\"},\"texto\":\"\"}");
        ok(Entendido.TAREA.equals(e.tipo()) && e.params().get("cliente_nif").equals("12345678Z"), "intérprete: entiende un alta de cliente con sus datos");
        var f = Interprete.leer("{\"tipo\":\"tarea\",\"tarea\":\"crear-cliente\",\"params\":{\"cliente_nombre\":\"ANA\"}}");
        ok(Entendido.PREGUNTAR.equals(f.tipo()) && f.texto().contains("cliente_nif"), "intérprete: si falta el NIF, pregunta y no inventa");
        var g = Interprete.leer("{\"tipo\":\"tarea\",\"tarea\":\"borrar-todo\",\"params\":{}}");
        ok(Entendido.PREGUNTAR.equals(g.tipo()), "intérprete: una tarea que no existe no se ejecuta");
        var h = Interprete.leer("hola, no sé qué decir");
        ok(Entendido.PREGUNTAR.equals(h.tipo()), "intérprete: una respuesta rara de la IA se convierte en pregunta");
        var i = Interprete.leer("Claro:\n```json\n{\"tipo\":\"responder\",\"texto\":\"Son las tres.\"}\n```");
        ok(Entendido.RESPONDER.equals(i.tipo()) && i.texto().equals("Son las tres."), "intérprete: aguanta que la IA envuelva el JSON en texto");
        var j = Interprete.leer("{\"tipo\":\"libre\",\"texto\":\"abrir la calculadora\"}");
        ok(Entendido.LIBRE.equals(j.tipo()), "intérprete: lo que no es del mapa es «libre»");
    }

    /** Un cerebro de mentira que contesta con lo que se le diga. */
    static Interprete interpreteFalso(String[] respuesta) {
        return new Interprete(p -> Map.of("content", List.of(Map.of("type", "text", "text", respuesta[0]))), "m");
    }

    static void almacenYServicio() throws IOException {
        Path dir = Files.createTempDirectory("jarvis");
        var almacen = new Almacen(dir);
        String[] resp = {""};
        List<String> dicho = new ArrayList<>();
        List<String> hecho = new ArrayList<>();
        Servicio.Salida salida = (o, q, t) -> dicho.add(q + ": " + t);
        var servicio = new Servicio(almacen, interpreteFalso(resp), t -> {
            hecho.add(t.tarea() + " " + t.params());
            return new Servicio.Resultado(true, "Cliente creado.");
        }, t -> {
            hecho.add("libre " + t.params().get("encargo"));
            return new Servicio.Resultado(true, "Calculadora abierta.");
        }, salida, Set.of("javi"), x -> { });

        servicio.recibir("telegram", "intruso", "crea un cliente");
        ok(almacen.todos().isEmpty() && dicho.isEmpty(), "servicio: a quien no está autorizado no se le hace caso");

        resp[0] = "{\"tipo\":\"tarea\",\"tarea\":\"crear-cliente\",\"params\":{\"cliente_nombre\":\"ANA PRUEBA\",\"cliente_nif\":\"12345678Z\"}}";
        servicio.recibir("telegram", "javi", "Dame de alta a Ana Prueba, NIF 12345678Z");
        ok(almacen.en(Trabajo.PENDIENTE).size() == 1, "servicio: una tarea del mapa queda apuntada en el almacén");
        ok(servicio.procesarUno() && hecho.size() == 1 && almacen.en(Trabajo.HECHO).size() == 1, "servicio: la hace y la marca como hecha");
        ok(dicho.get(dicho.size() - 1).contains("✅ Cliente creado."), "servicio: avisa del resultado");
        ok(!servicio.procesarUno(), "servicio: sin nada pendiente no hace nada");

        resp[0] = "{\"tipo\":\"libre\",\"texto\":\"abrir la calculadora\"}";
        servicio.recibir("telegram", "javi", "abre la calculadora");
        ok(almacen.en(Trabajo.ESPERANDO).size() == 1 && !servicio.procesarUno(), "servicio: lo «libre» espera un sí y no se hace solo");
        servicio.recibir("telegram", "javi", "Sí");
        ok(servicio.procesarUno() && hecho.get(hecho.size() - 1).startsWith("libre abrir la calculadora"), "servicio: con el sí se hace");

        servicio.recibir("telegram", "javi", "abre la calculadora");
        servicio.recibir("telegram", "javi", "no");
        ok(almacen.en(Trabajo.RECHAZADO).size() == 1 && !servicio.procesarUno(), "servicio: con el no se descarta");

        servicio.pausar(true);
        resp[0] = "{\"tipo\":\"tarea\",\"tarea\":\"crear-cliente\",\"params\":{\"cliente_nombre\":\"B\",\"cliente_nif\":\"1\"}}";
        servicio.recibir("telegram", "javi", "otro cliente");
        ok(!servicio.procesarUno(), "servicio: en pausa no empieza nada nuevo");
        servicio.recibir("telegram", "javi", "estado");
        ok(dicho.get(dicho.size() - 1).contains("En pausa") && dicho.get(dicho.size() - 1).contains("pendiente"), "servicio: «estado» cuenta cómo va, sin gastar IA");
        servicio.pausar(false);

        // Un apagón a mitad de trabajo: al volver se avisa y NO se repite.
        var pend = almacen.en(Trabajo.PENDIENTE).get(0);
        almacen.guardar(pend.con(Trabajo.EN_CURSO, null));
        var otro = new Almacen(dir); // «se reinicia el programa»
        var s2 = new Servicio(otro, interpreteFalso(resp), t -> {
            hecho.add("REPETIDO");
            return new Servicio.Resultado(true, "x");
        }, t -> new Servicio.Resultado(true, "x"), salida, Set.of("javi"), x -> { });
        s2.arrancar();
        ok(otro.leer(pend.id()).estado().equals(Trabajo.FALLO) && !s2.procesarUno() && !hecho.contains("REPETIDO"),
                "servicio: lo que se cortó por un apagón se avisa y no se repite solo");
        ok(dicho.get(dicho.size() - 1).contains("No lo repito solo"), "servicio: y se le cuenta a la persona");

        resp[0] = "{\"tipo\":\"tarea\",\"tarea\":\"crear-cliente\",\"params\":{\"cliente_nombre\":\"C\",\"cliente_nif\":\"2\"}}";
        var s3 = new Servicio(new Almacen(Files.createTempDirectory("jarvis")), interpreteFalso(resp), t -> {
            throw new IllegalStateException("explotó");
        }, t -> new Servicio.Resultado(true, "x"), salida, Set.of("javi"), x -> { });
        s3.recibir("telegram", "javi", "cliente");
        ok(s3.procesarUno() && dicho.get(dicho.size() - 1).contains("❌"), "servicio: si el trabajo revienta, se cuenta y el servicio sigue vivo");
    }

    static void telegram() {
        List<String> urls = new ArrayList<>();
        String[] cola = {"{\"ok\":true,\"result\":[{\"update_id\":10,\"message\":{\"text\":\"estado\",\"from\":{\"id\":777},\"chat\":{\"id\":777,\"type\":\"private\"}}},"
                + "{\"update_id\":11,\"message\":{\"text\":\"hola grupo\",\"from\":{\"id\":5},\"chat\":{\"id\":-9,\"type\":\"group\"}}}]}"};
        CanalTelegram.Red red = url -> {
            urls.add(url);
            if (url.contains("getUpdates")) {
                String r = cola[0];
                cola[0] = "{\"ok\":true,\"result\":[]}";
                return r;
            }
            return "{\"ok\":true}";
        };
        List<String> logs = new ArrayList<>();
        var canal = new CanalTelegram("TOKEN-SECRETO", red, logs::add);
        List<String> recibidos = new ArrayList<>();
        int n = canal.sondear(0, (q, t) -> recibidos.add(q + ":" + t));
        ok(n == 1 && recibidos.equals(List.of("777:estado")), "telegram: solo atiende mensajes de texto de chats privados");
        canal.sondear(0, (q, t) -> recibidos.add("repetido"));
        ok(urls.get(urls.size() - 1).contains("offset=12") && recibidos.size() == 1, "telegram: no vuelve a leer lo ya leído");
        canal.enviar("777", "Hola ñandú & más");
        ok(urls.get(urls.size() - 1).contains("sendMessage") && urls.get(urls.size() - 1).contains("chat_id=777")
                && urls.get(urls.size() - 1).contains("%C3%B1"), "telegram: envía en UTF-8 y bien codificado");
        var caida = new CanalTelegram("TOKEN-SECRETO", url -> {
            throw new IOException("fallo con " + url);
        }, logs::add);
        ok(caida.sondear(0, (q, t) -> { }) == -1 && logs.stream().noneMatch(l -> l.contains("TOKEN-SECRETO")), "telegram: sin red no se rompe y el token nunca sale en el registro");
    }

    static void buzon() throws IOException {
        Path raiz = Files.createTempDirectory("buzon");
        var canal = new CanalCarpeta(raiz);
        ok(canal.sondear((n, t) -> { }) == 0, "buzón: vacío no hace nada");
        Files.writeString(raiz.resolve("entrada").resolve("a.txt"), "estado\n", java.nio.charset.StandardCharsets.UTF_8);
        List<String> v = new ArrayList<>();
        ok(canal.sondear((n, t) -> v.add(n + "=" + t)) == 1 && v.equals(List.of("a=estado")), "buzón: lee un fichero como un mensaje");
        ok(canal.sondear((n, t) -> v.add("otra vez")) == 0, "buzón: y no lo vuelve a leer");
        canal.enviar("respuestas", "hola");
        canal.enviar("respuestas", "adiós");
        ok(Files.readString(raiz.resolve("salida").resolve("respuestas.txt")).equals("hola\nadiós\n"), "buzón: las respuestas se van añadiendo");
    }

    static void programadas() throws IOException {
        Path f = Files.createTempDirectory("prog").resolve("p.json");
        var p = new Programadas(f);
        ok(Programadas.dias("lun-vie").size() == 5 && Programadas.dias("todos").size() == 7 && Programadas.dias("finde").size() == 2
                && Programadas.dias("lunes, miércoles").size() == 2, "programadas: entiende los días en español");
        ok(p.anadir("telegram", "javi", "9:00", "lun-vie", "estado") != null && p.anadir("telegram", "javi", "las nueve", "lun-vie", "x") == null,
                "programadas: acepta una hora buena y rechaza una mala");
        var lunes9 = java.time.LocalDateTime.of(2026, 11, 16, 9, 5);
        ok(p.vencidas(java.time.LocalDateTime.of(2026, 11, 16, 8, 59)).isEmpty(), "programadas: antes de su hora no toca");
        ok(p.vencidas(lunes9).size() == 1 && p.vencidas(lunes9.plusMinutes(10)).isEmpty(), "programadas: toca una vez al día");
        ok(p.vencidas(java.time.LocalDateTime.of(2026, 11, 21, 10, 0)).isEmpty(), "programadas: en sábado no (lun-vie)");
        ok(new Programadas(f).todas().size() == 1 && p.vencidas(java.time.LocalDateTime.of(2026, 11, 17, 12, 0)).size() == 1,
                "programadas: sobrevive a un reinicio y, si estaba apagado a su hora, lo hace al volver ese día");
        // De punta a punta con el servicio: «cada día a las 9 …» se programa y a su hora se pide solo.
        String[] resp = {"{\"tipo\":\"programar\",\"params\":{\"hora\":\"09:00\",\"dias\":\"lun-vie\"},\"texto\":\"estado\"}"};
        List<String> dicho = new ArrayList<>();
        var prog = new Programadas(Files.createTempDirectory("prog2").resolve("p.json"));
        var s = new Servicio(new Almacen(Files.createTempDirectory("alm")), interpreteFalso(resp), t -> new Servicio.Resultado(true, "x"),
                t -> new Servicio.Resultado(true, "x"), (o, q, t) -> dicho.add(t), Set.of("javi"), x -> { }).conProgramadas(prog);
        s.recibir("telegram", "javi", "cada día laborable a las 9 dime cómo vas");
        ok(prog.todas().size() == 1 && dicho.get(dicho.size() - 1).startsWith("Programado ("), "programadas: el servicio programa lo que se le pide");
        s.tic(lunes9);
        ok(dicho.stream().anyMatch(d -> d.contains("Toca lo programado")) && dicho.stream().anyMatch(d -> d.contains("No tengo nada pendiente")),
                "programadas: a su hora se lanza solo");
    }
}
