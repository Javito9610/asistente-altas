@echo off
cd /d C:\Proyectos\asistente-altas
echo ===== %DATE% %TIME% ===== > probar-expediente.log
call asistente.cmd compilar >> probar-expediente.log 2>&1
call asistente.cmd expediente-prueba --cliente "Lucía" --descripcion MICRO --ref "PRUEBA-1 (AND)" --grabar crear-expediente --sin-aviso 1>> probar-expediente.log 2>&1
echo ===== FIN (codigo %ERRORLEVEL%) ===== >> probar-expediente.log
echo.
echo ---------- RESUMEN ----------
%SystemRoot%\System32\findstr.exe /B /C:"TERMINADO" /C:"PARADO" /C:"TIEMPO" /C:"Pasos:" /C:"ERROR" /C:"=====" probar-expediente.log
echo.
echo Terminado. Si queda algo abierto en MN, cierralo con Cancelar (sin guardar).
pause
