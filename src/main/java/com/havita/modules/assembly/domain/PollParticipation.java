package com.havita.modules.assembly.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Fase 2 / P4 — participacion de una unidad en una pregunta ({@code poll_participations}, V104). Sus opciones son las
 * filas de {@code poll_votes} con este {@code participation_id}. Solo un voto asistido puede pasar a VOIDED; sus votos
 * se conservan y dejan de contar. Sin entidad JPA (acceso JDBC): Hibernate no crea la tabla en bases existentes.
 */
public record PollParticipation(
        UUID id,
        UUID tenantId,
        UUID assemblyId,
        UUID pollId,
        UUID propertyId,
        UUID attendeeId,
        RepresentationType representationType,
        UUID actorUserId,
        UUID residentId,
        UUID delegationId,
        UUID proxyId,
        UUID grantorResidentId,
        UUID identityVerifiedByUserId,
        OffsetDateTime identityVerifiedAt,
        String identityVerificationMethod,
        Boolean expressedByProxyConfirmed,
        String status,
        OffsetDateTime castAt,
        UUID voidedByUserId,
        OffsetDateTime voidedAt,
        String voidReason,
        // Fase 2 / P5-A (V105): participacion ANULADA a la que sustituye esta nueva emision asistida (null si es la primera).
        UUID supersedesParticipationId) {

    public static final String VALID = "VALID";
    public static final String VOIDED = "VOIDED";
    public static final String IN_PERSON_DOCUMENT = "IN_PERSON_DOCUMENT";

    /** Persona representada cuando es una relacion de residente: el propietario que vota o el otorgante del poder. */
    public UUID representedResidentId() {
        return switch (representationType) {
            case OWNER, CO_OWNER -> residentId;
            case ACCOUNT_PROXY, ADMIN_ASSISTED_PROXY -> grantorResidentId;
            case TENANT_DELEGATE -> null; // el representado es el propietario delegante (delegation_id)
        };
    }
}
