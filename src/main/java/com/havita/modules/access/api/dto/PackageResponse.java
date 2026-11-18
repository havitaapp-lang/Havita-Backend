package com.havita.modules.access.api.dto;

import com.havita.modules.access.domain.PackageStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PackageResponse(
        UUID id,
        UUID tenantId,
        UUID propertyId,
        String propertyNumber,
        String propertyName,
        String recipientName,
        String trackingNumber,
        String company,
        PackageStatus status,
        OffsetDateTime receivedAt,
        OffsetDateTime deliveredAt,
        String deliveredTo,
        UUID securityGuardId,
        String observations,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
