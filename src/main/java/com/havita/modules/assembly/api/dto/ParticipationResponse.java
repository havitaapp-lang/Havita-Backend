package com.havita.modules.assembly.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Fase 2 / P4: participacion registrada (voto mediante poder o asistido) o anulada, con el resultado vigente de la
 * pregunta. Sin datos de identificacion del apoderado.
 */
public record ParticipationResponse(
        UUID participationId,
        UUID pollId,
        UUID assemblyId,
        UUID propertyId,
        String representationType,
        String status,
        OffsetDateTime castAt,
        OffsetDateTime voidedAt,
        /** Fase 2 / P5-A: participacion anulada a la que sustituye esta nueva emision (null si es la primera). */
        UUID supersedesParticipationId,
        PollResponse poll
) {}
