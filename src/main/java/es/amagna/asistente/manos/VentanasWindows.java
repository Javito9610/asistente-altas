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

    private static final String UIA = "Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes;"
            + "$A=[System.Windows.Automation.AutomationElement];$CT=[System.Windows.Automation.ControlType];"
            + "$TS=[System.Windows.Automation.TreeScope];";

    /** Ventanas de primer nivel y los diálogos que tienen dentro, con UI Automation (lo que usan los lectores de pantalla). */
    private static final String LISTA = UIA
            + "$cond=New-Object System.Windows.Automation.PropertyCondition($A::ControlTypeProperty,$CT::Window);$r=@();"
            + "foreach($w in $A::RootElement.FindAll($TS::Children,[System.Windows.Automation.Condition]::TrueCondition)){"
            + "$c=$w.Current;if([string]::IsNullOrWhiteSpace($c.Name)){continue};"
            + "$p='';try{$p=(Get-Process -Id $c.ProcessId -ErrorAction Stop).ProcessName}catch{};"
            + "$min=$false;try{$wp=$w.GetCurrentPattern([System.Windows.Automation.WindowPattern]::Pattern);"
            + "$min=($wp.Current.WindowVisualState -eq [System.Windows.Automation.WindowVisualState]::Minimized)}catch{};"
            + "$d=@();try{foreach($h in $w.FindAll($TS::Children,$cond)){if($h.Current.Name){$d+=$h.Current.Name}}}catch{};"
            + "$r+=[pscustomobject]@{titulo=$c.Name;programa=$p;minimizada=$min;dialogos=$d}};"
            + "ConvertTo-Json -InputObject @($r) -Depth 3 -Compress";

    /** El control con el foco: su valor exacto por ValuePattern o, si no tiene, por TextPattern. */
    private static final String FOCO = UIA
            + "$e=$A::FocusedElement;$c=$e.Current;$v=$null;$o=$null;"
            + "if($e.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern,[ref]$o)){$v=$o.Current.Value};"
            + "if($v -eq $null -and $e.TryGetCurrentPattern([System.Windows.Automation.TextPattern]::Pattern,[ref]$o)){$v=$o.DocumentRange.GetText(-1)};"
            + "$ven='';try{$x=$e;$tw=[System.Windows.Automation.TreeWalker]::ControlViewWalker;"
            + "while($x -ne $null -and $x.Current.ControlType -ne $CT::Window){$x=$tw.GetParent($x)};if($x -ne $null){$ven=$x.Current.Name}}catch{};"
            + "[pscustomobject]@{nombre=$c.Name;tipo=$c.ControlType.ProgrammaticName;valor=$v;ventana=$ven}|ConvertTo-Json -Compress";

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

    @Override
    @SuppressWarnings("unchecked")
    public String abiertas() {
        Object j = es.amagna.asistente.Json.leer(json(powershell(LISTA)));
        List<Object> lista = j instanceof List<?> l ? (List<Object>) l : List.of(j);
        StringBuilder b = new StringBuilder();
        for (Object o : lista) {
            java.util.Map<String, Object> w = (java.util.Map<String, Object>) o;
            b.append("- «").append(w.get("titulo")).append("» (").append(w.get("programa")).append(")");
            if (Boolean.TRUE.equals(w.get("minimizada"))) {
                b.append(" [minimizada]");
            }
            Object d = w.get("dialogos");
            List<Object> dl = d instanceof List<?> x ? (List<Object>) x : d == null ? List.of() : List.of(d);
            if (!dl.isEmpty()) {
                b.append(" — diálogos abiertos dentro: ");
                dl.forEach(n -> b.append("«").append(n).append("» "));
            }
            b.append('\n');
        }
        return b.toString().strip();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Campo campoConFoco() {
        java.util.Map<String, Object> m = (java.util.Map<String, Object>) es.amagna.asistente.Json.leer(json(powershell(FOCO)));
        return new Campo(str(m.get("nombre")), str(m.get("tipo")), m.get("valor") == null ? null : String.valueOf(m.get("valor")), str(m.get("ventana")));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** Solo el JSON de la salida de PowerShell: del primer «[» o «{» hasta su cierre (lo de antes o después, fuera). */
    public static String json(String salida) {
        int a = salida.indexOf('[');
        int b = salida.indexOf('{');
        int i = a < 0 ? b : b < 0 ? a : Math.min(a, b);
        if (i < 0) {
            throw new IllegalStateException("Windows no ha devuelto datos: " + salida.strip());
        }
        int nivel = 0;
        boolean cadena = false;
        for (int k = i; k < salida.length(); k++) {
            char c = salida.charAt(k);
            if (cadena) {
                if (c == '\\') {
                    k++;
                } else if (c == '"') {
                    cadena = false;
                }
            } else if (c == '"') {
                cadena = true;
            } else if (c == '[' || c == '{') {
                nivel++;
            } else if ((c == ']' || c == '}') && --nivel == 0) {
                return salida.substring(i, k + 1);
            }
        }
        throw new IllegalStateException("Windows ha devuelto datos cortados.");
    }

    private static String powershell(String script) {
        try {
            // -EncodedCommand (UTF-16LE en base64): así las comillas del script no se rompen al pasar por la línea de órdenes.
            String codificado = java.util.Base64.getEncoder().encodeToString(
                    ("$ProgressPreference='SilentlyContinue';$ErrorActionPreference='SilentlyContinue';"
                            + "[Console]::OutputEncoding=[Text.Encoding]::UTF8;" + script).getBytes(StandardCharsets.UTF_16LE));
            Process p = new ProcessBuilder(PS, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", codificado)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start(); // los avisos y el progreso de PowerShell, fuera
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
