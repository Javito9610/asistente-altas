@echo off
rem Asistente Altas: compila si hace falta y ejecuta. Necesita el JDK 21 (java y javac en el PATH o JAVA_HOME).
rem La primera vez descarga JNA (la libreria con la que Java habla con Windows) de Maven Central y comprueba su huella.
setlocal
"%SystemRoot%\System32\chcp.com" 65001 >nul
cd /d "%~dp0"
set "JBIN="
if defined JAVA_HOME set "JBIN=%JAVA_HOME%\bin\"
set "JNA_VER=5.15.0"
set "JNA_JAR=lib\jna-%JNA_VER%.jar"
set "CP=build\main;%JNA_JAR%"
if not exist "%JNA_JAR%" call :jna || goto fallo
if not exist build\main\es\amagna\asistente\App.class goto compilar
if "%~1"=="compilar" goto compilar
goto ejecutar

:compilar
echo Compilando...
if exist build rmdir /s /q build
mkdir build\main build\test
dir /s /b src\main\java\*.java > build\fuentes.txt
"%JBIN%javac" -encoding UTF-8 -cp "%JNA_JAR%" -d build\main @build\fuentes.txt || goto fallo
"%SystemRoot%\System32\xcopy.exe" /e /q /y /i src\main\resources build\main >nul || goto fallo
dir /s /b src\test\java\*.java > build\pruebas.txt
"%JBIN%javac" -encoding UTF-8 -cp "%CP%" -d build\test @build\pruebas.txt || goto fallo
"%JBIN%java" -Dstdout.encoding=UTF-8 -cp "%CP%;build\test" es.amagna.asistente.Comprobaciones || goto fallo
if "%~1"=="compilar" goto fin

:ejecutar
"%JBIN%java" -Dstdout.encoding=UTF-8 -cp "%CP%" es.amagna.asistente.App %*
goto fin

:jna
echo Descargando JNA %JNA_VER% de Maven Central...
if not exist lib mkdir lib
set "URL=https://repo1.maven.org/maven2/net/java/dev/jna/jna/%JNA_VER%/jna-%JNA_VER%.jar"
"%SystemRoot%\System32\curl.exe" -fsSL -o "%JNA_JAR%.tmp" "%URL%" || exit /b 1
"%SystemRoot%\System32\curl.exe" -fsSL -o lib\jna.sha1 "%URL%.sha1" || exit /b 1
set "ESPERADO="
for /f "usebackq tokens=1" %%s in ("lib\jna.sha1") do if not defined ESPERADO set "ESPERADO=%%s"
set "HUELLA="
for /f "usebackq skip=1 tokens=* delims=" %%h in (`""%SystemRoot%\System32\certutil.exe" -hashfile "%JNA_JAR%.tmp" SHA1"`) do if not defined HUELLA set "HUELLA=%%h"
set "HUELLA=%HUELLA: =%"
del lib\jna.sha1
if /i not "%HUELLA%"=="%ESPERADO%" (
  del "%JNA_JAR%.tmp"
  echo La descarga de JNA no coincide con su huella oficial. No se usa.
  exit /b 1
)
move /y "%JNA_JAR%.tmp" "%JNA_JAR%" >nul
echo JNA descargada y comprobada.
exit /b 0

:fallo
echo.
echo Ha fallado la compilacion o las comprobaciones.
exit /b 1

:fin
endlocal
