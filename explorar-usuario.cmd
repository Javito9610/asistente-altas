@echo off
cd /d C:\Proyectos\asistente-altas
echo ===== %DATE% %TIME% ===== > explorar-usuario.log
call asistente.cmd compilar >> explorar-usuario.log 2>&1
call asistente.cmd expediente-prueba --solo-explorar --sin-aviso 1>> explorar-usuario.log 2>&1
echo ===== FIN (codigo %ERRORLEVEL%) ===== >> explorar-usuario.log
echo.
echo ---------- RESUMEN ----------
%SystemRoot%\System32\findstr.exe /B /C:"TERMINADO" /C:"PARADO" /C:"TIEMPO" /C:"Pasos:" /C:"ERROR" /C:"=====" explorar-usuario.log
echo.
echo Terminado. Si queda algo abierto en MN, cierralo con Cancelar (sin guardar).
pause
