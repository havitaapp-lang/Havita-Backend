package com.havita.modules.assembly.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Fase 2 / E3 (D5): una unidad del residente frente a una pregunta.
 *
 * @param status      VOTED (la unidad ya voto), PENDING (puede votar ahora), NOT_ELIGIBLE (ver reasonCode) o CLOSED
 * @param canVote     true solo si status = PENDING
 * @param reasonCode  motivo estable si no puede votar (p. ej. PROPERTY_NOT_REGISTERED_IN_ASSEMBLY, VOTING_RIGHT_DELEGATED)
 */
public record MyVotingUnitResponse(
        UUID propertyId,
        String label,
        String number,
        List<String> relations,
        String status,
        boolean hasVoted,
        boolean canVote,
        String reasonCode,
        String reasonMessage) {}
