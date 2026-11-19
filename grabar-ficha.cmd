@echo off
cd /d C:\Proyectos\asistente-altas
echo ===== %DATE% %TIME% ===== > grabar-ficha.log
call asistente.cmd compilar >> grabar-ficha.log 2>&1
call asistente.cmd expediente-prueba --solo-ficha --grabar ficha-cliente --sin-aviso 1>> grabar-ficha.log 2>&1
echo ===== FIN (codigo %ERRORLEVEL%) ===== >> grabar-ficha.log
echo.
echo ---------- RESUMEN ----------
%SystemRoot%\System32\findstr.exe /B /C:"TERMINADO" /C:"PARADO" /C:"TIEMPO" /C:"Pasos:" /C:"ERROR" /C:"APRENDIDO" /C:"=====" grabar-ficha.log
echo.
echo Terminado. Si queda algo abierto en MN, cierralo con Cancelar (sin guardar).
pause
