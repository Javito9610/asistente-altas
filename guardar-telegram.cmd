@echo off
rem Guarda cifrada la clave del bot de Telegram (el que te da @BotFather). Se pega y no se ve al escribir.
cd /d "%~dp0"
call asistente.cmd compilar
echo.
echo Pega la clave y pulsa Enter. No se ve al escribir; es normal.
call asistente.cmd secreto telegram
echo.
echo Listo. Ya puedes cerrar esta ventana.
pause
