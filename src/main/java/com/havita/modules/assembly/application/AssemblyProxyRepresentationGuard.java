package com.havita.modules.assembly.application;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.modules.assembly.domain.AssemblyProxyChannel;
import com.havita.modules.assembly.domain.AssemblyProxyStatus;
import com.havita.modules.assembly.infrastructure.AssemblyProxyJdbcRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Fase 2 / P2: guardia real (solo con {@code havita.assembly-proxies.enabled=true}). Lee los poderes con la fila de la
 * unidad ya bloqueada por el llamante; todas las operaciones que cambian el estado de un poder de una asamblea abierta
 * bloquean antes esa misma fila, asi que la lectura ve un estado consistente sin bloqueos adicionales.
 * Los rechazos quedan en el historial de poderes (transaccion propia).
 */
@Component
@ConditionalOnProperty(name = "havita.assembly-proxies.enabled", havingValue = "true")
public class AssemblyProxyRepresentationGuard implements ProxyRepresentationGuard {

    private final AssemblyProxyJdbcRepository proxies;
    private final AssemblyProxyEvents events;

    public AssemblyProxyRepresentationGuard(AssemblyProxyJdbcRepository proxies, AssemblyProxyEvents events) {
        this.proxies = proxies;
        this.events = events;
    }

    /**
     * R1: la delegacion es por unidad y vale para todas las asambleas en las que aplique. Choca con un poder APPROVED o
     * USED de una asamblea NO finalizada. No bloquean los poderes en revision, rechazados, revocados, vencidos ni los de
     * asambleas finalizadas (historicos).
     */
    @Override
    public void assertDelegationAllowed(UUID propertyId, UUID tenantId, UUID actorUserId) {
        proxies.findProxyBlockingDelegation(propertyId, tenantId).ifPresent(p -> {
            events.deniedAction(tenantId, p[1], propertyId, p[0], actorUserId, AssemblyProxyChannel.OWNER,
                    "DELEGATION_BLOCKED_BY_PROXY", null, "DELEGATION_BLOCKED_BY_PROXY");
            throw new BusinessRuleConflictException("DELEGATION_BLOCKED_BY_PROXY",
                    "La unidad tiene un poder de representación aprobado para una asamblea abierta. "
                            + "Revoca el poder antes de delegar el voto.");
        });
    }

    /**
     * R6: con un poder APROBADO para la asamblea, la representacion de la unidad es el apoderado. Un propietario,
     * copropietario o arrendatario delegado que quiera asistir debe revocar antes el poder (el otorgante o la
     * administracion); no se revoca de forma implicita.
     */
    @Override
    public void assertTraditionalAttendanceAllowed(UUID assemblyId, UUID propertyId, UUID tenantId, UUID actorUserId, boolean byAdministration) {
        proxies.findApprovedProxy(assemblyId, propertyId, tenantId).ifPresent(proxyId -> {
            events.deniedAction(tenantId, assemblyId, propertyId, proxyId, actorUserId,
                    byAdministration ? AssemblyProxyChannel.ADMIN : AssemblyProxyChannel.OWNER,
                    "ATTENDANCE_BLOCKED_BY_PROXY", AssemblyProxyStatus.APPROVED, "ATTENDANCE_BLOCKED_BY_PROXY");
            throw new BusinessRuleConflictException("ATTENDANCE_BLOCKED_BY_PROXY",
                    "La unidad tiene un poder de representación aprobado para esta asamblea. "
                            + "Para asistir directamente, el poder debe revocarse antes.");
        });
    }
}
