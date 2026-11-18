package com.havita.modules.identity.api.dto;

import com.havita.modules.identity.domain.LegalDocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LegalConsentRequest(
        @NotNull(message = "El tipo de documento es obligatorio")
        LegalDocumentType documentType,

        @NotBlank(message = "La versión del documento es obligatoria")
        String documentVersion
) {}
