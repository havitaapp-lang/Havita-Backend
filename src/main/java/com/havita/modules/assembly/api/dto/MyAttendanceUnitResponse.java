package com.havita.modules.assembly.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Fase 2 / E5: una unidad del residente frente a una asamblea (asistencia por varias unidades).
 *
 * @param status         REGISTERED (la unidad ya tiene asistencia), PENDING (puede registrarla ahora),
 *                       NOT_ELIGIBLE (ver reasonCode) o CLOSED (la asamblea no esta en curso)
 * @param registered     true si la unidad ya tiene asistencia en esta asamblea (la registre yo u otra persona)
 * @param registeredByMe true si la asistencia de la unidad esta a nombre del usuario actual
 * @param canRegister    true solo si status = PENDING
 * @param reasonCode     motivo estable si no puede registrarla (p. ej. VOTING_RIGHT_DELEGATED, NO_VOTING_RIGHTS)
 */
public record MyAttendanceUnitResponse(
        UUID propertyId,
        String label,
        String number,
        List<String> relations,
        String status,
        boolean registered,
        boolean registeredByMe,
        boolean canRegister,
        String reasonCode,
        String reasonMessage,
        /* Cierre E5 / D14: nombre de quien representa a la unidad (solo para las unidades propias del residente). */
        String representativeName) {}
