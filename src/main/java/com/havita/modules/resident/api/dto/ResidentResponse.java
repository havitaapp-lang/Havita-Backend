package com.havita.modules.resident.api.dto;

import com.havita.modules.resident.domain.ResidentStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ResidentResponse(
        UUID id,
        UUID tenantId,
        UUID propertyId,
        String propertyNumber,
        String propertyName,
        UUID userId,
        String name,
        String documentType,
        String documentId,
        String type,
        String phone,
        String email,
        Boolean isMainContact,
        String emergencyContactName,
        String emergencyContactPhone,
        ResidentStatus status,
        OffsetDateTime registeredAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
