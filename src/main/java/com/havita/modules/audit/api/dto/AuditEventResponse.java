package com.havita.modules.audit.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,
        OffsetDateTime timestamp,
        String actorName,
        String actorEmail,
        String actorRole,
        String action,
        String resource,
        UUID tenantId,
        String tenantName,
        String result,
        String details,
        String ipAddress,
        OffsetDateTime createdAt
) {}
