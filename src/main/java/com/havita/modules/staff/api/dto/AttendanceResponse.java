package com.havita.modules.staff.api.dto;

import com.havita.modules.staff.domain.AttendanceRecordStatus;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AttendanceResponse(
        UUID id,
        UUID tenantId,
        UUID staffMemberId,
        String staffMemberName,
        String staffMemberPosition,
        UUID shiftId,
        LocalDate recordDate,
        LocalTime scheduledCheckIn,
        OffsetDateTime checkIn,
        LocalTime scheduledCheckOut,
        OffsetDateTime checkOut,
        String gate,
        AttendanceRecordStatus status,
        String observations,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
