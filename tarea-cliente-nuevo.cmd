@echo off
cd /d C:\Proyectos\asistente-altas
echo ===== %DATE% %TIME% ===== > tarea-cliente-nuevo.log
call asistente.cmd compilar >> tarea-cliente-nuevo.log 2>&1
call asistente.cmd tarea crear-cliente --param-fichero pruebas\cliente-nuevo.params --sin-ia 1>> tarea-cliente-nuevo.log 2>&1
echo ===== FIN (codigo %ERRORLEVEL%) ===== >> tarea-cliente-nuevo.log
echo.
echo ---------- RESUMEN ----------
%SystemRoot%\System32\findstr.exe /B /C:"AUDITOR" /C:"HECHO" /C:"PARADO" /C:"FALLO" /C:"TIEMPO" /C:"  fuera" /C:"  inicio" /C:"  lista" /C:"  alta" /C:"=====" tarea-cliente-nuevo.log
echo.
echo ==================== TERMINADO: MIRA ARRIBA EL RESUMEN ====================
pause
