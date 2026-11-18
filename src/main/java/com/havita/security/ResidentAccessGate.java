package com.havita.security;

import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Acceso vigente de un RESIDENT a una comunidad, leido de PostgreSQL en cada peticion: membresia ACTIVE y al
 * menos una relacion ACTIVE con una unidad (la misma regla con la que el login rechaza ACCESS_REVOKED).
 *
 * El JWT conserva la membresia hasta que caduca; sin esta comprobacion, un residente dado de baja de su ultima
 * unidad seguiria usando los endpoints de la comunidad. Solo se aplica al rol RESIDENT y a la comunidad de la
 * peticion: sus otras comunidades no se ven afectadas.
 */
@Component
@RequiredArgsConstructor
public class ResidentAccessGate {

    private final MembershipRepository membershipRepository;
    private final ResidentRepository residentRepository;

    @Transactional(readOnly = true)
    public boolean hasCommunityAccess(UUID userId, String email, UUID tenantId) {
        if (userId == null || tenantId == null) {
            return false;
        }
        boolean activeMembership = membershipRepository.findByUserIdAndTenantId(userId, tenantId)
                .map(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .orElse(false);
        return activeMembership && hasActiveResidence(userId, email, tenantId);
    }

    /**
     * SEC-9 (P2): solo la mitad "relacion ACTIVE con una unidad". La usa el filtro, donde {@link AuthorizationResolver}
     * ya acaba de comprobar en esta misma peticion la membresia ACTIVE (y la cuenta ACTIVE) en esa comunidad; repetir
     * esa consulta no anadia proteccion. Ahorra una consulta por peticion de RESIDENT.
     */
    @Transactional(readOnly = true)
    public boolean hasActiveResidence(UUID userId, String email, UUID tenantId) {
        if (userId == null || tenantId == null) {
            return false;
        }
        // Fase 2 / E1: mismo criterio que ResidentUnitResolver (vinculacion por cuenta; el correo solo para registros
        // del censo sin cuenta). Un registro vinculado a OTRA cuenta con el mismo correo ya no cuenta.
        return residentRepository.existsUnitRelation(userId, email, tenantId, ResidentStatus.ACTIVE);
    }
}
