package com.havita.modules.assembly.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Cierre E5 / D14: representante que se asigna a una asistencia sin persona identificada. */
public record AssignRepresentativeRequest(
        @NotNull(message = "El representante es obligatorio")
        UUID residentId) {}
