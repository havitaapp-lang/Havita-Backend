package com.havita.modules.staff.api.dto;

import com.havita.modules.staff.domain.StaffMemberStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record StaffMemberResponse(
        UUID id,
        UUID tenantId,
        UUID userId,
        String firstName,
        String lastName,
        String fullName,
        String documentType,
        String documentNumber,
        String phone,
        String email,
        String position,
        String area,
        String contractType,
        String company,
        LocalDate startDate,
        StaffMemberStatus status,
        String assignedGate,
        String avatarUrl,
        String observations,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
