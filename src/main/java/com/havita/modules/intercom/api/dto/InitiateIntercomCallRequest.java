package com.havita.modules.intercom.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record InitiateIntercomCallRequest(
        @NotNull(message = "La propiedad es obligatoria") UUID propertyId,
        @NotNull(message = "El residente destinatario es obligatorio") UUID recipientResidentId
) {}
