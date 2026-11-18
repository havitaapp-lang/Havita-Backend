package com.havita.modules.property.api.dto;

import com.havita.modules.property.domain.CommonZoneStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CommonZoneResponse(
        UUID id,
        UUID tenantId,
        String name,
        String description,
        Integer capacity,
        Boolean reservationRequired,
        BigDecimal costPerHour,
        String rules,
        CommonZoneStatus status,
        String disabledMessage,
        String disabledDays,
        String openTime,
        String closeTime,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
