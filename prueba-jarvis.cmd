@echo off
rem Prueba de Jarvis de punta a punta SIN Telegram: arranca Jarvis, le pide por el buzon que de de alta un cliente ficticio
rem en la Empresa de prueba, espera al resultado y lo apaga. Deja registro\jarvis-prueba.log y datos\buzon\salida\respuestas.txt
setlocal enabledelayedexpansion
cd /d C:\Proyectos\asistente-altas
if not exist datos\buzon\entrada mkdir datos\buzon\entrada
del datos\parar.txt >nul 2>&1
del datos\buzon\salida\respuestas.txt >nul 2>&1
call asistente.cmd compilar > registro\jarvis-prueba.log 2>&1
set "TAG=%TIME:~6,2%%TIME:~9,2%"
set "NIF=9971%TIME:~3,2%%TIME:~6,2%Z"
set "NIF=%NIF: =0%"
start "jarvis-prueba" /b cmd /c "call asistente.cmd jarvis --sin-ventana >> registro\jarvis-prueba.log 2>&1"
ping -n 13 127.0.0.1 >nul
echo estado> datos\buzon\entrada\1.tmp
move /y datos\buzon\entrada\1.tmp datos\buzon\entrada\1.txt >nul
ping -n 9 127.0.0.1 >nul
echo Da de alta en la Empresa de prueba un cliente ficticio llamado JARVIS PRUEBA %TAG% con NIF %NIF%, telefono 600 11 22 33, direccion Calle de Proba 9, y codigo postal 15001> datos\buzon\entrada\2.tmp
move /y datos\buzon\entrada\2.tmp datos\buzon\entrada\2.txt >nul
set /a ESPERA=0
:espera
ping -n 11 127.0.0.1 >nul
set /a ESPERA+=10
findstr /C:"HECHO" /C:"PARADO" /C:"FALL" /C:"YA HECHA" /C:"roto" datos\buzon\salida\respuestas.txt >nul 2>&1 && goto fin
if !ESPERA! lss 400 goto espera
:fin
echo estado> datos\buzon\entrada\3.tmp
move /y datos\buzon\entrada\3.tmp datos\buzon\entrada\3.txt >nul
ping -n 9 127.0.0.1 >nul
echo parar> datos\parar.txt
ping -n 7 127.0.0.1 >nul
echo ===== RESPUESTAS DE JARVIS =====
type datos\buzon\salida\respuestas.txt
echo ===== FIN =====
