@echo off
rem Jarvis: el asistente siempre encendido (ventana + Telegram si esta configurado).
rem   jarvis.cmd            con ventana
rem   jarvis.cmd --sin-ia   sin IA (solo tareas del mapa)
cd /d "%~dp0"
if not exist registro mkdir registro
call asistente.cmd compilar >> registro\jarvis.log 2>&1
call asistente.cmd jarvis %* >> registro\jarvis.log 2>&1
