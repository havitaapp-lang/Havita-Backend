package com.havita.modules.pqrs.api.dto;

import com.havita.modules.pqrs.domain.PqrsRequestStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record PqrsResponse(
        UUID id,
        UUID tenantId,
        UUID propertyId,
        String propertyNumber,
        String propertyName,
        UUID residentId,
        String residentName,
        String category,
        String title,
        String description,
        PqrsRequestStatus status,
        String priority,
        String responseNotes,
        UUID assignedToUserId,
        String assignedToUserName,
        OffsetDateTime resolvedAt,
        OffsetDateTime closedAt,
        List<PqrsTimelineEventResponse> timeline,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public record PqrsTimelineEventResponse(
            UUID id,
            String authorName,
            String authorRole,
            String comment,
            PqrsRequestStatus statusChangedTo,
            OffsetDateTime createdAt
    ) {}
}
