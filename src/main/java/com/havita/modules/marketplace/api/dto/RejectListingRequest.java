package com.havita.modules.marketplace.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectListingRequest(
        @NotBlank(message = "El motivo del rechazo es obligatorio")
        @Size(max = 500)
        String reason
) {}
