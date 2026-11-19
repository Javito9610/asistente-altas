@echo off
rem Jarvis: el asistente siempre encendido (ventana + Telegram si esta configurado). Si se cae, se levanta solo a los 15 s.
rem   jarvis.cmd            con ventana
rem   jarvis.cmd --sin-ia   sin IA (solo tareas del mapa)
cd /d "%~dp0"
if not exist registro mkdir registro
:otra
call asistente.cmd compilar >> registro\jarvis.log 2>&1
call asistente.cmd jarvis %* >> registro\jarvis.log 2>&1
if %ERRORLEVEL%==0 goto fin
echo [%DATE% %TIME%] Jarvis se cayo (codigo %ERRORLEVEL%); se levanta de nuevo. >> registro\jarvis.log
%SystemRoot%\System32\ping.exe -n 16 127.0.0.1 >nul
goto otra
:fin
