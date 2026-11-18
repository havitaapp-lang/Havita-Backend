package com.havita.modules.communication.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CommunityDocumentResponse(
        UUID id,
        UUID tenantId,
        UUID uploadedByUserId,
        String title,
        String category,
        String fileType,
        String fileSize,
        String fileUrl,
        String storageKey,
        String description,
        Integer downloadsCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
