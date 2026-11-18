@echo off
setlocal enabledelayedexpansion
rem Prueba de resistencia en la EMPRESA DE PRUEBA: repite varias veces el ZIP de Lucia (2 expedientes cada vez:
rem cliente por NIF, contrario por CIF, adjuntos y autorizacion pagina 2) y cuenta cuantas veces sale entero.
rem Deja: registro\ciclos.log (todo, con horas) y registro\ciclos-resumen.txt. Al acabar suena un pitido.
rem Cambia CICLOS para hacer mas o menos vueltas (cada una son unos 3-4 minutos).
set "CICLOS=5"
cd /d C:\Proyectos\asistente-altas
if not exist registro mkdir registro
set /a BIEN=0
set /a MAL=0
echo ===== INICIO %DATE% %TIME% (%CICLOS% ciclos) ===== > registro\ciclos.log
call asistente.cmd compilar >> registro\ciclos.log 2>&1
for /l %%i in (1,1,%CICLOS%) do (
  echo ##### CICLO %%i de %CICLOS% - %TIME% ##### >> registro\ciclos.log
  echo Ciclo %%i de %CICLOS% en marcha...
  call asistente.cmd expedientes pruebas\lectura-lucia-expedientes.json pruebas\zips\lucia-prueba.zip --hacer --repetir >> registro\ciclos.log 2>&1
  if !ERRORLEVEL!==0 (
    set /a BIEN+=1
    echo ##### CICLO %%i: ENTERO ##### >> registro\ciclos.log
    echo Ciclo %%i: ENTERO
  ) else (
    set /a MAL+=1
    echo ##### CICLO %%i: FALLO ##### >> registro\ciclos.log
    echo Ciclo %%i: FALLO
  )
)
echo ===== FIN %DATE% %TIME% - enteros: !BIEN! de %CICLOS% - con fallo: !MAL! ===== >> registro\ciclos.log
if !MAL!==0 (
  echo OK !BIEN! de %CICLOS% ciclos enteros - %DATE% %TIME% > registro\ciclos-resumen.txt
) else (
  echo ROTA !MAL! de %CICLOS% ciclos con fallo - mira registro\ciclos.log - %DATE% %TIME% > registro\ciclos-resumen.txt
)
echo.
type registro\ciclos-resumen.txt
echo.
%SystemRoot%\System32\findstr.exe /B /C:"#####" /C:"=====" /C:"Hechos" registro\ciclos.log
echo.
echo ==================== TERMINADO: MIRA ARRIBA EL RESUMEN ====================
echo 
pause
endlocal
