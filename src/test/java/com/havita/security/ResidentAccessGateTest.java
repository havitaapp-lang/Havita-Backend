package com.havita.security;

import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A-8: acceso vigente de un RESIDENT a la comunidad (membresia ACTIVE y al menos una relacion ACTIVE). */
@ExtendWith(MockitoExtension.class)
class ResidentAccessGateTest {

    @Mock private MembershipRepository membershipRepository;
    @Mock private ResidentRepository residentRepository;
    @InjectMocks private ResidentAccessGate gate;

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();
    private final String email = "persona@example.test";

    private void membership(MembershipStatus status) {
        when(membershipRepository.findByUserIdAndTenantId(userId, tenantId))
                .thenReturn(Optional.of(Membership.builder().tenantId(tenantId).status(status).build()));
    }

    private Resident relation(ResidentStatus status) {
        return Resident.builder().status(status).type("Propietario").build();
    }

    @Test
    @DisplayName("Membresia ACTIVE y una relacion ACTIVE: tiene acceso")
    void activeAccess() {
        membership(MembershipStatus.ACTIVE);
        // Fase 2 / E1: el estado ACTIVE se filtra en la consulta (existsUnitRelation), con el criterio del resolvedor.
        when(residentRepository.existsUnitRelation(userId, email, tenantId, ResidentStatus.ACTIVE)).thenReturn(true);
        assertTrue(gate.hasCommunityAccess(userId, email, tenantId));
    }

    @Test
    @DisplayName("Todas las relaciones dadas de baja: sin acceso")
    void allRelationsInactive() {
        membership(MembershipStatus.ACTIVE);
        when(residentRepository.existsUnitRelation(userId, email, tenantId, ResidentStatus.ACTIVE)).thenReturn(false);
        assertFalse(gate.hasCommunityAccess(userId, email, tenantId));
    }

    @Test
    @DisplayName("Membresia INACTIVE o ausente, o sin usuario/tenant: sin acceso y sin consultar relaciones")
    void membershipRequired() {
        membership(MembershipStatus.INACTIVE);
        assertFalse(gate.hasCommunityAccess(userId, email, tenantId));
        when(membershipRepository.findByUserIdAndTenantId(userId, tenantId)).thenReturn(Optional.empty());
        assertFalse(gate.hasCommunityAccess(userId, email, tenantId));
        assertFalse(gate.hasCommunityAccess(null, email, tenantId));
        assertFalse(gate.hasCommunityAccess(userId, email, null));
        verify(residentRepository, never()).existsUnitRelation(any(), any(), any(), any());
    }
}
