# Cómo funciona el asistente y cómo probarlo (estado a 19/11/2026)

## Capas, de más barata a más cara
1. **Mapa de estados** (`estados/mn.json`): cada pantalla de MN se reconoce por sus ventanas; cada transición tiene postcondición. Sin IA.
2. **Recuperador** (`estados/Recuperador.java`): antes de cada transición y tras cada fallo devuelve el teclado a MN si otra ventana se lo ha quedado y cierra los visores que MN abre con nuestros documentos. Anota en `registro\recuperaciones.tsv`.
3. **Limpieza con el mapa**: si algo falla, el planificador sale de la pantalla en la que esté (nunca por transiciones que guardan: `toca`).
4. **IA de guardia** (solo si el mapa no sabe salir; máximo 2 empujones).
5. **Auditor**: la IA solo transcribe lo que ve; el código decide si coincide.

## Principios
- Rutas alternativas: `_sin_ventana` (el paso solo se hace si esa ventana no ha salido), `_si`, `_opcional`.
- Clics por posición solo como último recurso; hay anclas de imagen aprendidas, y `cerrar_ventana_mn` cierra por el aspa de Windows sin coordenadas.
- Si un fallo deja una foto: `registro\fallo-*.png` (ventana de MN de delante, solo en la Empresa de prueba).

## Probar sin tocar la pantalla (cola)
1. Deja abierto `escuchar.cmd` (una vez). No pulses Ctrl+C en esa ventana.
2. Para lanzar algo, crear `cola\X.pedir` con el nombre de un `.cmd` de esta carpeta (p. ej. `prueba-clientes`). Resultado en `cola\hecho-X.txt` y en el log del propio `.cmd`.
3. Durante una prueba: no hacer capturas ni usar el teclado (le roban el foco a MN).

## Pruebas
- `asistente.cmd` compila y pasa las comprobaciones automáticas.
- `prueba-ciclos.cmd`: 5 ciclos de expedientes. `prueba-clientes.cmd`: N fichas de cliente nuevas (nombre/NIF distintos) con 3 adjuntos.
- Tras cada cambio importante: `prueba-ciclos.cmd` para no romper expedientes.

## Limpieza pendiente en la Empresa de prueba (con la oficina)
Clientes ficticios: DANIEL, ELENA, MARTA, NOEMI, OLGA, PEDRO, QUICO y los de `prueba-clientes`; expedientes de prueba de los ciclos. Importar adjuntos deja los archivos aunque se cancele la ficha.
