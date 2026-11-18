package com.havita.modules.push.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PushSubscriptionResponse(
        UUID id,
        String deviceName,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime lastUsedAt
) {}
