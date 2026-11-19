@echo off
setlocal enabledelayedexpansion
rem Prueba de resistencia de la ficha de cliente en la EMPRESA DE PRUEBA: crea N clientes ficticios distintos
rem (nombre y NIF nuevos en cada vuelta, con 3 adjuntos) y cuenta cuantas veces sale entera.
rem Deja: registro\clientes.log y registro\clientes-resumen.txt
set "VUELTAS=3"
cd /d C:\Proyectos\asistente-altas
if not exist registro mkdir registro
set /a BIEN=0
set /a MAL=0
set "NOMBRES=ABEL BRUNO CELIA DARIO ELISA FELIX GALA HUGO IRIS JULIO KARLA LUCAS MARTIN NURIA OSCAR PAULA RAUL SARA TOMAS URSULA"
set /a K=0
echo ===== INICIO %DATE% %TIME% ===== > registro\clientes.log
call asistente.cmd compilar >> registro\clientes.log 2>&1
for %%N in (%NOMBRES%) do (
  set /a K+=1
  if !K! leq %VUELTAS% (
    set "NIF=9970!TIME:~6,2!!TIME:~9,2!!K!Z"
    set "NIF=!NIF: =0!"
    echo ##### VUELTA !K! %%N !TIME! ##### >> registro\clientes.log
    %SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe -NoProfile -Command "(Get-Content -Encoding UTF8 pruebas\cliente-nuevo.params) -replace 'QUICO TESTEO','%%N TESTEO' -replace 'quico.testeo','%%N.testeo' -replace '99887688K','!NIF!' | Set-Content -Encoding UTF8 pruebas\cliente-auto.params"
    call asistente.cmd tarea crear-cliente --param-fichero pruebas\cliente-auto.params --sin-ia >> registro\clientes.log 2>&1
    if !ERRORLEVEL!==0 (
      set /a BIEN+=1
      echo ##### VUELTA !K!: ENTERA ##### >> registro\clientes.log
    ) else (
      set /a MAL+=1
      echo ##### VUELTA !K!: CON FALLO ##### >> registro\clientes.log
    )
  )
)
echo ===== FIN %DATE% %TIME% - enteras: !BIEN! de %VUELTAS% - con fallo: !MAL! ===== >> registro\clientes.log
echo enteras: !BIEN! de %VUELTAS% - con fallo: !MAL! > registro\clientes-resumen.txt
echo enteras: !BIEN! de %VUELTAS% - con fallo: !MAL!
