@echo off
rem Deja Telegram listo: comprueba el token del bot, espera tu primer mensaje y (si tu confirmas aqui) te autoriza.
rem Antes: en Telegram abre @BotFather, escribe /newbot, y luego ejecuta:  asistente.cmd secreto telegram
cd /d "%~dp0"
call asistente.cmd compilar
call asistente.cmd telegram
pause
