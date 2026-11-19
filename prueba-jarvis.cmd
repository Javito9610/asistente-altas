@echo off
rem Prueba de Jarvis de punta a punta SIN Telegram: arranca Jarvis, le pide por el buzon que de de alta un cliente ficticio
rem en la Empresa de prueba, espera al resultado y lo apaga. Deja registro\jarvis-prueba.log y datos\buzon\salida\respuestas.txt
setlocal enabledelayedexpansion
set "SYS=%SystemRoot%\System32"
cd /d C:\Proyectos\asistente-altas
if not exist datos\buzon\entrada mkdir datos\buzon\entrada
del datos\parar.txt >nul 2>&1
del datos\buzon\salida\respuestas.txt >nul 2>&1
call asistente.cmd compilar > registro\jarvis-prueba.log 2>&1
set "TAG=%TIME:~6,2%%TIME:~9,2%"
set "NIF=9971%TIME:~3,2%%TIME:~6,2%Z"
set "NIF=%NIF: =0%"
start "jarvis-prueba" /b cmd /c "call asistente.cmd jarvis --sin-ventana >> registro\jarvis-prueba.log 2>&1"
rem Esperar a que Jarvis este listo (compilar tarda).
set /a N=0
:listo
%SYS%\ping.exe -n 4 127.0.0.1 >nul
set /a N+=3
%SYS%\findstr.exe /C:"Jarvis en marcha" registro\jarvis-prueba.log >nul 2>&1 && goto listo2
if !N! lss 180 goto listo
echo Jarvis no arranco. & goto fin
:listo2
echo estado> datos\buzon\entrada\1.tmp
move /y datos\buzon\entrada\1.tmp datos\buzon\entrada\1.txt >nul
%SYS%\ping.exe -n 6 127.0.0.1 >nul
echo Da de alta en la Empresa de prueba un cliente ficticio llamado JARVIS PRUEBA %TAG% con NIF %NIF%, telefono 600 11 22 33, direccion Calle de Proba 9, y codigo postal 15001> datos\buzon\entrada\2.tmp
move /y datos\buzon\entrada\2.tmp datos\buzon\entrada\2.txt >nul
set /a ESPERA=0
:espera
%SYS%\ping.exe -n 11 127.0.0.1 >nul
set /a ESPERA+=10
%SYS%\findstr.exe /C:"HECHO" /C:"PARADO" /C:"YA HECHA" /C:"roto" /C:"Se me ha" datos\buzon\salida\respuestas.txt >nul 2>&1 && goto acabo
if !ESPERA! lss 500 goto espera
:acabo
echo estado> datos\buzon\entrada\3.tmp
move /y datos\buzon\entrada\3.tmp datos\buzon\entrada\3.txt >nul
%SYS%\ping.exe -n 6 127.0.0.1 >nul
:fin
echo parar> datos\parar.txt
%SYS%\ping.exe -n 6 127.0.0.1 >nul
echo ===== RESPUESTAS DE JARVIS =====
type datos\buzon\salida\respuestas.txt
echo ===== FIN =====
