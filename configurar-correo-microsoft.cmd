@echo off
rem Inicia sesion en Outlook / Microsoft 365 para el correo de Jarvis (una vez, desde el navegador).
rem Antes hay que registrar una aplicacion gratuita: mira docs\JARVIS.md, apartado 14.
cd /d "%~dp0"
call asistente.cmd compilar
echo.
set /p ID=Pega el Application (client) ID y pulsa Enter: 
set /p TENANT=Si tu cuenta es de empresa (Microsoft 365) pega el Directory (tenant) ID; si es Outlook/Hotmail personal, deja vacio y Enter: 
if "%TENANT%"=="" (set TENANT=common)
call asistente.cmd correo-microsoft %ID% %TENANT%
echo.
pause
