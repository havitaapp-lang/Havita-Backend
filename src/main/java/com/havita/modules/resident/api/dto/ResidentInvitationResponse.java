package com.havita.modules.resident.api.dto;

import com.havita.modules.resident.domain.InvitationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ResidentInvitationResponse(
        UUID id,
        UUID propertyId,
        String propertyName,
        InvitationStatus status,
        String invitationType,
        Integer maxUses,
        Integer usedCount,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt,
        OffsetDateTime usedAt,
        String link
) {
}
