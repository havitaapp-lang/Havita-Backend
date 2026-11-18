package com.havita.modules.accounting.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RecordPropertyPaymentRequest(
        @NotNull(message = "El ID de la cuenta de cobro es obligatorio")
        UUID invoiceId,

        @NotNull(message = "El monto pagado es obligatorio")
        @Positive(message = "El monto debe ser superior a cero")
        BigDecimal amountPaid,

        String paymentMethod,

        @Size(max = 100, message = "El número de referencia no puede exceder 100 caracteres")
        String referenceNumber,

        LocalDate paymentDate,

        @Size(max = 500, message = "La URL del comprobante no puede exceder 500 caracteres")
        String receiptUrl,

        String notes
) {}
