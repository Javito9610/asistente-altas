package es.amagna.asistente.manos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Ventanas de Windows por PowerShell (sin librerías): GetForegroundWindow/GetWindowText para saber cuál está delante y
 * WScript.Shell.AppActivate para ponerla delante. PowerShell se llama por su ruta completa (en algunos PC System32 no
 * está en el PATH).
 */
public final class VentanasWindows implements Ventanas {
    private static final String PS = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
            "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();

    private static final String TIPO = "Add-Type -TypeDefinition 'using System;using System.Runtime.InteropServices;using System.Text;"
            + "public class VentanaAA{[DllImport(\"user32.dll\")]public static extern IntPtr GetForegroundWindow();"
            + "[DllImport(\"user32.dll\",CharSet=CharSet.Unicode)]public static extern int GetWindowText(IntPtr h,StringBuilder s,int n);}';";

    private final boolean modoVista;

    public VentanasWindows(boolean modoVista) {
        this.modoVista = modoVista;
    }

    @Override
    public void abrir(String programa, List<String> argumentos) {
        if (modoVista) {
            System.out.println("    [modo vista, no se hace] abrir " + programa + " " + argumentos);
            return;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(programa);
        cmd.addAll(argumentos);
        try {
            new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            throw new IllegalStateException("No se puede abrir «" + programa + "»: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean activar(String titulo) {
        if (modoVista) {
            System.out.println("    [modo vista, no se hace] activar ventana «" + titulo + "»");
            return false;
        }
        String t = titulo.replace("'", "''");
        // Un Alt «suelto» antes de AppActivate: Windows deja cambiar el primer plano a quien acaba de pulsar una tecla.
        String r = powershell("$w=New-Object -ComObject WScript.Shell;$w.SendKeys('%');Start-Sleep -Milliseconds 100;"
                + "if($w.AppActivate('" + t + "')){'SI'}else{'NO'}");
        return r.trim().endsWith("SI");
    }

    @Override
    public String activa() {
        return powershell(TIPO + "$h=[VentanaAA]::GetForegroundWindow();$s=New-Object Text.StringBuilder 512;"
                + "[void][VentanaAA]::GetWindowText($h,$s,512);$s.ToString()").trim();
    }

    private static String powershell(String script) {
        try {
            // -EncodedCommand (UTF-16LE en base64): así las comillas del script no se rompen al pasar por la línea de órdenes.
            String codificado = java.util.Base64.getEncoder().encodeToString(
                    ("[Console]::OutputEncoding=[Text.Encoding]::UTF8;" + script).getBytes(StandardCharsets.UTF_16LE));
            Process p = new ProcessBuilder(PS, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", codificado)
                    .redirectErrorStream(true).start();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("PowerShell no ha respondido");
            }
            return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No se puede usar PowerShell: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido", e);
        }
    }
}
