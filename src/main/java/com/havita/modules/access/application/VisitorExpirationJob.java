package com.havita.modules.access.application;

import com.havita.modules.access.domain.VisitorStatus;
import com.havita.modules.access.infrastructure.VisitorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Job diario que expira visitas cuyo QR ya no es válido.
 *
 * Regla: una visita AUTHORIZED o PENDING cuya expectedAt (o createdAt si no
 * tiene expectedAt) sea más de 12 horas anterior a la hora actual pasa a EXPIRED.
 *
 * Ejecuta todos los días a las 02:00 AM (hora Colombia, UTC-5).
 * Equivalente UTC: 07:00 AM → cron "0 0 7 * * *"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitorExpirationJob {

    private final VisitorRepository visitorRepository;
    private final com.havita.modules.sync.application.SyncEventPublisher syncEventPublisher;

    /**
     * Revisa cada 5 minutos visitas cuyo tiempo de vigencia (12 horas desde
     * su inicio programado o creación) ya haya finalizado.
     * Cron: cada 5 minutos ("0 * / 5 * * * *")
     */
    @Scheduled(cron = "0 */5 * * * *")
    @Transactional
    public void expireOldVisits() {
        OffsetDateTime threshold = OffsetDateTime.now(ZoneOffset.UTC).minusHours(12);
        List<com.havita.modules.access.domain.Visitor> toExpire =
                visitorRepository.findVisitsToExpire(threshold);

        if (toExpire.isEmpty()) {
            return;
        }

        toExpire.forEach(v -> {
            v.setStatus(VisitorStatus.EXPIRED);
            try {
                syncEventPublisher.publish(
                    new com.havita.modules.sync.domain.SyncEvent(
                        "VISITOR_STATUS_CHANGED",
                        v.getTenantId(),
                        java.util.Map.of("visitorId", v.getId().toString(), "status", VisitorStatus.EXPIRED.name()),
                        com.havita.modules.sync.domain.SyncAudience.unit(v.getProperty() != null ? v.getProperty().getId() : null)
                    )
                );
            } catch (Exception e) {
                log.debug("Error notificando evento SSE para visita expirada: {}", e.getMessage());
            }
        });

        visitorRepository.saveAll(toExpire);

        log.info("[VisitorExpirationJob] {} visitas marcadas como EXPIRED (threshold: {})",
                toExpire.size(), threshold);
    }
}
