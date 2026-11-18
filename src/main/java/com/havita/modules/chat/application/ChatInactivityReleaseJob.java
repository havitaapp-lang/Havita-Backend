package com.havita.modules.chat.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Fase 3 — si el portero responsable no responde a un residente en {@code havita.chat.inactivity-release-minutes}
 * minutos (5 por defecto), la conversacion vuelve a PENDING para que otro portero la atienda (p. ej. termino su turno
 * sin liberarla). Se revisa cada minuto. Ver {@link ChatService#releaseInactive}.
 */
@Slf4j
@Component
public class ChatInactivityReleaseJob {

    private final ChatService chatService;
    private final long minutes;

    public ChatInactivityReleaseJob(ChatService chatService,
                                    @Value("${havita.chat.inactivity-release-minutes:5}") long minutes) {
        this.chatService = chatService;
        this.minutes = minutes;
    }

    @Scheduled(fixedDelayString = "${havita.chat.inactivity-check-ms:60000}", initialDelayString = "${havita.chat.inactivity-check-ms:60000}")
    public void releaseInactive() {
        if (minutes <= 0) return; // desactivado
        try {
            int released = chatService.releaseInactive(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(minutes));
            if (released > 0) {
                log.info("[Chat] {} conversacion(es) liberadas: el responsable no respondio en {} min", released, minutes);
            }
        } catch (Exception e) {
            log.warn("[Chat] Fallo la liberacion por inactividad: {}", e.getClass().getSimpleName());
        }
    }
}
