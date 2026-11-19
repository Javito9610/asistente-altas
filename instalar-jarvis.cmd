@echo off
rem Hace que Jarvis se abra solo al iniciar sesion en Windows (acceso directo en la carpeta Inicio del usuario; sin permisos de
rem administrador y sin tocar nada del sistema). Para quitarlo: instalar-jarvis.cmd quitar
setlocal
set "INICIO=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"
set "ACCESO=%INICIO%\Jarvis.lnk"
if /i "%~1"=="quitar" (
  if exist "%ACCESO%" del "%ACCESO%"
  echo Jarvis ya no arranca solo.
  goto fin
)
%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe -NoProfile -Command "$s=(New-Object -ComObject WScript.Shell).CreateShortcut('%ACCESO%'); $s.TargetPath='%~dp0jarvis.cmd'; $s.WorkingDirectory='%~dp0'; $s.WindowStyle=7; $s.Description='Jarvis'; $s.Save()"
if exist "%ACCESO%" (echo Listo: Jarvis arrancara solo al iniciar sesion. Acceso: %ACCESO%) else echo No se pudo crear el acceso directo.
:fin
pause
