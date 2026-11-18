package com.havita.modules.sync.application;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SseTicketRegistry {

    public record TicketInfo(UUID tenantId, UUID userId, Instant expiresAt) {}

    private final Map<String, TicketInfo> tickets = new ConcurrentHashMap<>();

    public String generateTicket(UUID tenantId, UUID userId) {
        // Los tickets caducados que nunca se usaron se descartan para que el mapa no crezca sin limite.
        Instant now = Instant.now();
        tickets.values().removeIf(t -> !t.expiresAt().isAfter(now));

        // Generar token seguro y opaco, valido por 30 segundos
        String ticket = UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();
        tickets.put(ticket, new TicketInfo(tenantId, userId, now.plusSeconds(30)));
        return ticket;
    }

    public TicketInfo consumeTicket(String ticket) {
        TicketInfo info = tickets.remove(ticket); // Remove it so it cannot be reused
        if (info != null && info.expiresAt().isAfter(Instant.now())) {
            return info;
        }
        return null;
    }
}
