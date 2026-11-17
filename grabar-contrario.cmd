@echo off
cd /d C:\Proyectos\asistente-altas
echo ===== %DATE% %TIME% ===== > grabar-contrario.log
call asistente.cmd compilar >> grabar-contrario.log 2>&1
call asistente.cmd expediente-prueba --cliente "LUCIA FERNANDEZ PRUEBA" --contrario "ALEJANDRO" --adjunto "C:\Proyectos\asistente-altas\pruebas\adjunto-prueba.txt" --descripcion MICRO --grabar contrario-adjunto --sin-aviso 1>> grabar-contrario.log 2>&1
echo ===== FIN (codigo %ERRORLEVEL%) ===== >> grabar-contrario.log
echo.
echo ---------- RESUMEN ----------
%SystemRoot%\System32\findstr.exe /B /C:"TERMINADO" /C:"PARADO" /C:"TIEMPO" /C:"Pasos:" /C:"ERROR" /C:"APRENDIDO" /C:"=====" grabar-contrario.log
echo.
echo Terminado. Si queda algo abierto en MN, cierralo con Cancelar (sin guardar).
pause
