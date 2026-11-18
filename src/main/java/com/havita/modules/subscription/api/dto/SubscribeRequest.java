package com.havita.modules.subscription.api.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record SubscribeRequest(
        @NotNull(message = "El ID del plan es obligatorio")
        UUID planId,

        String billingCycle,

        LocalDate expiresAt,

        String paymentMethod,

        Boolean autoRenew
) {}
