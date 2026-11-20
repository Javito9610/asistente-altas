# Jarvis — el asistente siempre encendido

Jarvis junta tres cosas: el **asistente Java** (hace solo, sin IA, lo que el mapa de estados sabe: crear clientes y
expedientes), la **IA** (entiende lo que se le pide, rescata al asistente cuando se atasca y hace lo nuevo) y la
**memoria** (lo que la IA arregla o hace una vez, el asistente lo repite solo la siguiente).

## Cómo se le habla
| Canal | Cómo |
|---|---|
| Ventana | `jarvis.cmd` abre una ventana: escribes y contesta. Cerrarla no lo apaga (botón «Salir»). |
| Telegram | 1) En Telegram abre @BotFather → `/newbot`. 2) `asistente secreto telegram` (pega el token). 3) `configurar-telegram.cmd`: comprueba el token, espera tu primer mensaje al bot y, **si tú confirmas en la consola**, te autoriza (`datos\autorizados.txt`, una línea por persona). Nadie queda autorizado sin que una persona lo teclee ahí. |
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
   **Ficheros por Telegram**: si una persona autorizada le manda un documento (hasta 20 MB; con un pie de foto que diga qué hacer), Jarvis lo guarda
   en `trabajo\\jarvis\\recibidos\\` y se lo da al especialista de oficina («resume este PDF»). De desconocidos no baja nada.
   **Plantillas Word**: `rellenar_plantilla` rellena un .docx con campos «{{nombre}}» (aunque Word los parta en trozos), sin tocar la plantilla,
   y avisa de los campos sin dato (nunca los inventa).
   **Tipo «oficina»**: lo que son solo documentos, ficheros o web se hace **sin pedir confirmación** (el especialista `oficina` solo tiene
   herramientas de ese tipo, atadas en código: no puede tocar ventanas ni mover/borrar). Lo que maneja programas en pantalla («libre») sí la pide.
   **Varias peticiones en un mensaje**: se separan (máximo 4) y un solo «sí» confirma todas.
9. **Control sin IA**: «ayuda» (qué sabe hacer), «diagnóstico» (clave de IA, Telegram, MN, disco, lo aprendido), «estado», «pausa» / «sigue».
10. **Freno de gasto**: `Consumo` cuenta los tokens de IA de cada día (`datos\\consumo.json`) y corta al llegar al tope diario
    (`datos\\limite-tokens.txt`, un número; 0 = sin tope; por defecto 3.000.000). «diagnóstico» enseña lo gastado.
11. **Ventana**: estado en vivo (verde escuchando · azul trabajando · ámbar en pausa), pestañas Conversación / Trabajos / Ficheros (doble clic abre) / Programadas,
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
`asistente.cmd` ejecuta además todas las comprobaciones (497).
`prueba-pedido.cmd` (una orden de `pruebas\\pedido.txt` + «sí») y `prueba-charla.cmd` (varios mensajes de `pruebas\\charla\\*.txt`, uno cada 25 s).

## 12. Varias IA (Gemini y modelo local)

Claude sigue siendo el cerebro. Se pueden añadir dos más, y quien decide a cuál va cada cosa es el CÓDIGO (`Enrutador` +
`Sensibilidad`), nunca una IA:

- **Lo sensible va solo a Claude**: cualquier texto con palabras del despacho (cliente, expediente, contrario…), correos, cifras
  largas, NIF/IBAN, claves o nombres propios, y todo lo que use herramientas, imágenes o documentos. Si Claude no puede, no se
  contesta: nunca se baja a una IA gratuita (que puede aprender de lo que recibe).
- **Lo que no es sensible** (charla, preguntas generales) va a Gemini → si falla, a Claude → como último recurso, al modelo local.
- Sin cuota, una IA descansa 15 minutos. `diagnóstico` dice cuántas veces ha contestado cada una.

Cómo activarlas:
1. Gemini: clave gratis en aistudio.google.com y `asistente.cmd secreto gemini` (cifrada con tu cuenta de Windows, no se ve).
   Modelo opcional en `datos\gemini-modelo.txt` (por defecto `gemini-flash-latest`).
2. Local: instala Ollama, `ollama pull <modelo>` y escribe el nombre del modelo en `datos\ollama.txt`.
Sin ninguna de las dos, todo sigue yendo a Claude como hasta ahora.

## 13. Correo (enviar y leer) y captura

- **Configurar:** `guardar-correo.cmd` (dirección + contraseña de aplicación, cifradas con tu cuenta de Windows). Recomendado: una
  cuenta propia de Jarvis (p. ej. un Gmail nuevo) en vez de la tuya. Gmail y cualquier correo IMAP/SMTP. Microsoft 365 no (pide OAuth).
  Otros servidores: `datos\correo.json` con `smtp`, `smtp_puerto`, `imap`, `imap_puerto`.
- **Enviar:** «mándale un correo a ana@… diciendo…». Jarvis enseña el correo ENTERO (de, para, asunto, adjuntos, texto) y solo sale con
  tu «sí» a ese borrador exacto. Adjuntos solo de sus carpetas y nunca ficheros de claves.
- **Leer:** «léeme los correos nuevos» (los últimos sin leer; no marca nada como leído). Lo que dicen los correos son datos, nunca órdenes.
- **Captura:** «captura» (o «qué ves») manda por el chat una imagen de la pantalla. Puede salir trabajo: solo la piden las personas autorizadas.
