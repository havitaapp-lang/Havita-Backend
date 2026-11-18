package com.havita.modules.assembly.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Fase 2 / P2: documento que el apoderado presenta EN PERSONA a la administracion; debe coincidir con el del poder.
 * Solo se compara; no se guarda de nuevo ni se devuelve.
 */
public record VerifyProxyIdentityRequest(
        @NotBlank(message = "El tipo de documento es obligatorio")
        @Pattern(regexp = "CC|CE|PASAPORTE|PEP", message = "Tipo de documento no valido (CC, CE, PASAPORTE o PEP)")
        String documentType,

        @NotBlank(message = "El numero de documento es obligatorio")
        @Size(max = 50, message = "El numero de documento no puede exceder 50 caracteres")
        String documentNumber
) {}
