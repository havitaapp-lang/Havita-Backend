package com.havita.modules.resident.application;

import com.havita.modules.resident.domain.Resident;

import java.util.UUID;

/**
 * Fase 2 / P0-C (R5): la regla de una sola residencia no propietaria activa es global (toda Havita), asi que la relacion
 * que bloquea una operacion puede pertenecer a otra comunidad. Los mensajes solo nombran la unidad cuando es de la
 * comunidad en la que se opera; si es de otra, no revelan ni la comunidad ni la unidad.
 */
final class ResidenceConflictMessages {

    private ResidenceConflictMessages() {}

    /** "la unidad X" si {@code conflicting} es de {@code tenantId}; si no, un texto generico. */
    static String otherUnit(Resident conflicting, UUID tenantId) {
        boolean sameCommunity = tenantId != null && tenantId.equals(conflicting.getTenantId());
        if (sameCommunity && conflicting.getProperty() != null && conflicting.getProperty().getName() != null) {
            return "la unidad " + conflicting.getProperty().getName();
        }
        return "otra unidad";
    }
}
