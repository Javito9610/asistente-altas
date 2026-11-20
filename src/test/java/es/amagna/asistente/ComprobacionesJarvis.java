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
import java.util.Arrays;
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
            oficina();
            telegramPorHttp();
            consumo();
            oficinaSinConfirmar();
            variasCosas();
            notasYRecordatorios();
            ficheros();
            web();
        } catch (Exception e) {
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
        var c = Interprete.leer("{\"tipo\":\"consultar\",\"texto\":\"cuántos expedientes hay\"}");
        ok(Entendido.CONSULTAR.equals(c.tipo()) && c.texto().contains("expedientes"), "intérprete: una pregunta sobre lo que se ve en MN es «consultar»");
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
        // Entrega de ficheros: lo creado se manda al terminar, y por Telegram va como documento.
        List<String> subidos = new ArrayList<>();
        var conSubida = new CanalTelegram("TOKEN-SECRETO", new CanalTelegram.Red() {
            @Override
            public String get(String url) {
                return "{\"ok\":true}";
            }

            @Override
            public String subir(String url, java.util.Map<String, String> campos, String campo, Path f) {
                subidos.add(url.substring(url.lastIndexOf('/') + 1) + "|" + campos.get("chat_id") + "|" + campo + "|" + f.getFileName());
                return "{\"ok\":true}";
            }
        }, logs::add);
        conSubida.enviarFichero("777", Path.of("/x/informe.pdf"));
        ok(subidos.equals(List.of("sendDocument|777|document|informe.pdf")), "telegram: manda un fichero como documento");
        try {
            Path carp = Files.createTempDirectory("entr");
            var fi = new es.amagna.asistente.jarvis.Ficheros(List.of(carp));
            fi.escribirBytes(carp + "/a.pdf", new byte[] {1}, false);
            fi.escribir(carp + "/b.txt", "hola", false);
            Files.writeString(carp.resolve("c.txt"), "viejo");
            fi.entregar(carp + "/c.txt");
            boolean raro = false;
            try {
                fi.entregar("/etc/passwd");
            } catch (IllegalArgumentException e) {
                raro = true;
            }
            var l = fi.drenarEntregas();
            ok(l.size() == 3 && raro && fi.drenarEntregas().isEmpty(), "ficheros: lo creado y lo pedido se entrega una vez; fuera de las carpetas, no");
            Files.writeString(carp.resolve("clave.txt"), "x");
            Files.createDirectories(carp.resolve("secretos"));
            Files.writeString(carp.resolve("secretos").resolve("mn.dat"), "x");
            boolean secreto = false;
            for (String r0 : List.of("/clave.txt", "/secretos/mn.dat")) {
                try {
                    fi.entregar(carp + r0);
                } catch (IllegalArgumentException e) {
                    secreto = e.getMessage().contains("secretos") || secreto;
                }
            }
            ok(secreto && fi.drenarEntregas().stream().noneMatch(x -> x.toString().contains("clave") || x.toString().contains("mn.dat")), "ficheros: no manda por el chat claves ni secretos");
            List<String> eco = new ArrayList<>();
            var sv = new Servicio(new Almacen(Files.createTempDirectory("alm")), new Interprete(p0 -> Map.of("content", List.of(Map.of("type", "text", "text", "{\"tipo\":\"libre\",\"tarea\":\"x\",\"texto\":\"haz\"}"))), "m"),
                    t -> new Servicio.Resultado(true, "ok"), t -> {
                        try {
                            fi.escribir(carp + "/d.txt", "d", false);
                        } catch (IOException e) {
                            return new Servicio.Resultado(false, "x");
                        }
                        return new Servicio.Resultado(true, "hecho");
                    }, new Servicio.Salida() {
                        @Override
                        public void enviar(String o, String q, String texto) {
                            eco.add(texto);
                        }

                        @Override
                        public void fichero(String o, String q, Path f) {
                            eco.add("FICHERO " + f.getFileName());
                        }
                    }, Set.of("javi"), x -> { }).conEntregas(fi::drenarEntregas);
            sv.recibir("telegram", "javi", "haz algo");
            sv.recibir("telegram", "javi", "sí");
            sv.procesarUno();
            ok(eco.contains("FICHERO d.txt") && eco.stream().noneMatch(x -> x.equals("FICHERO a.pdf")), "servicio: al acabar bien entrega solo lo creado en ese encargo");
        } catch (IOException e) {
            ok(false, "entrega de ficheros: " + e);
        }
    }

    /** Telegram de verdad por HTTP, contra un servidor local que lo imita: comprueba el multipart y el sondeo reales. */
    static void telegramPorHttp() throws Exception {
        var visto = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var servidor = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", ex -> {
            byte[] cuerpo = ex.getRequestBody().readAllBytes();
            String ruta = ex.getRequestURI().getPath();
            String texto = new String(cuerpo, java.nio.charset.StandardCharsets.UTF_8);
            visto.add(ruta + "|" + ex.getRequestMethod() + "|" + ex.getRequestHeaders().getFirst("Content-Type") + "|" + texto.replace("\r\n", "/")
                    + "|" + (ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery()));
            byte[] r;
            if (ruta.endsWith("getUpdates")) {
                r = ("{\"ok\":true,\"result\":[{\"update_id\":1,\"message\":{\"text\":\"hola\",\"from\":{\"id\":42},\"chat\":{\"id\":42,\"type\":\"private\"}}},"
                        + "{\"update_id\":2,\"message\":{\"caption\":\"resúmelo\",\"document\":{\"file_id\":\"F1\",\"file_name\":\"../../contrato final.pdf\",\"file_size\":14},\"from\":{\"id\":42},\"chat\":{\"id\":42,\"type\":\"private\"}}},"
                        + "{\"update_id\":3,\"message\":{\"document\":{\"file_id\":\"F2\",\"file_name\":\"x.pdf\",\"file_size\":5},\"from\":{\"id\":99},\"chat\":{\"id\":99,\"type\":\"private\"}}}]}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else if (ruta.endsWith("getMe")) {
                r = "{\"ok\":true,\"result\":{\"username\":\"jarvis_bot\",\"first_name\":\"Jarvis\"}}".getBytes();
            } else if (ruta.endsWith("getFile")) {
                r = "{\"ok\":true,\"result\":{\"file_path\":\"docs/f.pdf\"}}".getBytes();
            } else if (ruta.startsWith("/file/botT0KEN/")) {
                r = "%PDF-contenido".getBytes();
            } else {
                r = "{\"ok\":true}".getBytes();
            }
            ex.sendResponseHeaders(200, r.length);
            ex.getResponseBody().write(r);
            ex.close();
        });
        servidor.start();
        try {
            Path carp = Files.createTempDirectory("recib");
            var fich = new es.amagna.asistente.jarvis.Ficheros(List.of(carp));
            var canal = new CanalTelegram("T0KEN", CanalTelegram.redReal(), x -> { }).conBase("http://127.0.0.1:" + servidor.getAddress().getPort())
                    .conRecepcion(u -> u.equals("42"), (nombre, datos) -> {
                        try {
                            return fich.recibir(nombre, datos);
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    });
            List<String> recibidos = new ArrayList<>();
            canal.sondear(0, (q, t) -> recibidos.add(q + ":" + t));
            ok(canal.quienSoy().equals("@jarvis_bot (Jarvis)"), "telegram por HTTP: «getMe» dice cómo se llama el bot");
            ok(recibidos.size() == 2 && recibidos.get(0).equals("42:hola"), "telegram por HTTP: el sondeo real recibe el mensaje");
            ok(recibidos.get(1).startsWith("42:He recibido el fichero «") && recibidos.get(1).contains("recibidos") && recibidos.get(1).endsWith("resúmelo"),
                    "telegram por HTTP: un documento de una persona autorizada se baja y se avisa a Jarvis con su pie de foto");
            Path guardado = Path.of(recibidos.get(1).substring(recibidos.get(1).indexOf('«') + 1, recibidos.get(1).indexOf('»')));
            ok(guardado.startsWith(carp) && guardado.getFileName().toString().endsWith("contrato final.pdf") && !guardado.getFileName().toString().contains("..")
                    && Files.readString(guardado).equals("%PDF-contenido"), "telegram por HTTP: se guarda dentro de la carpeta permitida, con el nombre limpio y su contenido");
            ok(visto.stream().noneMatch(v -> v.contains("F2")), "telegram por HTTP: de quien no está autorizado no se baja nada");
            Path f = Files.createTempFile("doc", ".pdf");
            Files.write(f, "%PDF-contenido".getBytes());
            canal.enviarFichero("42", f);
            String envio = visto.stream().filter(v -> v.contains("sendDocument")).findFirst().orElse("");
            ok(envio.startsWith("/botT0KEN/sendDocument|POST|multipart/form-data; boundary=") && envio.contains("name=\"chat_id\"//42")
                    && envio.contains("name=\"document\"; filename=\"" + f.getFileName() + "\"") && envio.contains("%PDF-contenido"),
                    "telegram por HTTP: el fichero sube como multipart con su chat y su nombre");
        } finally {
            servidor.stop(0);
        }
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

    static void oficina() throws IOException {
        Path d = Files.createTempDirectory("ofi");
        var f = new es.amagna.asistente.jarvis.Ficheros(List.of(d));
        var o = new es.amagna.asistente.jarvis.Oficina(f);
        java.util.Map<String, Object> in = new java.util.HashMap<>();
        in.put("titulo", "Informe ñ");
        List<String> par = new ArrayList<>(List.of("# Uno", "Texto áéí (con) \\ & <x>", "- viñeta"));
        for (int i = 0; i < 90; i++) {
            par.add("Párrafo largo " + i + " para forzar varias páginas en el PDF de comprobación, con bastante texto para que ajuste líneas.");
        }
        in.put("parrafos", par);
        o.crear("pdf", d + "/a", in);
        byte[] pdf = Files.readAllBytes(d.resolve("a.pdf"));
        String cab = new String(pdf, 0, 8, java.nio.charset.StandardCharsets.ISO_8859_1);
        String pie = new String(pdf, pdf.length - 6, 6, java.nio.charset.StandardCharsets.ISO_8859_1);
        ok(cab.startsWith("%PDF-1.4") && pie.contains("%%EOF") && new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1).contains("/Count 4"),
                "oficina: el PDF se genera con cabecera, pie y varias páginas");
        o.crear("docx", d + "/a.docx", in);
        String dx = o.leerDocumento(d + "/a.docx");
        ok(dx.contains("Informe ñ") && dx.contains("Texto áéí (con) \\ & <x>") && dx.contains("•  viñeta"), "oficina: el Word se escribe y se vuelve a leer igual");
        in.put("hoja", "Clientes");
        in.put("filas", List.of(List.of("Nombre", "Importe"), List.of("Ana & Co", 12.5), List.of("=1+1", 3L)));
        o.crear("xlsx", d + "/a.xlsx", in);
        String xl = o.leerDocumento(d + "/a.xlsx");
        ok(xl.contains("Ana & Co\t12.5") && xl.contains("=1+1\t3"), "oficina: el Excel se escribe y se vuelve a leer (y «=» queda como texto)");
        // Plantilla Word con el campo partido en dos trozos (lo que hace Word de verdad) y un campo sin dato.
        String cab0 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>";
        String cuerpo0 = "<w:p><w:r><w:t xml:space=\"preserve\">Cliente: {{nom</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>bre}} con NIF {{ nif }} y {{falta}}</w:t></w:r></w:p></w:body></w:document>";
        var zb = new java.io.ByteArrayOutputStream();
        try (var z = new java.util.zip.ZipOutputStream(zb)) {
            z.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml"));
            z.write("<Types/>".getBytes());
            z.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            z.write((cab0 + cuerpo0).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Files.write(d.resolve("plantilla.docx"), zb.toByteArray());
        java.util.Map<String, Object> datos = new java.util.HashMap<>();
        datos.put("nombre", "ANA & HIJOS <SL>");
        datos.put("NIF", "12345678Z");
        String rr = o.rellenar(d + "/plantilla.docx", d + "/contrato", datos, false);
        String lleno = o.leerDocumento(d + "/contrato.docx");
        ok(lleno.contains("Cliente: ANA & HIJOS <SL> con NIF 12345678Z y {{falta}}") && rr.contains("sin rellenar") && rr.contains("falta"),
                "oficina: rellena la plantilla (campo partido, mayúsculas, caracteres raros) y avisa del campo sin dato");
        ok(o.leerDocumento(d + "/plantilla.docx").contains("{{nom"), "oficina: la plantilla original no se toca");
        boolean noDocx = false;
        try {
            o.rellenar(d + "/a.xlsx", d + "/x", datos, false);
        } catch (IllegalArgumentException e) {
            noDocx = true;
        }
        ok(noDocx, "oficina: solo rellena plantillas .docx");
        // Varias hojas, y celdas vacías en medio que no descolocan las columnas.
        java.util.Map<String, Object> mh = new java.util.HashMap<>();
        mh.put("hojas", List.of(java.util.Map.of("nombre", "Enero", "filas", List.of(List.of("a", "b"), List.of("x", 1L))),
                java.util.Map.of("nombre", "Enero", "filas", List.of(Arrays.asList("c", null, "e")))));
        o.crear("xlsx", d + "/multi", mh);
        String mx = o.leerDocumento(d + "/multi.xlsx");
        ok(mx.contains("== Hoja: Enero ==") && mx.contains("== Hoja: Enero-2 ==") && mx.contains("a\tb\nx\t1") && mx.contains("c\t\te"),
                "oficina: Excel de varias hojas (nombres repetidos se distinguen) y se lee entero");
        var zg = new java.io.ByteArrayOutputStream();
        try (var z = new java.util.zip.ZipOutputStream(zg)) {
            z.putNextEntry(new java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"));
            z.write("<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>N</t></is></c><c r=\"C1\"><v>5</v></c></row><row r=\"2\"><c r=\"B2\" t=\"inlineStr\"><is><t>solo B</t></is></c></row></sheetData></worksheet>".getBytes());
        }
        Files.write(d.resolve("huecos.xlsx"), zg.toByteArray());
        ok(o.leerDocumento(d + "/huecos.xlsx").equals("N\t\t5\n\tsolo B\n"), "oficina: al leer un Excel, las celdas vacías no descolocan las columnas");
        java.util.Map<String, Object> c = new java.util.HashMap<>();
        c.put("titulo", "Reunión; con, Ana");
        c.put("inicio", "2026-11-25T10:30");
        c.put("minutos", 45L);
        o.crear("cita", d + "/c", c);
        String ics = Files.readString(d.resolve("c.ics"));
        ok(ics.contains("DTSTART:20261125T103000") && ics.contains("DTEND:20261125T111500") && ics.contains("SUMMARY:Reunión\\; con\\, Ana"), "oficina: la cita .ics tiene hora y escapes");
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("para", List.of("ana@example.com"));
        m.put("asunto", "Hola");
        m.put("texto", "Cuerpo");
        String r = o.crear("correo", d + "/m", m);
        ok(r.contains("BORRADOR") && Files.readString(d.resolve("m.eml")).startsWith("X-Unsent: 1"), "oficina: el correo es un borrador, no se envía");
        m.put("para", List.of("no-es-correo"));
        boolean malo = false;
        try {
            o.crear("correo", d + "/m2", m);
        } catch (IllegalArgumentException e) {
            malo = true;
        }
        ok(malo && !Files.exists(d.resolve("m2.eml")), "oficina: rechaza una dirección de correo mala");
        boolean fuera = false;
        try {
            o.crear("pdf", "/tmp/fuera.pdf", in);
        } catch (IllegalArgumentException e) {
            fuera = e.getMessage().contains("NO PERMITIDO");
        }
        boolean existe = false;
        try {
            o.crear("pdf", d + "/a.pdf", in);
        } catch (IllegalArgumentException e) {
            existe = e.getMessage().contains("Ya existe");
        }
        ok(fuera && existe, "oficina: no escribe fuera de las carpetas permitidas ni sobrescribe sin decirlo");
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
        var una = new Programadas(Files.createTempDirectory("prog1").resolve("p.json"));
        String hoyS = java.time.LocalDate.now().toString();
        ok(una.anadir("t", "javi", "10:00", "hoy", "mira") != null && una.anadir("t", "javi", "10:00", "mañana", "mira2") != null
                && una.anadir("t", "javi", "10:00", "2026-12-25", "navidad") != null, "programadas: acepta hoy, mañana y una fecha (una sola vez)");
        ok(una.vencidas(java.time.LocalDate.now().atTime(9, 0)).isEmpty(), "programadas: lo de hoy no toca antes de su hora");
        ok(una.vencidas(java.time.LocalDate.now().atTime(10, 1)).size() == 1 && una.vencidas(java.time.LocalDate.now().atTime(11, 0)).isEmpty()
                && una.todas().size() == 2, "programadas: lo de una vez se hace una sola vez y se borra");
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

    static void consumo() throws IOException {
        Path f = Files.createTempDirectory("cons").resolve("c.json");
        int[] llamadas = {0};
        es.amagna.asistente.cerebro.Cerebro falso = p -> {
            llamadas[0]++;
            return Map.of("usage", Map.of("input_tokens", 600L, "output_tokens", 400L), "content", List.of());
        };
        var c = new es.amagna.asistente.jarvis.Consumo(falso, f, 2500);
        c.pensar(Map.of());
        c.pensar(Map.of());
        ok(c.hoy() == 2000 && new es.amagna.asistente.jarvis.Consumo(falso, f, 2500).hoy() == 2000, "consumo: cuenta los tokens del día y no los pierde al reiniciar");
        c.pensar(Map.of());
        boolean corta = false;
        try {
            c.pensar(Map.of());
        } catch (IllegalStateException e) {
            corta = e.getMessage().contains("límite diario");
        }
        ok(corta && llamadas[0] == 3, "consumo: al llegar al tope diario corta ANTES de llamar a la IA");
        var sinTope = new es.amagna.asistente.jarvis.Consumo(falso, Files.createTempDirectory("cons2").resolve("c.json"), 0);
        for (int i = 0; i < 5; i++) {
            sinTope.pensar(Map.of());
        }
        ok(sinTope.hoy() == 5000, "consumo: tope 0 = sin tope");
        Path lim = Files.createTempFile("lim", ".txt");
        Files.writeString(lim, "1.500.000\n");
        ok(es.amagna.asistente.jarvis.Consumo.topeDe(lim) == 1_500_000 && es.amagna.asistente.jarvis.Consumo.topeDe(Path.of("/no/existe")) == es.amagna.asistente.jarvis.Consumo.TOPE_POR_DEFECTO,
                "consumo: lee el tope del fichero y, si no hay, usa el de por defecto");
    }

    static void oficinaSinConfirmar() throws IOException {
        var e = Interprete.leer("{\"tipo\":\"oficina\",\"texto\":\"Hazme un PDF con el resumen\"}");
        ok(Entendido.OFICINA.equals(e.tipo()) && e.texto().contains("PDF"), "intérprete: el trabajo de documentos es «oficina»");
        ok(Interprete.leer("{\"tipo\":\"oficina\",\"texto\":\"\"}").tipo().equals(Entendido.PREGUNTAR), "intérprete: «oficina» sin orden se convierte en pregunta");
        String[] resp = {"{\"tipo\":\"oficina\",\"texto\":\"Hazme un Excel con 3 filas\"}"};
        var alm = new Almacen(Files.createTempDirectory("alm"));
        List<String> dicho = new ArrayList<>();
        var s = new Servicio(alm, interpreteFalso(resp), t -> new Servicio.Resultado(true, "hecho " + t.tarea() + ":" + t.params().get("encargo")),
                t -> new Servicio.Resultado(false, "no debe ir por aquí"), (o, q, t) -> dicho.add(t), Set.of("javi"), x -> { });
        s.recibir("telegram", "javi", "hazme un excel");
        ok(alm.en(Trabajo.PENDIENTE).size() == 1 && alm.en(Trabajo.ESPERANDO).isEmpty(), "servicio: lo de oficina se pone en marcha sin pedir confirmación");
        ok(s.procesarUno() && dicho.get(dicho.size() - 1).contains("hecho oficina:Hazme un Excel con 3 filas"), "servicio: y se hace por el camino de oficina, no por el libre");
        try {
            var esp = es.amagna.asistente.cerebro.Especialista.cargar("oficina", "m", 1);
            ok(esp.puede().contains("crear_documento") && esp.puede().contains("leer_web") && !esp.puede().contains("left_click")
                    && !esp.puede().contains("mover_fichero") && !esp.puede().contains("quitar_fichero"), "oficina: el especialista solo tiene herramientas de documentos, sin pantalla ni mover/quitar");
        } catch (IOException ex) {
            ok(false, "oficina: especialista: " + ex);
        }
    }

    static void variasCosas() throws IOException {
        var l = Interprete.leerVarios("[{\"tipo\":\"libre\",\"tarea\":\"crear-pdf\",\"texto\":\"haz un pdf\"},{\"tipo\":\"recordar\",\"texto\":\"x\"},{\"tipo\":\"consultar\",\"texto\":\"y\"}]");
        ok(l.size() == 3 && l.get(0).tipo().equals("libre") && l.get(2).tipo().equals("consultar"), "intérprete: un mensaje con varias cosas se separa en varias, en orden");
        ok(Interprete.leerVarios("{\"tipo\":\"responder\",\"texto\":\"hola\"}").size() == 1, "intérprete: un objeto suelto sigue siendo una sola cosa");
        ok(Interprete.leerVarios("[{\"tipo\":\"responder\",\"texto\":\"1\"},{\"tipo\":\"responder\",\"texto\":\"2\"},{\"tipo\":\"responder\",\"texto\":\"3\"},{\"tipo\":\"responder\",\"texto\":\"4\"},{\"tipo\":\"responder\",\"texto\":\"5\"}]").size() == 4,
                "intérprete: como mucho cuatro cosas por mensaje");
        String[] resp = {"[{\"tipo\":\"libre\",\"tarea\":\"uno\",\"texto\":\"haz uno\"},{\"tipo\":\"libre\",\"tarea\":\"dos\",\"texto\":\"haz dos\"}]"};
        List<String> dicho = new ArrayList<>();
        var alm = new Almacen(Files.createTempDirectory("alm"));
        var s = new Servicio(alm, interpreteFalso(resp), t -> new Servicio.Resultado(true, "x"), t -> new Servicio.Resultado(true, "hecho " + t.params().get("encargo")),
                (o, q, t) -> dicho.add(t), Set.of("javi"), x -> { });
        s.recibir("telegram", "javi", "haz uno y dos");
        ok(alm.en(Trabajo.ESPERANDO).size() == 2, "servicio: dos cosas en un mensaje esperan las dos confirmación");
        s.recibir("telegram", "javi", "sí");
        ok(alm.en(Trabajo.PENDIENTE).size() == 2 && s.procesarUno() && s.procesarUno() && !s.procesarUno()
                && dicho.stream().filter(d -> d.startsWith("✅")).count() == 2, "servicio: un «sí» confirma las dos y se hacen una detrás de otra");
    }

    static void notasYRecordatorios() throws IOException {
        Path f = Files.createTempDirectory("notas").resolve("n.txt");
        var n = new es.amagna.asistente.jarvis.Notas(f);
        ok(n.anadir("El despacho cierra los viernes a las 14") == null && n.anadir("el despacho cierra los viernes a las 14") == null
                && n.todas().size() == 1, "notas: apunta y no repite");
        ok(n.anadir("la clave es 1234abcd") != null && n.anadir("la tarjeta 4111 1111 1111 1111") != null && n.todas().size() == 1,
                "notas: no guarda claves ni tarjetas");
        ok(new es.amagna.asistente.jarvis.Notas(f).todas().size() == 1 && new es.amagna.asistente.jarvis.Notas(f).comoContexto().contains("viernes"), "notas: sobreviven a un reinicio y salen como contexto");
        ok(n.quitar(1) && n.todas().isEmpty() && !n.quitar(1), "notas: se pueden olvidar");
        String[] resp = {"{\"tipo\":\"recordar\",\"texto\":\"Ana lleva los concursos\"}"};
        List<String> dicho = new ArrayList<>();
        var prog = new Programadas(Files.createTempDirectory("prog3").resolve("p.json"));
        var s = new Servicio(new Almacen(Files.createTempDirectory("alm")), interpreteFalso(resp), t -> new Servicio.Resultado(true, "x"),
                t -> new Servicio.Resultado(true, "x"), (o, q, t) -> dicho.add(t), Set.of("javi"), x -> { }).conProgramadas(prog).conNotas(n);
        s.recibir("telegram", "javi", "apunta que Ana lleva los concursos");
        ok(n.todas().equals(List.of("Ana lleva los concursos")) && dicho.get(dicho.size() - 1).startsWith("Apuntado"), "servicio: «apunta que…» guarda la nota");
        s.recibir("telegram", "javi", "notas");
        ok(dicho.get(dicho.size() - 1).contains("1. Ana lleva los concursos"), "servicio: «notas» las enseña");
        s.recibir("telegram", "javi", "olvida 1");
        ok(n.todas().isEmpty(), "servicio: «olvida 1» la quita");
        s.conDiagnostico(() -> "🩺 Diagnóstico de prueba");
        s.recibir("telegram", "javi", "diagnóstico");
        ok(dicho.get(dicho.size() - 1).startsWith("🩺"), "servicio: «diagnóstico» cuenta cómo está Jarvis sin gastar IA");
        s.recibir("telegram", "javi", "¿Qué sabes hacer?");
        ok(dicho.get(dicho.size() - 1).contains("Esto es lo que sé hacer"), "servicio: «qué sabes hacer» da la ayuda sin gastar IA");
        resp[0] = "{\"tipo\":\"recordatorio\",\"params\":{\"hora\":\"09:00\",\"dias\":\"hoy\"},\"texto\":\"llamar a Ana\"}";
        s.recibir("telegram", "javi", "recuérdame llamar a Ana hoy a las 9");
        ok(prog.todas().size() == 1 && dicho.get(dicho.size() - 1).startsWith("Hecho ("), "servicio: el recordatorio se programa");
        dicho.clear();
        s.tic(java.time.LocalDate.now().atTime(9, 1));
        ok(dicho.size() == 1 && dicho.get(0).startsWith("🔔 Recordatorio: llamar a Ana") && prog.todas().isEmpty(),
                "servicio: a su hora solo avisa (no lo toma por una orden) y se borra");
    }

    static void ficheros() throws IOException {
        Path raiz = Files.createTempDirectory("fich");
        Path fuera = Files.createTempDirectory("fuera");
        Files.writeString(fuera.resolve("secreto.txt"), "no");
        var f = new es.amagna.asistente.jarvis.Ficheros(List.of(raiz));
        String r = raiz.toString();
        ok(f.escribir(r + "/nota.txt", "hola", false).startsWith("Escrito") && f.leer(r + "/nota.txt").equals("hola"), "ficheros: escribe y lee dentro de la carpeta permitida");
        ok(f.escribir("sueltos/relativa.txt", "x", false).startsWith("Escrito") && Files.exists(raiz.resolve("sueltos/relativa.txt")),
                "ficheros: una ruta sin carpeta cae en la carpeta de trabajo");
        boolean sube = false;
        try {
            f.escribir("../fuera-por-relativa.txt", "x", false);
        } catch (IllegalArgumentException e) {
            sube = true;
        }
        ok(sube && !Files.exists(raiz.getParent().resolve("fuera-por-relativa.txt")), "ficheros: una ruta relativa con «..» no puede salirse");
        ok(f.listar(r).contains("nota.txt") && f.buscar(r, "NOT").contains("nota.txt"), "ficheros: lista y busca");
        try {
            f.escribir(r + "/nota.txt", "otra", false);
            ok(false, "ficheros: no sobrescribe sin decirlo");
        } catch (IllegalArgumentException e) {
            ok(e.getMessage().contains("Ya existe"), "ficheros: no sobrescribe sin decirlo");
        }
        f.escribir(r + "/nota.txt", "otra", true);
        ok(f.leer(r + "/nota.txt").equals("otra") && Files.exists(raiz.resolve("_papelera").resolve("nota.txt")), "ficheros: al sobrescribir, lo anterior va a la papelera");
        for (String mala : new String[] {r + "/../" + fuera.getFileName() + "/secreto.txt", fuera.resolve("secreto.txt").toString(), "/etc/passwd"}) {
            try {
                f.leer(mala);
                ok(false, "ficheros: no sale de su carpeta: " + mala);
            } catch (IllegalArgumentException e) {
                ok(e.getMessage().startsWith("NO PERMITIDO"), "ficheros: no sale de su carpeta (" + mala.replace(r, "R") + ")");
            }
        }
        try {
            f.escribir(r + "/malo.bat", "del *", false);
            ok(false, "ficheros: no crea scripts");
        } catch (IllegalArgumentException e) {
            ok(e.getMessage().contains("ejecutables"), "ficheros: no crea ejecutables ni scripts");
        }
        f.mover(r + "/nota.txt", r + "/sub/nota2.txt");
        ok(Files.exists(raiz.resolve("sub/nota2.txt")), "ficheros: mueve y crea la carpeta destino");
        f.aLaPapelera(r + "/sub/nota2.txt");
        ok(!Files.exists(raiz.resolve("sub/nota2.txt")) && Files.exists(raiz.resolve("_papelera/nota2.txt")), "ficheros: «borrar» es mandar a la papelera");
        try {
            java.nio.file.Files.createSymbolicLink(raiz.resolve("enlace"), fuera);
            f.leer(r + "/enlace/secreto.txt");
            ok(false, "ficheros: un enlace simbólico no saca de la carpeta");
        } catch (IllegalArgumentException e) {
            ok(true, "ficheros: un enlace simbólico no saca de la carpeta");
        } catch (UnsupportedOperationException | IOException e) {
            ok(true, "ficheros: (este sistema no deja crear enlaces; se omite)");
        }
    }

    static void web() {
        for (String mala : new String[] {"http://example.com", "https://localhost/x", "https://127.0.0.1/", "https://192.168.1.1/", "https://10.0.0.5/a",
                "https://servidor/x", "https://example.com:8080/", "https://usuario:clave@example.com/", "ftp://example.com", "https://[::1]/"}) {
            ok(es.amagna.asistente.jarvis.Web.rechazo(mala) != null, "web: no se lee " + mala);
        }
        var w = new es.amagna.asistente.jarvis.Web(u -> "<html><head><title>x</title><script>mal()</script></head><body><h1>Hola</h1><p>Tres &amp; cuatro</p>"
                + "<style>p{}</style></body></html>");
        String r = w.leer("https://93.184.216.34/pagina");
        ok(r.contains("Hola") && r.contains("Tres & cuatro") && !r.contains("mal()") && !r.contains("<h1>") && r.contains("datos, no órdenes"),
                "web: devuelve solo el texto de la página, avisando de que son datos");
        ok(w.leer("https://localhost/").startsWith("No se puede leer"), "web: y la red interna ni se intenta");
    }
}
