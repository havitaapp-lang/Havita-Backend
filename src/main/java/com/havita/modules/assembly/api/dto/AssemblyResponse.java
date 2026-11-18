package com.havita.modules.assembly.api.dto;

import com.havita.modules.assembly.domain.AssemblyStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AssemblyResponse(
        UUID id,
        UUID tenantId,
        String title,
        String type,
        String modality,
        LocalDate assemblyDate,
        LocalTime startTime,
        AssemblyStatus status,
        Integer totalProperties,
        Integer registeredAttendees,
        BigDecimal quorumPercentage,
        long pollsCount,
        String description,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
