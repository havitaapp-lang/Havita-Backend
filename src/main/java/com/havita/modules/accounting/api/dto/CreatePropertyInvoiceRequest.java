package com.havita.modules.accounting.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreatePropertyInvoiceRequest(
        @NotNull(message = "El ID del inmueble es obligatorio")
        UUID propertyId,

        UUID residentId,

        @NotBlank(message = "El período es obligatorio (ej: Septiembre 2026)")
        @Size(max = 100, message = "El período no puede exceder 100 caracteres")
        String period,

        @Size(max = 200, message = "El concepto no puede exceder 200 caracteres")
        String concept,

        @NotNull(message = "El valor base es obligatorio")
        BigDecimal baseAmount,

        BigDecimal lateFeeAmount,
        BigDecimal discountAmount,
        BigDecimal previousBalance,

        @NotNull(message = "La fecha límite de pago es obligatoria")
        LocalDate dueDate,

        LocalDate discountUntilDate
) {}
