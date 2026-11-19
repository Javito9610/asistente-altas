@echo off
rem Prueba de una tarea LIBRE fuera de MN, dos veces: la primera la hace la IA (y graba lo que hace); la segunda la repite
rem Jarvis sin IA. Usa el Bloc de notas con un texto inventado. Resultado: datos\buzon\salida\respuestas.txt y registro\jarvis-prueba.log
setlocal enabledelayedexpansion
set "SYS=%SystemRoot%\System32"
cd /d C:\Proyectos\asistente-altas
if not exist datos\buzon\entrada mkdir datos\buzon\entrada
del datos\parar.txt >nul 2>&1
del datos\buzon\salida\respuestas.txt >nul 2>&1
del aprendido\libres\*.json >nul 2>&1
call asistente.cmd compilar > registro\jarvis-prueba.log 2>&1
start "jarvis-prueba" /b cmd /c "call asistente.cmd jarvis --sin-ventana >> registro\jarvis-prueba.log 2>&1"
set /a N=0
:listo
%SYS%\ping.exe -n 4 127.0.0.1 >nul
set /a N+=3
%SYS%\findstr.exe /C:"Jarvis en marcha" registro\jarvis-prueba.log >nul 2>&1 && goto vuelta
if !N! lss 180 goto listo
echo Jarvis no arranco. & goto fin
:vuelta
call :ronda 1
copy /y datos\buzon\salida\respuestas.txt datos\buzon\salida\vuelta1.txt >nul
del datos\buzon\salida\respuestas.txt >nul 2>&1
call :ronda 2
copy /y datos\buzon\salida\respuestas.txt datos\buzon\salida\vuelta2.txt >nul
goto fin
:ronda
echo ===== VUELTA %1 =====
echo Abre el Bloc de notas y escribe en el la frase: Hola desde Jarvis %1. Luego cierralo sin guardar.> datos\buzon\entrada\q%1.tmp
move /y datos\buzon\entrada\q%1.tmp datos\buzon\entrada\q%1.txt >nul
%SYS%\ping.exe -n 12 127.0.0.1 >nul
echo si> datos\buzon\entrada\s%1.tmp
move /y datos\buzon\entrada\s%1.tmp datos\buzon\entrada\s%1.txt >nul
set /a ESPERA=0
:esperar
%SYS%\ping.exe -n 11 127.0.0.1 >nul
set /a ESPERA+=10
%SYS%\findstr.exe /C:"HECHO" /C:"TERMINADO" /C:"PARADO" /C:"Hecho de memoria" /C:"Se me ha" /C:"necesito la IA" /C:"FALL" datos\buzon\salida\respuestas.txt >nul 2>&1 && goto finronda
if !ESPERA! lss 300 goto esperar
:finronda
%SYS%\ping.exe -n 8 127.0.0.1 >nul
exit /b
:fin
echo parar> datos\parar.txt
%SYS%\ping.exe -n 6 127.0.0.1 >nul
echo ===== VUELTA 1 =====
type datos\buzon\salida\vuelta1.txt
echo ===== VUELTA 2 =====
type datos\buzon\salida\vuelta2.txt
echo ===== FIN =====
