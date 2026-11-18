package com.havita.modules.resident.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record VehicleResponse(
        UUID id,
        UUID tenantId,
        UUID propertyId,
        String propertyNumber,
        UUID residentId,
        String residentName,
        String plate,
        String type,
        String brand,
        String model,
        String color,
        String parkingSpot,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
