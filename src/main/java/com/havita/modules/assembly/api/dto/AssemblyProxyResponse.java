package com.havita.modules.assembly.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Fase 2 / P1: poder visible para quien puede verlo. Nunca incluye la referencia de almacenamiento del documento. */
public record AssemblyProxyResponse(
        UUID id,
        UUID assemblyId,
        UUID propertyId,
        UUID grantorResidentId,
        UUID proxyUserId,
        String proxyFullName,
        String proxyDocumentType,
        String proxyDocumentNumber,
        String status,
        String createdChannel,
        String documentContentType,
        long documentSizeBytes,
        OffsetDateTime documentRegisteredAt,
        OffsetDateTime reviewedAt,
        String reviewDecision,
        String rejectionReason,
        OffsetDateTime revokedAt,
        OffsetDateTime expiredAt,
        OffsetDateTime createdAt,
        /** Fase 2 / P6: numero de la unidad representada (solo para quien ya puede ver el poder). */
        String propertyNumber
) {}
