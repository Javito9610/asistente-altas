@echo off
cd /d C:\Proyectos\asistente-altas
echo ===== %DATE% %TIME% ===== > tarea-expediente.log
call asistente.cmd compilar >> tarea-expediente.log 2>&1
call asistente.cmd tarea crear-expediente --param "descripcion=MICRO" --param "cliente=LUCIA FERNANDEZ PRUEBA" --param "cliente_nombre=LUCIA FERNANDEZ PRUEBA" 1>> tarea-expediente.log 2>&1
echo ===== FIN (codigo %ERRORLEVEL%) ===== >> tarea-expediente.log
echo.
echo ---------- RESUMEN ----------
%SystemRoot%\System32\findstr.exe /B /C:"AUDITOR" /C:"YA HECHA" /C:"HECHO" /C:"PARADO" /C:"FALLO" /C:"TIEMPO" /C:"  fuera" /C:"  inicio" /C:"  lista" /C:"  formulario" /C:"=====" tarea-expediente.log
echo.
pause
