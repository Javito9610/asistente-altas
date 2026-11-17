@echo off
cd /d C:\Proyectos\asistente-altas
echo ===== %DATE% %TIME% ===== > repetir-expediente.log
call asistente.cmd receta crear-expediente --param "cliente=Lucía" --param "descripcion=MICRO" --param "ref=PRUEBA-2 (AND)" 1>> repetir-expediente.log 2>&1
echo ===== FIN (codigo %ERRORLEVEL%) ===== >> repetir-expediente.log
echo.
echo ---------- RESUMEN ----------
%SystemRoot%\System32\findstr.exe /B /C:"HECHO" /C:"FALLO" /C:"FALLÓ" /C:"Receta" /C:"=====" repetir-expediente.log
echo.
pause
