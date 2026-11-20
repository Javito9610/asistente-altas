@echo off
rem Guarda cifrada la cuenta de correo de Jarvis (direccion + contrasena de aplicacion).
cd /d "%~dp0"
call asistente.cmd compilar
echo.
echo 1) Usuario: escribe la DIRECCION de correo completa (por ejemplo jarvis.amagna@gmail.com) y Enter.
echo 2) Contrasena: la CONTRASENA DE APLICACION (no la normal). En Gmail: myaccount.google.com/apppasswords
echo    (hace falta la verificacion en dos pasos). No se ve al escribir; se pide dos veces.
echo.
call asistente.cmd secreto correo
echo.
echo Listo. Reinicia Jarvis (jarvis.cmd) para que lo use.
pause
