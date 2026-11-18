package com.havita.modules.staff.api.dto;

import com.havita.modules.staff.domain.ShiftStatus;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ShiftResponse(
        UUID id,
        UUID tenantId,
        UUID staffMemberId,
        String staffMemberName,
        String staffMemberPosition,
        String area,
        LocalDate shiftDate,
        String shiftName,
        LocalTime startTime,
        LocalTime endTime,
        String gate,
        String shiftType,
        ShiftStatus status,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
