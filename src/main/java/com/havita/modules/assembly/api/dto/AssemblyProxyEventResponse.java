package com.havita.modules.assembly.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Fase 2 / P6: un evento del historial de un poder (auditoria; solo administracion). Sin documentos ni identidades. */
public record AssemblyProxyEventResponse(
        String action,
        String result,
        String actorChannel,
        UUID actorUserId,
        String fromStatus,
        String toStatus,
        String reasonCode,
        OffsetDateTime occurredAt
) {}
