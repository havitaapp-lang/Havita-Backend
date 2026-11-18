package com.havita.modules.realtime.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID tenantId,
        UUID userId,
        UUID propertyId,
        String title,
        String message,
        String type,
        String dataPayload,
        Boolean isRead,
        OffsetDateTime readAt,
        OffsetDateTime createdAt
) {}
