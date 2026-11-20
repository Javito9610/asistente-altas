@echo off
rem Prueba Gemini solo: dice si contesta y cuanto tarda, o que falla.
cd /d "%~dp0"
call asistente.cmd compilar
call asistente.cmd probar-gemini
pause
