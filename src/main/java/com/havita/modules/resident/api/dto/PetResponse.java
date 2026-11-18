package com.havita.modules.resident.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PetResponse(
        UUID id,
        UUID tenantId,
        UUID propertyId,
        String propertyNumber,
        UUID residentId,
        String residentName,
        String name,
        String type,
        String breed,
        String color,
        Boolean isDangerousBreed,
        String vaccinationCardUrl,
        String photoUrl,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
