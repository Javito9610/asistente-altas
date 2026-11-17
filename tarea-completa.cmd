@echo off
cd /d C:\Proyectos\asistente-altas
echo ===== %DATE% %TIME% ===== > tarea-completa.log
call asistente.cmd compilar >> tarea-completa.log 2>&1
call asistente.cmd tarea crear-expediente --param-fichero pruebas\expediente-completo.params 1>> tarea-completa.log 2>&1
echo ===== FIN (codigo %ERRORLEVEL%) ===== >> tarea-completa.log
echo.
echo ---------- RESUMEN ----------
%SystemRoot%\System32\findstr.exe /B /C:"AUDITOR" /C:"YA HECHA" /C:"HECHO" /C:"PARADO" /C:"FALLO" /C:"TIEMPO" /C:"  fuera" /C:"  inicio" /C:"  lista" /C:"  formulario" /C:"=====" tarea-completa.log
echo.
pause
