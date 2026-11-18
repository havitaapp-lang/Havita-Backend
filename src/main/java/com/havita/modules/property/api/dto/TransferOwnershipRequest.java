package com.havita.modules.property.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import java.time.OffsetDateTime;

public record TransferOwnershipRequest(
        @NotBlank(message = "El nombre del nuevo propietario es obligatorio")
        String newOwnerName,

        UUID newOwnerUserId,

        @jakarta.validation.constraints.Email(message = "El correo del nuevo propietario no es valido")
        String newOwnerEmail,

        String newOwnerPhone,

        String newOwnerDocumentId,

        @NotNull(message = "La fecha efectiva es obligatoria")
        OffsetDateTime effectiveDate,
        
        String reason
) {}