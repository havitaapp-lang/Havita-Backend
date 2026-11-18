package com.havita.modules.assembly.api.dto;

import com.havita.modules.assembly.domain.PollStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record PollResponse(
        UUID id,
        UUID tenantId,
        UUID assemblyId,
        String assemblyTitle,
        String question,
        String type,
        List<PollOptionDto> options,
        PollStatus status,
        Boolean showResultsToResidents,
        long totalVotes,
        BigDecimal totalVotedCoefficient,
        OffsetDateTime openedAt,
        OffsetDateTime closedAt,
        Integer durationMinutes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public record PollOptionDto(
            UUID id,
            String text,
            long votesCount,
            BigDecimal coefficientSum,
            BigDecimal percentage
    ) {}
}
