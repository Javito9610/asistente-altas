@echo off
rem Arranca Jarvis CON ventana unos segundos y lo apaga; el registro dice si la ventana dio algun error. Resultado: registro\jarvis-ventana.log
setlocal enabledelayedexpansion
set "SYS=%SystemRoot%\System32"
cd /d C:\Proyectos\asistente-altas
del datos\parar.txt >nul 2>&1
call asistente.cmd compilar > registro\jarvis-ventana.log 2>&1
start "jarvis-ventana" /b cmd /c "call asistente.cmd jarvis >> registro\jarvis-ventana.log 2>&1"
set /a N=0
:listo
%SYS%\ping.exe -n 4 127.0.0.1 >nul
set /a N+=3
%SYS%\findstr.exe /C:"Jarvis en marcha" registro\jarvis-ventana.log >nul 2>&1 && goto vivo
if !N! lss 180 goto listo
echo Jarvis no arranco. & goto fin
:vivo
%SYS%\ping.exe -n 12 127.0.0.1 >nul
echo si hay ventana: capturando > datos\buzon\entrada\v.tmp
move /y datos\buzon\entrada\v.tmp datos\buzon\entrada\v.txt >nul
%SYS%\ping.exe -n 8 127.0.0.1 >nul
:fin
echo parar> datos\parar.txt
%SYS%\ping.exe -n 8 127.0.0.1 >nul
echo ===== REGISTRO =====
%SYS%\findstr.exe /V /C:"  ok  " registro\jarvis-ventana.log
echo ===== FIN =====
