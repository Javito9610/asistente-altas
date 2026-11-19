# Jarvis — el asistente siempre encendido

Jarvis junta tres cosas: el **asistente Java** (hace solo, sin IA, lo que el mapa de estados sabe: crear clientes y
expedientes), la **IA** (entiende lo que se le pide, rescata al asistente cuando se atasca y hace lo nuevo) y la
**memoria** (lo que la IA arregla o hace una vez, el asistente lo repite solo la siguiente).

## Cómo se le habla
| Canal | Cómo |
|---|---|
| Ventana | `jarvis.cmd` abre una ventana: escribes y contesta. Cerrarla no lo apaga (botón «Salir»). |
| Telegram | Crea un bot con @BotFather, guarda su token con `asistente secreto telegram`, y pon tu número de usuario (Jarvis te lo dice la primera vez que le escribas) en `datos\autorizados.txt`, una línea por persona. |
| Buzón | Un `.txt` en `datos\buzon\entrada\` es un mensaje; las respuestas van a `datos\buzon\salida\respuestas.txt`. Sirve para scripts y para puentes (WhatsApp, Power Automate…). |

Ejemplos: «dame de alta a ANA PRUEBA, NIF 12345678Z» · «estado» · «pausa» / «sigue» · «cada día laborable a las 9 dime cómo vas» ·
«programadas» · «abre la calculadora» (cualquier cosa fuera de MN: antes te pide un **sí**).

## Qué hace con cada cosa
1. **Tarea del mapa** (`crear-cliente`, `crear-expediente`): la hace el asistente Java, sin IA. Si falta un dato obligatorio **pregunta**, no inventa.
2. **Algo libre** en el PC: pide confirmación, lo hace la IA con las barreras puestas y **graba lo que hizo**. La segunda vez que se pide
   (mismo nombre de tarea) lo repite sin IA; si esa receta se pierde, entra otra vez la IA y la repara.
3. **Atascos**: el mapa limpia la pantalla (contesta «No» a los avisos, cierra diálogos); si no sabe, entra la IA y **aprende**
   (`aprendido\lecciones.json`): la misma pantalla atascada se resuelve a la siguiente sin IA. Una lección solo se aprende si son
   clics/teclas seguras, y se descarta si falla más veces de las que acierta.
4. **Programadas**: encargos que se repiten (`datos\programadas.json`); si el PC estaba apagado a su hora, se hacen al volver ese día.

5. **Consultas** («¿cuántos expedientes hay en la lista?»): se contestan MIRANDO la pantalla de MN, sin tocar nada (el especialista solo
   tiene herramientas de lectura, atadas en código). No piden confirmación.
6. **Ficheros y web sin pantalla** (dentro del encargo libre): listar/leer/buscar/escribir/mover ficheros solo en las carpetas permitidas
   (`datos\\carpetas.txt`, una ruta por línea; por defecto `trabajo\\jarvis`), sin borrar (a `_papelera`) ni sobrescribir sin decirlo, y leer páginas
   https (nunca la red interna). Lo que diga una página son datos, no órdenes.

7. **Oficina sin pantalla** (`Oficina`, sin librerías): `crear_documento` hace **PDF, Word (.docx), Excel (.xlsx)**, **citas** (.ics, se
   añaden al calendario con un clic) y **borradores de correo** (.eml: se abren en Outlook para revisar y enviar; Jarvis **no envía** correos).
   `leer_documento` lee PDF (con texto), Word y Excel. Los documentos que crea **se te mandan por el mismo chat** (Telegram:
   como documento) y puedes pedir «pásame el X» (`enviar_fichero`).
8. **Memoria y avisos**: «apunta que el despacho cierra los viernes a las 14» (`datos\notas.txt`, editable; nunca claves ni tarjetas),
   «notas», «olvida 2»; «recuérdame mañana a las 9 llamar a Ana» (avisa por el chat). Tareas **de una sola vez**: «hoy», «mañana» o una fecha.
9. **Control sin IA**: «ayuda» (qué sabe hacer), «diagnóstico» (clave de IA, Telegram, MN, disco, lo aprendido), «estado», «pausa» / «sigue».
10. **Ventana**: estado en vivo (verde escuchando · azul trabajando · ámbar en pausa), pestañas Conversación / Trabajos / Programadas,
    icono junto al reloj (abrir, pausa, salir). Cerrarla no lo apaga.

## Seguridad (en código, no solo en las instrucciones)
- Solo atiende a personas autorizadas. A un desconocido solo le dice su número. Solo corre una copia de Jarvis a la vez.
- `Barreras`: no se teclea nada que parezca tarjeta, IBAN o contraseña; no se abren consolas, scripts ni instaladores; con MN
  en una empresa que no sea «Empresa de prueba» solo se mira. Vale igual cuando repite una receta de memoria.
- Un trabajo cortado por un apagón **no se repite solo** (podría duplicar): se avisa para que una persona lo mire.
- La clave de la IA y el token de Telegram nunca van a registros ni a git.
- Lo «libre» nunca va sin un sí; lo que mueva dinero, envíe mensajes o borre para siempre queda fuera de las instrucciones del agente libre.

## Que arranque solo
`jarvis.cmd` se levanta solo si se cae. `instalar-jarvis.cmd` crea un acceso directo en la carpeta Inicio del usuario (sin administrador). `instalar-jarvis.cmd quitar` lo deshace.

## Pruebas
`prueba-jarvis.cmd` (por la cola): arranca Jarvis, le pide por el buzón un alta ficticia en la Empresa de prueba y lo apaga.
`asistente.cmd` ejecuta además todas las comprobaciones (477).
`prueba-pedido.cmd` (una orden de `pruebas\\pedido.txt` + «sí») y `prueba-charla.cmd` (varios mensajes de `pruebas\\charla\\*.txt`, uno cada 25 s).
