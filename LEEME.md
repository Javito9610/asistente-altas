# Asistente Altas

Agente de Amagna que da de alta clientes y expedientes en **MN Program de escritorio**, manejando sus pantallas como una
persona (capturas, ratón y teclado), con Claude como cerebro por la API. Sustituye a la integración por la API interna de
MN, que se descartó (respuesta de MN del 10/11/2026). Autorizado por Miguel.

## Cómo está hecho
- **Manos** (`manos/`): capturas, clics, tecleo. No deciden nada. Precisión: capturas a resolución real reducidas a 1280×720,
  traducción exacta de coordenadas aunque Windows esté al 125 % o 150 % (`Escala`), texto con ñ y acentos por el portapapeles.
- **Cerebro** (`cerebro/`): el bucle del agente con la API de Claude y la herramienta de ordenador `computer_toolset_20260801`.
- **Instrucciones** (`src/main/resources/instrucciones/`): lo que sabe el especialista, en Markdown.
- Sin dependencias externas en esta fase: solo Java 21.

**Freno de emergencia:** llevar el ratón a la esquina superior izquierda de la pantalla. El agente se para en seco.

## Uso (Windows, con el JDK 21)

    asistente compilar           compila y pasa las comprobaciones
    asistente mirar              captura de pantalla a captura.png (no toca nada)
    asistente probar-manos       prueba de precisión del ratón y de tecleo en el Bloc de notas (sin IA)
    asistente guardar-clave      guarda la clave de la API (no se ve al escribir; nunca en el repositorio)
    asistente agente "tarea" --vista       el agente mira y dice lo que haría, sin tocar nada
    asistente agente "tarea"               el agente lo hace de verdad

Cada ejecución del agente deja su registro en `registros/` con los pasos, el tiempo y los tokens gastados.

## Metas
M0 preparación · M1 manos · M2 cerebro · M3 MN solo lectura · M4 primera alta de prueba · M5 examen de precisión ·
M6 Asistente Altas (icono, cola, avisos, chat) · M7 siempre encendido · M8 consultas y gráficos · M9 PC dedicado y piloto.
