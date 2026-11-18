package com.havita.modules.push.infrastructure;

import com.havita.modules.push.application.PushNotificationService;
import com.havita.modules.push.application.ResidentPushRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Envia los avisos push a residentes DESPUES del commit de la operacion que los origino (paquete recibido, visita que
 * ingresa, comunicado publicado, asamblea iniciada). El envio es asincrono: nunca retrasa ni rompe la operacion.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResidentPushListener {

    private final PushNotificationService pushNotificationService;

    // fallbackExecution: si quien publica no tiene transaccion, se envia igualmente (no hay commit que esperar).
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onResidentPush(ResidentPushRequested r) {
        try {
            switch (r.scope()) {
                case UNIT -> pushNotificationService.sendToUnitResidents(r.tenantId(), r.propertyId(), r.title(), r.body(), r.type(), r.url());
                case COMMUNITY -> pushNotificationService.sendToCommunityResidents(r.tenantId(), r.residentType(), r.title(), r.body(), r.type(), r.url());
            }
        } catch (Exception e) {
            log.warn("[Push] No se pudo encolar el aviso {}: {}", r.type(), e.getClass().getSimpleName());
        }
    }
}
