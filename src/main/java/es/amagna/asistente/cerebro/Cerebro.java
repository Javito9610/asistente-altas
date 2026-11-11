package es.amagna.asistente.cerebro;

import java.util.Map;

/** Quien piensa: recibe la petición completa (formato de la API de Mensajes) y devuelve la respuesta. */
public interface Cerebro {
    Map<String, Object> pensar(Map<String, Object> peticion);
}
