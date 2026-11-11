@echo off
rem Asistente Altas (pruebas): compila si hace falta y ejecuta. Necesita el JDK 21 (java y javac en el PATH o JAVA_HOME).
setlocal
"%SystemRoot%\System32\chcp.com" 65001 >nul
cd /d "%~dp0"
set "JBIN="
if defined JAVA_HOME set "JBIN=%JAVA_HOME%\bin\"
if not exist build\main\es\amagna\asistente\App.class goto compilar
if "%~1"=="compilar" goto compilar
goto ejecutar

:compilar
echo Compilando...
if exist build rmdir /s /q build
mkdir build\main build\test
dir /s /b src\main\java\*.java > build\fuentes.txt
"%JBIN%javac" -encoding UTF-8 -d build\main @build\fuentes.txt || goto fallo
"%SystemRoot%\System32\xcopy.exe" /e /q /y /i src\main\resources build\main >nul || goto fallo
dir /s /b src\test\java\*.java > build\pruebas.txt
"%JBIN%javac" -encoding UTF-8 -cp build\main -d build\test @build\pruebas.txt || goto fallo
"%JBIN%java" -Dstdout.encoding=UTF-8 -cp build\main;build\test es.amagna.asistente.Comprobaciones || goto fallo
if "%~1"=="compilar" goto fin

:ejecutar
"%JBIN%java" -Dstdout.encoding=UTF-8 -Dsun.java2d.uiScale.enabled=true -cp build\main es.amagna.asistente.App %*
goto fin

:fallo
echo.
echo Ha fallado la compilacion o las comprobaciones.
exit /b 1

:fin
endlocal
