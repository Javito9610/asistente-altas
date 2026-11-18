@echo off
cd /d C:\Proyectos\asistente-altas
echo ===== %DATE% %TIME% ===== > explorar-usuario.log
call asistente.cmd compilar >> explorar-usuario.log 2>&1
call asistente.cmd expediente-prueba --solo-usuario --sin-aviso 1>> explorar-usuario.log 2>&1
echo ===== FIN (codigo %ERRORLEVEL%) ===== >> explorar-usuario.log
echo.
echo ---------- RESUMEN ----------
%SystemRoot%\System32\findstr.exe /B /C:"TERMINADO" /C:"PARADO" /C:"TIEMPO" /C:"Pasos:" /C:"ERROR" /C:"APRENDIDO" /C:"=====" explorar-usuario.log
echo.
echo ==================== TERMINADO: MIRA ARRIBA EL RESUMEN ====================
echo 
echo Si queda algo abierto en MN, cierralo con Cancelar (sin guardar).
pause
