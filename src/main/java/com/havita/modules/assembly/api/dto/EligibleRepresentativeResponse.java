package com.havita.modules.assembly.api.dto;

import java.util.UUID;

/**
 * Cierre E5 / D14: persona de la unidad que la administracion puede registrar como representante en una asamblea.
 *
 * @param eligible   true si puede representar a la unidad ahora (misma regla que el registro)
 * @param reasonCode motivo estable si no puede (p. ej. VOTING_RIGHT_DELEGATED, VOTING_RIGHT_NOT_DELEGATED, NO_VOTING_RIGHTS)
 */
public record EligibleRepresentativeResponse(
        UUID residentId,
        String name,
        String type,
        boolean eligible,
        String reasonCode,
        String reasonMessage) {}
