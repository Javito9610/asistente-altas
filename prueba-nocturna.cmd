@echo off
rem Prueba de humo en la EMPRESA DE PRUEBA: crea un expediente ficticio, lo audita y apunta si MN ha cambiado.
rem Pensada para el Programador de tareas de Windows (sesion iniciada y pantalla desbloqueada).
rem Deja: registro\nocturna.log (todo) y registro\nocturna-estado.txt (una linea: OK o ROTA, con fecha).
cd /d C:\Proyectos\asistente-altas
if not exist registro mkdir registro
echo ===== %DATE% %TIME% ===== >> registro\nocturna.log
call asistente.cmd tarea crear-expediente --repetir --param "descripcion=PRUEBA-NOCTURNA" --param "cliente=LUCIA FERNANDEZ PRUEBA" --param "cliente_nombre=LUCIA FERNANDEZ PRUEBA" --clave "nocturna-%DATE%-%TIME%" >> registro\nocturna.log 2>&1
if %ERRORLEVEL%==0 (
  echo OK %DATE% %TIME% > registro\nocturna-estado.txt
) else (
  echo ROTA %DATE% %TIME% - mira registro\nocturna.log > registro\nocturna-estado.txt
)
