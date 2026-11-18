package com.havita.modules.assembly.api.dto;

import com.havita.modules.assembly.domain.VotingDelegationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record VotingDelegationResponse(
        UUID id,
        UUID propertyId,
        UUID ownerUserId,
        String ownerUserName,
        UUID delegateUserId,
        String delegateUserName,
        VotingDelegationStatus status,
        OffsetDateTime grantedAt,
        OffsetDateTime revokedAt
) {}
