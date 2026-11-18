package com.havita.modules.reservation.api.dto;

import com.havita.modules.reservation.domain.ReservationStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ReservationResponse(
        UUID id,
        UUID tenantId,
        UUID commonZoneId,
        String commonZoneName,
        UUID propertyId,
        String propertyNumber,
        String propertyName,
        UUID residentId,
        String residentName,
        UUID createdByUserId,
        LocalDate reservationDate,
        LocalTime startTime,
        LocalTime endTime,
        Integer guestsCount,
        BigDecimal totalCost,
        ReservationStatus status,
        String observations,
        String adminNotes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
