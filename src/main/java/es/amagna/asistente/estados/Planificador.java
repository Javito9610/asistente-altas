package es.amagna.asistente.estados;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** El camino más corto de un estado a otro por las transiciones del mapa (anchura primero). */
public final class Planificador {
    private Planificador() {
    }

    /** Las transiciones a seguir, en orden. Lista vacía = ya estás; null = no hay camino. Solo cuentan las transiciones
     *  de paso (las acciones de dentro de una pantalla, con de == a, no llevan a ningún sitio). */
    public static List<Mapa.Transicion> camino(Mapa mapa, String desde, String hasta) {
        if (desde.equals(hasta)) {
            return List.of();
        }
        Map<String, Mapa.Transicion> vino = new HashMap<>();
        ArrayDeque<String> cola = new ArrayDeque<>();
        cola.add(desde);
        vino.put(desde, null);
        while (!cola.isEmpty()) {
            String e = cola.poll();
            for (Mapa.Transicion t : mapa.transiciones()) {
                if (!t.de().equals(e)) {
                    continue;
                }
                if (t.toca()) {
                    continue; // lo que guarda o envía solo se hace cuando la tarea lo nombra; nunca como camino ni al limpiar
                }
                for (String destino : t.destinos()) {
                    if (destino.equals(t.de()) || vino.containsKey(destino)) {
                        continue;
                    }
                    vino.put(destino, t);
                    if (destino.equals(hasta)) {
                        List<Mapa.Transicion> ruta = new ArrayList<>();
                        for (String x = hasta; vino.get(x) != null; x = vino.get(x).de()) {
                            ruta.add(vino.get(x));
                        }
                        Collections.reverse(ruta);
                        return ruta;
                    }
                    cola.add(destino);
                }
            }
        }
        return null;
    }
}
