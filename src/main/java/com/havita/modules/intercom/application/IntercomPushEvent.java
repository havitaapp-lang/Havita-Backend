package com.havita.modules.intercom.application;

import java.util.UUID;

/**
 * P8-B: cambio de una llamada que debe llegar por Web Push a los dispositivos del residente llamado. Se publica dentro
 * de la transaccion de la llamada y se despacha tras el commit ({@link IntercomPushNotifier}).
 *
 * @param excludeEndpoint endpoint de push del dispositivo que provoco el cambio (el que contesto o rechazo), para no
 *                        avisarle de su propia accion. Solo sirve para EXCLUIR una suscripcion del propio residente;
 *                        nunca para elegir destinatarios. Puede ser {@code null}.
 */
public record IntercomPushEvent(UUID callId, UUID tenantId, Kind kind, String excludeEndpoint) {

    public enum Kind {
        /** Llamada entrante: aviso de timbre. */
        RINGING,
        /** La porteria colgo antes de contestar. */
        CANCELLED,
        /** Nadie contesto a tiempo. */
        MISSED,
        /** El residente rechazo (en otro dispositivo). */
        REJECTED,
        /** El residente contesto (en otro dispositivo). */
        ANSWERED;

        public boolean isEnd() {
            return this != RINGING;
        }
    }
}
