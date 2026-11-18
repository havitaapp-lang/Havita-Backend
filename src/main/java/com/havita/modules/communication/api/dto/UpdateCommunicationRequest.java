package com.havita.modules.communication.api.dto;

import com.havita.modules.communication.domain.CommunicationStatus;
import jakarta.validation.constraints.Size;

public record UpdateCommunicationRequest(
        @Size(max = 255) String title,
        @Size(max = 5000) String content,
        @Size(max = 255) String audience,
        @Size(max = 100) String category,
        CommunicationStatus status
) {}
