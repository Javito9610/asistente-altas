@echo off
rem Cola de pruebas: deja esta ventana abierta. Cualquier fichero cola\NOMBRE.pedir cuyo contenido sea el nombre
rem de un .cmd de esta carpeta (por ejemplo: tarea-cliente-nuevo) se ejecuta, y el resultado queda en cola\hecho-NOMBRE.txt.
rem Solo se ejecutan los .cmd que ya existen en esta carpeta (los del repositorio): nada mas.
setlocal enabledelayedexpansion
cd /d C:\Proyectos\asistente-altas
if not exist cola mkdir cola
echo [%time%] Escuchando la cola (cola\*.pedir). Cierra esta ventana para parar.
:bucle
for %%f in (cola\*.pedir) do (
  set "NOMBRE="
  set /p NOMBRE=<"%%f"
  del "%%f" >nul 2>&1
  if exist "!NOMBRE!.cmd" (
    echo [%time%] Ejecutando !NOMBRE!
    call "!NOMBRE!.cmd" < nul > "cola\hecho-%%~nf.txt" 2>&1
    echo [%time%] Terminado !NOMBRE! >> "cola\hecho-%%~nf.txt"
  ) else (
    echo [%time%] Pedido desconocido: !NOMBRE! > "cola\hecho-%%~nf.txt"
  )
)
timeout /t 3 /nobreak >nul
goto bucle
