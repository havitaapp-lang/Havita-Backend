package com.havita.modules.realtime.api.dto;

import com.havita.modules.realtime.domain.PanicAlertStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PanicAlertResponse(
        UUID id,
        String alertType,
        String alertTypeLabel,
        String notes,
        PanicAlertStatus status,
        // Residente
        UUID residentId,
        String residentName,
        // Propiedad
        UUID propertyId,
        String propertyPath,
        String propertyNumber,
        // Quien tomó la alerta
        UUID acknowledgedById,
        String acknowledgedByName,
        OffsetDateTime acknowledgedAt,
        // Quien resolvió
        UUID resolvedById,
        String resolvedByName,
        OffsetDateTime resolvedAt,
        String resolutionNotes,
        // Timestamps
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
