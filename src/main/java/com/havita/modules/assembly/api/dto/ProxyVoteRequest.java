package com.havita.modules.assembly.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Fase 2 / P4: voto del apoderado CON CUENTA por la unidad de un poder USED. La unidad y el titulo se derivan del poder
 * (no se aceptan del cliente).
 */
public record ProxyVoteRequest(
        @NotNull(message = "El poder es obligatorio")
        UUID proxyId,

        @NotEmpty(message = "Debe seleccionar al menos una opción")
        List<UUID> optionIds
) {}
