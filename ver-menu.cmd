@echo off
cd /d C:\Proyectos\asistente-altas
echo ===== %DATE% %TIME% ===== > ver-menu.log
call asistente.cmd compilar >> ver-menu.log 2>&1
call asistente.cmd controles "MNprogram - Abogados" --hondo 7 --a menu-principal.txt 1>> ver-menu.log 2>&1
echo ===== FIN (codigo %ERRORLEVEL%) ===== >> ver-menu.log
echo.
echo ---------- RESUMEN ----------
type ver-menu.log | %SystemRoot%\System32\findstr.exe /B /C:"Escrito" /C:"=====" /C:"No "
echo.
echo Solo ha MIRADO: no ha pulsado nada.
pause
