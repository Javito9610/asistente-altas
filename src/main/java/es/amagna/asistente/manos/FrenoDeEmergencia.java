package es.amagna.asistente.manos;

/** Alguien ha llevado el ratón a la esquina superior izquierda: el agente se para en seco, sin hacer nada más. */
public final class FrenoDeEmergencia extends RuntimeException {
    public FrenoDeEmergencia() {
        super("Freno de emergencia: ratón en la esquina superior izquierda. El agente se ha parado.");
    }
}
