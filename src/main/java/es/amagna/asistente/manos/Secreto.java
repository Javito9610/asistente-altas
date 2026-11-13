package es.amagna.asistente.manos;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Los secretos: la contraseña de MN, guardada como la guarda Windows.
 *
 * Sin esto no hay servicio de noche. Si a las tres de la mañana caduca la sesión de MN, el asistente tiene que poder
 * volver a entrar él solo, y para eso necesita la contraseña. Pero una contraseña en un fichero de texto en el PC de
 * un despacho es inaceptable.
 *
 * Se usa DPAPI, que es lo mismo que hay debajo del Administrador de credenciales de Windows: Windows la cifra con una
 * clave derivada de la cuenta de usuario. El fichero cifrado solo se puede descifrar con ESE usuario y en ESE
 * ordenador. Si alguien se lleva el disco, el fichero no le sirve de nada. Ni Claude, ni este programa, ni nadie que
 * mire el repositorio ve jamás la contraseña: solo se descifra en el momento de escribirla en MN.
 *
 * Y nunca sale por pantalla ni por el registro: lo que se enseña es «secreto:mn», nunca su valor.
 *
 * @package Amagna\Altas
 */
public final class Secreto {
    private Secreto() {
    }

    /** Un pellizco de sal propio: sin él, otro programa del mismo usuario podría descifrar nuestros secretos. */
    private static final byte[] SAL = "AsistenteAltas.Amagna.v1".getBytes(StandardCharsets.UTF_8);

    public interface Crypt32 extends StdCallLibrary {
        Crypt32 I = Native.load("Crypt32", Crypt32.class);

        boolean CryptProtectData(Blob dentro, Pointer descripcion, Blob sal, Pointer reservado, Pointer aviso, int banderas, Blob fuera);

        boolean CryptUnprotectData(Blob dentro, Pointer descripcion, Blob sal, Pointer reservado, Pointer aviso, int banderas, Blob fuera);
    }

    public interface Kernel32Local extends StdCallLibrary {
        Kernel32Local I = Native.load("kernel32", Kernel32Local.class);

        Pointer LocalFree(Pointer p);
    }

    @Structure.FieldOrder({"cbData", "pbData"})
    public static class Blob extends Structure {
        public int cbData;
        public Pointer pbData;

        public Blob() {
        }

        public Blob(byte[] datos) {
            cbData = datos.length;
            Memory m = new Memory(Math.max(1, datos.length));
            m.write(0, datos, 0, datos.length);
            pbData = m;
        }

        byte[] bytes() {
            return cbData == 0 || pbData == null ? new byte[0] : pbData.getByteArray(0, cbData);
        }
    }

    public static Path carpeta() {
        String local = System.getenv("LOCALAPPDATA");
        return Path.of(local != null ? local : System.getProperty("user.home"), "AsistenteAltas", "secretos");
    }

    /** Guarda un secreto cifrado con la cuenta de Windows de quien lo guarda. */
    public static void guardar(String nombre, char[] valor) throws IOException {
        byte[] claro = new String(valor).getBytes(StandardCharsets.UTF_8);
        try {
            Blob fuera = new Blob();
            if (!Crypt32.I.CryptProtectData(new Blob(claro), null, new Blob(SAL), null, null, 0, fuera)) {
                throw new IOException("Windows no ha podido cifrar el secreto (error " + Native.getLastError() + ").");
            }
            byte[] cifrado = fuera.bytes();
            Kernel32Local.I.LocalFree(fuera.pbData);
            Files.createDirectories(carpeta());
            Path f = carpeta().resolve(limpio(nombre) + ".dat");
            Files.write(f, cifrado);
            soloParaMi(f);
        } finally {
            Arrays.fill(claro, (byte) 0);
            Arrays.fill(valor, '\0');
        }
    }

    /** Lo devuelve descifrado, o null si no está guardado. Quien lo use debe borrarlo de memoria al terminar. */
    public static char[] leer(String nombre) throws IOException {
        Path f = carpeta().resolve(limpio(nombre) + ".dat");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        Blob fuera = new Blob();
        if (!Crypt32.I.CryptUnprotectData(new Blob(Files.readAllBytes(f)), null, new Blob(SAL), null, null, 0, fuera)) {
            throw new IOException("No se ha podido descifrar «" + nombre + "»: lo guardó otro usuario de Windows u otro "
                    + "ordenador. Vuelve a guardarlo aquí con «asistente secreto " + nombre + "».");
        }
        byte[] claro = fuera.bytes();
        Kernel32Local.I.LocalFree(fuera.pbData);
        char[] r = new String(claro, StandardCharsets.UTF_8).toCharArray();
        Arrays.fill(claro, (byte) 0);
        return r;
    }

    public static boolean hay(String nombre) {
        return Files.isRegularFile(carpeta().resolve(limpio(nombre) + ".dat"));
    }

    public static void olvidar(String nombre) throws IOException {
        Files.deleteIfExists(carpeta().resolve(limpio(nombre) + ".dat"));
    }

    /** Los nombres de los secretos guardados. Solo los nombres: el valor no sale de aquí nunca. */
    public static List<String> nombres() throws IOException {
        List<String> r = new ArrayList<>();
        if (Files.isDirectory(carpeta())) {
            try (var s = Files.list(carpeta())) {
                s.filter(f -> f.getFileName().toString().endsWith(".dat"))
                        .forEach(f -> r.add(f.getFileName().toString().replace(".dat", "")));
            }
        }
        r.sort(String::compareTo);
        return r;
    }

    /** Además del cifrado, que el fichero sea solo del dueño: dos cerrojos mejor que uno. */
    private static void soloParaMi(Path f) {
        try {
            var vista = Files.getFileAttributeView(f, java.nio.file.attribute.AclFileAttributeView.class);
            if (vista == null) {
                return;
            }
            var yo = vista.getOwner();
            var permiso = java.nio.file.attribute.AclEntry.newBuilder()
                    .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                    .setPrincipal(yo)
                    .setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class))
                    .build();
            vista.setAcl(List.of(permiso));
        } catch (IOException | RuntimeException e) {
            // Si el sistema de ficheros no lo permite, el cifrado sigue estando.
        }
    }

    private static String limpio(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
