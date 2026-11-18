package com.havita.modules.subscription.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreatePlanRequest(
        @NotBlank(message = "El tier es obligatorio (STARTER, PROFESSIONAL, ENTERPRISE)")
        @Size(max = 50, message = "El tier no puede exceder 50 caracteres")
        String tier,

        @NotBlank(message = "El nombre del plan es obligatorio")
        @Size(max = 100, message = "El nombre no puede exceder 100 caracteres")
        String name,

        @Size(max = 255, message = "El tagline no puede exceder 255 caracteres")
        String tagline,

        @NotNull(message = "El precio bruto por propiedad es obligatorio")
        BigDecimal grossPricePerProperty,

        BigDecimal platformPricePerProperty,

        BigDecimal communitySharePerProperty,

        String currency,

        String billingPeriod,

        Integer maxProperties,

        Long maxStorageBytes,

        Integer maxAdminUsers,

        Integer maxStaffUsers,

        Integer maxPorters,

        Integer maxDocuments,

        Integer maxReservations,

        Boolean popular,

        String features
) {}
