package es.amagna.asistente.cerebro;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ¿Este texto podría llevar datos de un cliente, de un asunto del despacho o secretos? Lo decide el CÓDIGO, nunca una IA, y
 * es deliberadamente desconfiado: ante la duda dice que SÍ es sensible. Solo lo que no es sensible puede salir a una IA
 * gratuita (que puede aprender de lo que recibe); todo lo demás va a Claude.
 *
 * Es sensible si lleva: palabras del trabajo del despacho (cliente, expediente, contrario, demanda…), correos, cifras largas
 * (teléfonos, NIF, cuentas, importes grandes, fechas), un NIF/CIF/IBAN, palabras de claves o secretos, o cualquier palabra con
 * mayúscula que no sea la primera de la frase (podría ser el nombre de una persona o de una empresa).
 */
public final class Sensibilidad {
    private Sensibilidad() {
    }

    private static final Set<String> PALABRAS = Set.of("cliente", "clientes", "expediente", "expedientes", "contrario", "contrarios",
            "demanda", "demandas", "procedimiento", "procedimientos", "autos", "juzgado", "juicio", "sentencia", "procurador",
            "letrado", "letrada", "nif", "cif", "dni", "nie", "iban", "cuenta", "cuentas", "contrasena", "contrasenas", "password",
            "clave", "claves", "token", "factura", "facturas", "nomina", "nominas", "salario", "sueldo", "deuda", "deudas", "impago",
            "embargo", "concurso", "minuta", "documento", "documentos", "fichero", "ficheros", "archivo", "archivos", "adjunto",
            "adjuntos", "pdf", "word", "excel", "correo", "correos", "email", "mail", "telefono", "movil", "direccion", "domicilio",
            "mn", "alta", "altas", "empresa", "sociedad", "divorcio", "herencia", "contrato", "contratos", "denuncia", "multa",
            "banco", "tarjeta", "pago", "pagos", "transferencia", "secreto", "secretos", "confidencial", "privado", "paciente");
    private static final Pattern CORREO = Pattern.compile("[^\\s@]+@[^\\s@]+");
    private static final Pattern NIF = Pattern.compile("(?i)\\b([XYZ]?\\d{7,8}[A-Z]|[A-HJNPQRSUVW]\\d{7}[0-9A-J])\\b");
    private static final Pattern IBAN = Pattern.compile("(?i)\\b[A-Z]{2}\\d{2}[ ]?[A-Z0-9]{4}");
    private static final Pattern TRAMO_NUMERICO = Pattern.compile("[\\d][\\d .,/\\-]*");
    private static final Pattern PARTE = Pattern.compile("[\\p{L}][\\p{L}'’-]*");

    public static boolean esSensible(String texto) {
        if (texto == null || texto.isBlank()) {
            return false;
        }
        if (CORREO.matcher(texto).find() || NIF.matcher(texto).find() || IBAN.matcher(texto).find()) {
            return true;
        }
        var m = TRAMO_NUMERICO.matcher(texto);
        while (m.find()) {
            long cifras = m.group().chars().filter(Character::isDigit).count();
            if (cifras >= 5) {
                return true;
            }
        }
        // Palabras del trabajo y nombres propios.
        for (String frase : texto.split("[.!?¿¡:;\\n\\r]+")) {
            var p = PARTE.matcher(frase);
            boolean primera = true;
            while (p.find()) {
                String w = p.group();
                String plana = plana(w);
                if (PALABRAS.contains(plana)) {
                    return true;
                }
                if (!primera && Character.isUpperCase(w.codePointAt(0)) && !plana.equals("jarvis")) {
                    return true;
                }
                primera = false;
            }
        }
        return false;
    }

    private static String plana(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
