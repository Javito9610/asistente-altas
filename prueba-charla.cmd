@echo off
rem Manda a Jarvis, por el buzon, los mensajes de pruebas\charla\*.txt (uno cada 25 s) y despues lo apaga.
rem Resultado: datos\buzon\salida\respuestas.txt
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
%SYS%\findstr.exe /C:"Jarvis en marcha" registro\jarvis-prueba.log >nul 2>&1 && goto enviar
if !N! lss 180 goto listo
echo Jarvis no arranco. & goto fin
:enviar
for %%F in (pruebas\charla\*.txt) do (
  copy /y "%%F" datos\buzon\entrada\m.tmp >nul
  move /y datos\buzon\entrada\m.tmp datos\buzon\entrada\%%~nF.txt >nul
  %SYS%\ping.exe -n 26 127.0.0.1 >nul
)
:fin
echo parar> datos\parar.txt
%SYS%\ping.exe -n 11 127.0.0.1 >nul
echo ===== RESPUESTAS DE JARVIS =====
type datos\buzon\salida\respuestas.txt
echo ===== FIN =====
