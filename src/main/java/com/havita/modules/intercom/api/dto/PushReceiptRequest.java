package com.havita.modules.intercom.api.dto;

import jakarta.validation.constraints.Size;

/**
 * P8-B: recibo de diagnostico del service worker. {@code r} es el nonce de un solo uso que viajo en el aviso push;
 * {@code s} la etapa (RECEIVED, DISPLAYED o CLICKED). Sin datos personales.
 */
public record PushReceiptRequest(
        @Size(max = 64) String r,
        @Size(max = 16) String s
) {
}
