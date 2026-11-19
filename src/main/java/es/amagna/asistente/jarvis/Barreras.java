package es.amagna.asistente.jarvis;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Las líneas rojas del Jarvis «libre», puestas en CÓDIGO: da igual lo que la IA crea o lo que diga el encargo, esto no pasa.
 * (Las instrucciones piden prudencia; esto la garantiza.) Devuelve el motivo del veto, o null si se puede.
 */
public final class Barreras {
    private Barreras() {
    }

    /** Acciones que solo miran: valen siempre. */
    static final Set<String> SOLO_MIRAN = Set.of("screenshot", "zoom", "ventanas_abiertas", "ventana_activa", "ver_controles", "ver_ventana",
            "leer_control", "leer_campo", "esperar_a", "mouse_move", "cursor_position", "wait");

    /** Programas que tocan el sistema o la seguridad, o que dan una consola. */
    static final List<String> PROGRAMAS_PROHIBIDOS = List.of("cmd", "powershell", "pwsh", "regedit", "mmc", "taskmgr", "gpedit", "secpol",
            "control", "msconfig", "wt", "bash", "wsl", "diskpart", "format", "reg", "netsh", "schtasks", "sc", "net", "cipher", "bcdedit",
            "setx", "cscript", "wscript", "mshta", "rundll32", "certutil", "bitsadmin", "curl", "wget");

    public static String veto(String accion, Map<String, Object> in, String ventanasAbiertas) {
        if (SOLO_MIRAN.contains(accion)) {
            return null;
        }
        if ("abrir_programa".equals(accion)) {
            String prog = String.valueOf(in.get("programa")).toLowerCase(Locale.ROOT).replace('/', '\\');
            String exe = prog.substring(prog.lastIndexOf('\\') + 1).replaceAll("\\.(exe|com|bat|cmd|msc|ps1|vbs|js|lnk)$", "");
            if (PROGRAMAS_PROHIBIDOS.contains(exe)) {
                return "«" + in.get("programa") + "» toca el sistema o da una consola.";
            }
            if (prog.matches(".*\\.(bat|cmd|ps1|vbs|js|msi|scr|jar|hta)$")) {
                return "no se ejecutan scripts ni instaladores.";
            }
        }
        // En MN solo se escribe en la Empresa de prueba.
        if (ventanasAbiertas != null && delanteEsMn(ventanasAbiertas) && !ventanasAbiertas.contains("Empresa de prueba")) {
            return "MN no está en la «Empresa de prueba»: en la empresa real solo se mira.";
        }
        // Datos que nunca se teclean: tarjetas, IBAN, contraseñas.
        Object t = in.get("text") != null ? in.get("text") : in.get("valor") != null ? in.get("valor") : in.get("texto");
        if (t instanceof String s && sensible(s)) {
            return "ese texto parece un número de tarjeta, un IBAN o una clave: no se teclea.";
        }
        if ("escribir_en".equals(accion)) {
            String c = String.valueOf(in.get("control")).toLowerCase(Locale.ROOT);
            if (c.contains("contrase") || c.contains("password") || c.contains("clave") || c.contains("pin")) {
                return "es un campo de contraseña: las claves no las pone Jarvis.";
            }
        }
        return null;
    }

    static boolean delanteEsMn(String ventanas) {
        for (String l : ventanas.split("\\R")) {
            if (l.contains("[delante")) {
                return l.contains("(MNprogram)") || l.contains("(GestorConsultas)");
            }
        }
        return false;
    }

    static boolean sensible(String s) {
        String d = s.replaceAll("[\\s-]", "");
        if (d.matches(".*\\bES\\d{22}\\b.*") || d.toUpperCase(Locale.ROOT).matches(".*ES\\d{22}.*")) {
            return true;
        }
        var m = java.util.regex.Pattern.compile("\\d{13,19}").matcher(d);
        while (m.find()) {
            if (luhn(m.group())) {
                return true;
            }
        }
        return false;
    }

    static boolean luhn(String n) {
        int suma = 0;
        boolean doble = false;
        for (int i = n.length() - 1; i >= 0; i--) {
            int c = n.charAt(i) - '0';
            if (doble) {
                c *= 2;
                if (c > 9) {
                    c -= 9;
                }
            }
            suma += c;
            doble = !doble;
        }
        return suma % 10 == 0;
    }
}
