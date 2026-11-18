package com.havita.modules.communication.api.dto;

import com.havita.modules.communication.domain.CommunicationStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCommunicationRequest(
        @NotBlank(message = "El título del comunicado es obligatorio")
        @Size(max = 200, message = "El título no puede exceder 200 caracteres")
        String title,

        @NotBlank(message = "El contenido del comunicado es obligatorio")
        String content,

        @Size(max = 50, message = "La audiencia no puede exceder 50 caracteres")
        String audience,

        @Size(max = 50, message = "La categoría no puede exceder 50 caracteres")
        String category,

        CommunicationStatus status
) {}
