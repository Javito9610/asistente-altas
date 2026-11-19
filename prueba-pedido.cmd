@echo off
rem Le pide a Jarvis (por el buzon) lo que haya en pruebas\pedido.txt, le contesta "si" a la confirmacion, espera el resultado y lo
rem apaga. Resultado: datos\buzon\salida\respuestas.txt y registro\jarvis-prueba.log
setlocal enabledelayedexpansion
set "SYS=%SystemRoot%\System32"
cd /d C:\Proyectos\asistente-altas
if not exist datos\buzon\entrada mkdir datos\buzon\entrada
del datos\parar.txt >nul 2>&1
del datos\buzon\salida\respuestas.txt >nul 2>&1
call asistente.cmd compilar > registro\jarvis-prueba.log 2>&1
start "jarvis-prueba" /b cmd /c "call asistente.cmd jarvis --sin-ventana >> registro\jarvis-prueba.log 2>&1"
set /a N=0
:listo
%SYS%\ping.exe -n 4 127.0.0.1 >nul
set /a N+=3
%SYS%\findstr.exe /C:"Jarvis en marcha" registro\jarvis-prueba.log >nul 2>&1 && goto pedir
if !N! lss 180 goto listo
echo Jarvis no arranco. & goto fin
:pedir
copy /y pruebas\pedido.txt datos\buzon\entrada\p.tmp >nul
move /y datos\buzon\entrada\p.tmp datos\buzon\entrada\p.txt >nul
%SYS%\ping.exe -n 14 127.0.0.1 >nul
echo si> datos\buzon\entrada\s.tmp
move /y datos\buzon\entrada\s.tmp datos\buzon\entrada\s.txt >nul
set /a ESPERA=0
:esperar
%SYS%\ping.exe -n 11 127.0.0.1 >nul
set /a ESPERA+=10
%SYS%\findstr.exe /C:"TERMINADO" /C:"PARADO" /C:"HECHO" /C:"Hecho de memoria" /C:"Se me ha" /C:"necesito la IA" /C:"Programado (" /C:"No he podido" /C:"FALL" datos\buzon\salida\respuestas.txt >nul 2>&1 && goto fin
if !ESPERA! lss 400 goto esperar
:fin
%SYS%\ping.exe -n 6 127.0.0.1 >nul
echo parar> datos\parar.txt
%SYS%\ping.exe -n 6 127.0.0.1 >nul
echo ===== RESPUESTAS DE JARVIS =====
type datos\buzon\salida\respuestas.txt
echo ===== FIN =====
