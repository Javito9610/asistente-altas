@echo off
cd /d C:\Proyectos\asistente-altas
echo ===== %DATE% %TIME% ===== > explorar-ficha.log
call asistente.cmd compilar >> explorar-ficha.log 2>&1
call asistente.cmd expediente-prueba --solo-ficha --sin-aviso 1>> explorar-ficha.log 2>&1
echo ===== FIN (codigo %ERRORLEVEL%) ===== >> explorar-ficha.log
echo.
echo ---------- RESUMEN ----------
%SystemRoot%\System32\findstr.exe /B /C:"TERMINADO" /C:"PARADO" /C:"TIEMPO" /C:"Pasos:" /C:"ERROR" /C:"APRENDIDO" /C:"=====" explorar-ficha.log
echo.
echo Terminado. Si queda algo abierto en MN, cierralo con Cancelar (sin guardar).
pause
