@echo off
cd /d C:\Proyectos\asistente-altas
echo ===== %DATE% %TIME% ===== > tarea-zip.log
call asistente.cmd compilar >> tarea-zip.log 2>&1
call asistente.cmd expedientes pruebas\lectura-lucia-expedientes.json pruebas\zips\lucia-prueba.zip --hacer --repetir 1>> tarea-zip.log 2>&1
echo ===== FIN (codigo %ERRORLEVEL%) ===== >> tarea-zip.log
echo.
%SystemRoot%\System32\findstr.exe /B /C:"---------- INFORME" /C:"  EXPEDIENTE" /C:"      " /C:"  NO SE CREA" /C:"Hechos" /C:"=====" tarea-zip.log
echo.
echo.
echo ==================== TERMINADO: MIRA ARRIBA EL RESUMEN ====================
echo 
pause
