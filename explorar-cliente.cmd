@echo off
cd /d C:\Proyectos\asistente-altas
echo ===== %DATE% %TIME% ===== > explorar-cliente.log
call asistente.cmd compilar >> explorar-cliente.log 2>&1
call asistente.cmd expediente-prueba --solo-cliente --sin-aviso 1>> explorar-cliente.log 2>&1
echo ===== FIN (codigo %ERRORLEVEL%) ===== >> explorar-cliente.log
echo.
echo ---------- RESUMEN ----------
%SystemRoot%\System32\findstr.exe /B /C:"TERMINADO" /C:"PARADO" /C:"TIEMPO" /C:"Pasos:" /C:"ERROR" /C:"APRENDIDO" /C:"=====" explorar-cliente.log
echo.
echo Terminado. Si queda algo abierto en MN, cierralo con Cancelar (sin guardar).
pause
