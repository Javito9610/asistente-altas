# Lo que hace el agente de Miguel (y qué nos falta)

Sacado de la conversación de Miguel con su agente de Claude. Sin datos de clientes: solo las reglas.
Objetivo de Javi: nuestro Jarvis tiene que hacer **todo esto** y sin que nadie esté delante.

## 1. Qué hace el agente de Miguel, de principio a fin

1. Recibe una carpeta o un ZIP por comercial y semana. Dentro hay una carpeta por cliente y, a veces, una por entidad.
2. Lee todos los papeles: DNI/NIE, hoja de encargo, contratos, justificante de la provisión, notas del asesor.
3. Mira en MN qué hay ya (cliente por NIF/NIE y por apellidos; expedientes de ese cliente) **antes de crear nada**.
4. Da de alta la **ficha del cliente** y le adjunta: identidad, hoja de encargo, justificante (+ notas del asesor).
5. Da de alta **un expediente extrajudicial por entidad prestamista**, con cliente y contrario, y le adjunta:
   contratos, autorización (página 2 de 3 de la hoja de encargo), DNI y notas del asesor.
6. Verifica uno a uno en el listado al terminar.
7. Entrega un **informe por cliente y, dentro, por expediente** con lo creado, lo reutilizado y las incidencias.

## 2. Valores fijos en MN

| Campo | Valor |
|---|---|
| Tipo de expediente | Expediente Extrajudicial |
| Estado | Abierto |
| Etapa | PEND. ENVIAR (si da problemas, vacía) |
| Usuario | Lia Silva |
| Tipo asunto / procedimiento | vacíos |
| Ref. Propia | solo iniciales del comercial: (AND) Andrea · (CHA) Chamorro · (AIN) Ainhoa (el «SI/NO» es de un sistema antiguo) |
| Descripción | MICRO · PRESTAMO (T) · REVOLVING (T) · REVOLVING (T) + DILIGENCIAS (ver §3) |
| Número | lo da MN. Al cambiar el tipo, MN pregunta «¿actualizar el número a la serie del tipo?» → **Sí** |

## 3. Reglas de negocio

- **Cliente:** si ya existe por NIF, no se crea otra ficha. Se completa (DNI, teléfono, correo, documentos que falten).
  Nunca se sustituye nada. Si el teléfono es distinto se guardan los dos seguidos; si el correo es distinto se da de
  alta como segundo correo en el listado, dejando el principal. **La ficha del expediente nunca se toca** (consecuencias procesales).
- **Nombre:** manda siempre el documento de identidad, no la carpeta, ni las notas, ni el contrato.
- **Expediente existente:** no se toca (ni adjuntos, ni etapa, ni estado, ni usuario), en la fase que esté. Pero
  cliente + contrario iguales **no basta** para descartar: se comparan los documentos. Mismo contrato/tarjeta →
  se descarta y se reporta. Contratos distintos (p. ej. dos tarjetas) → se crea y se avisa en el informe.
- **Un expediente por sociedad prestamista** (no por marca): «Smartcrédito» y «Arena Finance» son la misma. Dos
  marcas de la misma sociedad → un expediente con los dos contratos (se nombran por la marca). Excepción: tarjetas
  revolving distintas → una por tarjeta.
- **Contrario:** se identifica por denominación social y CIF del contrato. Se usa la ficha existente aunque tenga el
  CIF o el nombre mal si hay motivos más que razonables (mismos dígitos, nombre antiguo tras cambio de denominación).
  **Nunca se cambia el CIF ni la denominación de un contrario.** Si no existe: expediente con el hueco vacío + incidencia
  (lo da de alta una persona). Se avisa siempre en el informe si la coincidencia no es indudable. Extranjeras sin CIF: buscar solo por nombre.
- **Contrato válido:** solo si aparece el nombre del prestatario. La INE/SECCI precontractual no vale. Entidad en
  la carpeta pero no contratada → fuera, con incidencia.
- **Revolving sin contrato:** el expediente **se crea igualmente** si hay recibos o extractos que acrediten la relación.
- **Descripción:** MICRO = microcréditos (importes pequeños, TAE > ~50 %: ID Finance, 4finance/Vivus, Kviku, MyKredit/Trive,
  Avafin, Loaney, Arena, Wandoo, Plazo…). PRESTAMO (T) = resto de préstamos (Caixa, Cetelem, Bankinter). REVOLVING (T) =
  tarjeta con contrato original. REVOLVING (T) + DILIGENCIAS = revolving sin contrato o con novación/actualización.
- **Provisión de fondos:** basta con archivar el justificante. Matrimonio con pago conjunto → el mismo justificante en las dos fichas.
- **El comercial** es quien recibe la provisión (Bizum), no es anomalía.
- **Incidencias que se reportan, no se arreglan:** DNI caducado, NIE antiguo, nombre distinto entre papeles, mismo
  teléfono en dos clientes, provisión que no cuadra / programada / pagada por un tercero, hoja sin DNI, PDF corrupto (se repara).
- **Informe:** por cliente → expedientes (identificados por contrario) → incidencias; «sin incidencias» donde no hay nada.

## 4. Lecciones de MN (trampas que ya pisó él)

- **El importador de Excel NO sirve para altas:** empareja por número y año sin mirar el tipo → pisó un expediente
  judicial real. No lo usamos. Tampoco sube adjuntos. (Decisión: **solo interfaz**.)
- La API de MN no está disponible para nosotros (pendiente de MN/proveedor); se descarta por ahora.
- Al poner la etapa o elegir usuario con Intro, el **estado salta a «Nuevo»** → hay que comprobar «Abierto» antes de Guardar.
- Cajas de búsqueda que concatenan texto; la caja de nombre de archivo tiene límite de longitud (subir en tandas).
- **Cerrar cada ventana de expediente al terminar** (con ~40 abiertas MN deja de abrir el cuadro de archivos). Limpieza cada 10.
- **Nunca cerrar ni relanzar MN:** pide contraseña y no se puede escribir. Traer al frente la ventana que ya está abierta.
- Otras aplicaciones (Bitrix, el panel de Windows, otra persona usando el portapapeles) se cruzan: verificar cada pegado.
- PDFs de Kviku/Moneyman llegan corruptos (4 bytes sobrantes al principio, sin marca de fin): se reparan antes de leer.
- La hoja de encargo firmada: el texto extraído sale en blanco; hay que mirarla **como imagen**.
- Bloqueo de Windows / suspensión paran todo. Sesión de MN caduca. Tarea desatendida = diseñarla para fallar bien:
  cliente a cliente, registro de lo hecho, nunca dar por bueno lo no verificado.

## 5. Dónde estamos nosotros

| Capacidad | Estado |
|---|---|
| Entrar en MN solo en Empresa de prueba | Hecho |
| Leer papeles con Claude (PDF/imagen nativo) y clasificarlos | Hecho; con las reglas de arriba en `altas.md` |
| Parar y avisar ante dudas | Hecho (cualquier duda = parada) |
| Ficha de cliente: buscar por NIF, crear, guardar | Hecho (crear); **falta**: completar ficha existente, 2.º correo |
| Adjuntar documentos a la ficha | **Falta** |
| Crear expediente (tipo, estado, etapa, usuario, descripción, ref., contrario, cliente) | **Falta** |
| Adjuntar al expediente (contratos, autorización, DNI, notas) | **Falta** |
| Separar la página 2 de 3 de la hoja de encargo | **Falta** (PDF) |
| Detectar clientes/expedientes que ya existen y comparar documentos | **Falta** |
| Buscar el contrario por CIF/nombre sin tocarlo + incidencia | **Falta** |
| Reparar PDFs corruptos | **Falta** |
| Incidencias que no paran todo el lote (solo avisan) | **Falta** (hoy una duda para todo el cliente) |
| Informe por cliente → expediente | **Falta** |
| Cola de varios ZIP / carpeta de OneDrive | **Falta** |
| Funcionar sin pantalla (RDP desconectado, servidor) | Hecho (percepción por eventos + dibujar ventana) |
| Trabajar solo en bandeja, sin consola | **Falta** |
| Avisar a Miguel por WhatsApp / correo | **Falta** |
| Interfaz visual tipo Jarvis | **Falta** |

## 6. Orden propuesto (decidido, para no preguntar)

1. Probar en vivo el arreglo de «Guardado» con los ZIP de Pablo, Nuria y Hugo (solo Empresa de prueba).
2. Expediente por interfaz (alta + contrario + adjuntos) en la empresa de prueba, con las reglas de arriba.
3. Detección de duplicados + incidencias que no paran + informe por cliente.
4. Cola y carpeta de OneDrive; luego bandeja + arranque al iniciar sesión.
5. Avisos (WhatsApp/correo) y panel visual.
