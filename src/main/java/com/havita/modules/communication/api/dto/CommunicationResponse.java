package com.havita.modules.communication.api.dto;

import com.havita.modules.communication.domain.CommunicationStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CommunicationResponse(
        UUID id,
        UUID tenantId,
        UUID authorId,
        String authorName,
        String title,
        String content,
        String audience,
        String category,
        CommunicationStatus status,
        OffsetDateTime publishedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
