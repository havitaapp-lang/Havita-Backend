package com.havita.modules.staff.api.dto;

import com.havita.modules.staff.domain.StaffMemberStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record StaffMemberResponse(
        UUID id,
        UUID tenantId,
        UUID userId,
        String firstName,
        String lastName,
        String fullName,
        String documentType,
        String documentNumber,
        String phone,
        String email,
        String position,
        String area,
        String contractType,
        String company,
        LocalDate startDate,
        StaffMemberStatus status,
        String assignedGate,
        String avatarUrl,
        String observations,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        /** Acceso al sistema en ESTA comunidad (nunca incluye la contrasena). */
        SystemAccess systemAccess
) {
    /** Compatibilidad con quien construye la respuesta sin datos de acceso. */
    public StaffMemberResponse(UUID id, UUID tenantId, UUID userId, String firstName, String lastName, String fullName,
                               String documentType, String documentNumber, String phone, String email, String position,
                               String area, String contractType, String company, LocalDate startDate,
                               StaffMemberStatus status, String assignedGate, String avatarUrl, String observations,
                               OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this(id, tenantId, userId, firstName, lastName, fullName, documentType, documentNumber, phone, email, position,
                area, contractType, company, startDate, status, assignedGate, avatarUrl, observations, createdAt,
                updatedAt, SystemAccess.NONE);
    }

    /**
     * @param enabled    tiene cuenta con membresia de personal en esta comunidad
     * @param loginEmail correo de inicio de sesion
     * @param role       STAFF o SECURITY
     * @param status     ACTIVE / INACTIVE (membresia en esta comunidad)
     * @param shared     la cuenta tambien tiene acceso en otra comunidad: su correo y contrasena solo los cambia su titular
     */
    public record SystemAccess(boolean enabled, String loginEmail, String role, String status, boolean shared) {
        public static final SystemAccess NONE = new SystemAccess(false, null, null, null, false);
    }
}
