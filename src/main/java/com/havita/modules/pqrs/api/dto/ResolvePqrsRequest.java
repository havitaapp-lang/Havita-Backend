package com.havita.modules.pqrs.api.dto;

import jakarta.validation.constraints.Size;

/** Cuerpo opcional de {@code POST /api/v1/pqrs/{id}/resolve}: resumen de la solucion que vera el residente. */
public record ResolvePqrsRequest(
        @Size(max = 1000, message = "La nota de la solución no puede exceder 1000 caracteres")
        String responseNotes
) {}
