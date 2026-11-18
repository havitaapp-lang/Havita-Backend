package com.havita.modules.assembly.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Fase 2 / P1: poder de representacion de una unidad en una asamblea (fila de {@code assembly_proxies}, V102).
 *
 * No es una entidad JPA a proposito: con {@code ddl-auto: update}, Hibernate crearia la tabla en la base real sin las
 * restricciones de V102. Se lee y escribe por JDBC ({@code AssemblyProxyJdbcRepository}).
 *
 * Roles distintos, nunca confundidos: otorgante (relacion de propietario de la unidad), apoderado (cuenta opcional mas
 * identificacion), quien registra ({@code createdByUserId}, el propietario o la administracion) y quien revisa.
 * El poder no da titularidad ni permisos sobre la unidad: solo describe una representacion para una asamblea.
 */
public record AssemblyProxy(
        UUID id,
        UUID tenantId,
        UUID assemblyId,
        UUID propertyId,
        UUID grantorResidentId,
        UUID grantorUserId,
        UUID proxyUserId,
        String proxyFullName,
        String proxyDocumentType,
        String proxyDocumentNumber,
        AssemblyProxyStatus status,
        String documentKey,
        String documentContentType,
        long documentSizeBytes,
        String documentSha256,
        OffsetDateTime documentRegisteredAt,
        UUID documentRegisteredByUserId,
        UUID createdByUserId,
        AssemblyProxyChannel createdChannel,
        UUID reviewedByUserId,
        OffsetDateTime reviewedAt,
        String reviewDecision,
        String rejectionReason,
        UUID revokedByUserId,
        OffsetDateTime revokedAt,
        String revocationReason,
        OffsetDateTime usedAt,
        OffsetDateTime expiredAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long version
) {}
