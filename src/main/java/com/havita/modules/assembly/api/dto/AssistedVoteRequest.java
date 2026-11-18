package com.havita.modules.assembly.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Fase 2 / P4: voto ASISTIDO de un apoderado EXTERNO (sin cuenta) presente. La administracion verifica en persona el
 * documento (debe coincidir con el del poder; solo se compara, no se guarda de nuevo ni se devuelve) y confirma
 * explicitamente que el voto lo expreso el apoderado. Sin fotografias ni archivos.
 */
public record AssistedVoteRequest(
        @NotNull(message = "El poder es obligatorio")
        UUID proxyId,

        @NotBlank(message = "El tipo de documento es obligatorio")
        @Pattern(regexp = "CC|CE|PASAPORTE|PEP", message = "Tipo de documento no valido (CC, CE, PASAPORTE o PEP)")
        String documentType,

        @NotBlank(message = "El numero de documento es obligatorio")
        @Size(max = 50, message = "El numero de documento no puede exceder 50 caracteres")
        String documentNumber,

        @NotNull(message = "Confirma que el voto lo expresó el apoderado presente")
        @AssertTrue(message = "Confirma que el voto lo expresó el apoderado presente")
        Boolean expressedByProxyConfirmed,

        @NotEmpty(message = "Debe seleccionar al menos una opción")
        List<UUID> optionIds
) {}
