package com.havita.modules.subscription.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangePlanRequest(
        @NotBlank(message = "El planCode es obligatorio")
        String planCode
) {}
