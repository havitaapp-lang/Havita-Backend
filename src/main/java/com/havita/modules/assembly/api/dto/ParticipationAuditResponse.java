package com.havita.modules.assembly.api.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Fase 2 / P5-A: una participacion en el historial de una pregunta (auditoria de la administracion). Incluye las
 * anuladas con su opcion original, quien y cuando las anulo y por que, y la participacion a la que sustituye una nueva
 * emision. Sin nombre ni documento del apoderado.
 */
public record ParticipationAuditResponse(
        UUID participationId,
        UUID propertyId,
        String representationType,
        String status,
        UUID actorUserId,
        OffsetDateTime castAt,
        List<UUID> optionIds,
        UUID supersedesParticipationId,
        UUID identityVerifiedByUserId,
        OffsetDateTime identityVerifiedAt,
        UUID voidedByUserId,
        OffsetDateTime voidedAt,
        String voidReason
) {}
