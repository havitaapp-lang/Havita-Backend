package com.havita.modules.sync.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Evento de sincronizacion enviado por SSE. {@code audience} decide quien lo recibe dentro de la comunidad y no
 * se serializa: el cliente sigue recibiendo {@code type, tenantId, payload, timestamp}.
 */
public record SyncEvent(
        String type,
        UUID tenantId,
        Map<String, Object> payload,
        OffsetDateTime timestamp,
        @JsonIgnore SyncAudience audience
) {
    public SyncEvent {
        audience = audience == null ? SyncAudience.TENANT : audience;
    }

    public SyncEvent(String type, UUID tenantId, Map<String, Object> payload, OffsetDateTime timestamp) {
        this(type, tenantId, payload, timestamp, SyncAudience.TENANT);
    }

    public SyncEvent(String type, UUID tenantId, Map<String, Object> payload) {
        this(type, tenantId, payload, OffsetDateTime.now(), SyncAudience.TENANT);
    }

    public SyncEvent(String type, UUID tenantId, Map<String, Object> payload, SyncAudience audience) {
        this(type, tenantId, payload, OffsetDateTime.now(), audience);
    }
}
