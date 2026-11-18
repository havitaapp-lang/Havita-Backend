package com.havita.modules.communication.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDocumentRequest(
        @NotBlank(message = "El título del documento es obligatorio")
        @Size(max = 200, message = "El título no puede exceder 200 caracteres")
        String title,

        @Size(max = 50, message = "La categoría no puede exceder 50 caracteres")
        String category,

        @Size(max = 20, message = "El tipo de archivo no puede exceder 20 caracteres")
        String fileType,

        @NotBlank(message = "El tamaño de archivo es obligatorio")
        @Size(max = 50, message = "El tamaño no puede exceder 50 caracteres")
        String fileSize,

        @NotBlank(message = "La URL del archivo es obligatoria")
        @Size(max = 500, message = "La URL no puede exceder 500 caracteres")
        String fileUrl,

        @Size(max = 500, message = "La clave de almacenamiento no puede exceder 500 caracteres")
        String storageKey,

        @Size(max = 1000, message = "La descripcion no puede exceder 1000 caracteres")
        String description
) {}

