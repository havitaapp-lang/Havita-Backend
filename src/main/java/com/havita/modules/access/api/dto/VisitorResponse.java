package com.havita.modules.access.api.dto;

import com.havita.modules.access.domain.VisitorStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record VisitorResponse(
        UUID id,
        UUID tenantId,
        UUID propertyId,
        String propertyNumber,
        String propertyName,
        UUID residentId,
        UUID createdByUserId,
        String residentName,
        String name,
        String documentId,
        VisitorStatus status,
        OffsetDateTime expectedAt,
        OffsetDateTime enteredAt,
        OffsetDateTime exitedAt,
        String vehiclePlate,
        String observations,
        String qrCode,
        String groupId,
        String groupName,
        String visitPurpose,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        /** Quien registro el ingreso (portero o administrador). Solo en respuestas y eventos para el personal. */
        String enteredByName,
        /** Quien registro la salida. Solo en respuestas y eventos para el personal. */
        String exitedByName
) {
    /** Sin datos del personal (residentes, pase publico, eventos de la unidad). */
    public VisitorResponse(UUID id, UUID tenantId, UUID propertyId, String propertyNumber, String propertyName,
                           UUID residentId, UUID createdByUserId, String residentName, String name, String documentId,
                           VisitorStatus status, OffsetDateTime expectedAt, OffsetDateTime enteredAt,
                           OffsetDateTime exitedAt, String vehiclePlate, String observations, String qrCode,
                           String groupId, String groupName, String visitPurpose, OffsetDateTime createdAt,
                           OffsetDateTime updatedAt) {
        this(id, tenantId, propertyId, propertyNumber, propertyName, residentId, createdByUserId, residentName, name,
                documentId, status, expectedAt, enteredAt, exitedAt, vehiclePlate, observations, qrCode, groupId,
                groupName, visitPurpose, createdAt, updatedAt, null, null);
    }

    public VisitorResponse withStaffNames(String enteredByName, String exitedByName) {
        return new VisitorResponse(id, tenantId, propertyId, propertyNumber, propertyName, residentId, createdByUserId,
                residentName, name, documentId, status, expectedAt, enteredAt, exitedAt, vehiclePlate, observations,
                qrCode, groupId, groupName, visitPurpose, createdAt, updatedAt, enteredByName, exitedByName);
    }
}

