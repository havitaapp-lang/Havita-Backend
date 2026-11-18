package com.havita.modules.audit.application;

import com.havita.common.api.CorrelationIdFilter;
import com.havita.modules.audit.domain.AuditEvent;
import com.havita.modules.audit.infrastructure.AuditEventRepository;
import com.havita.modules.identity.domain.MembershipRoleChangedEvent;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.security.AuthorizationScope;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Cierre SEC-9 (R3): guarda en la bitacora existente ({@code platform_audit_events}) cada asignacion, cambio o retiro
 * de rol de una membresia, dentro de la transaccion que lo hizo (ver MembershipRoleChangeListener).
 *
 * Registra: usuario afectado, comunidad (columna tenant_id; null = rol global, visible solo para la administracion
 * global), rol anterior y nuevo, quien lo hizo (id, nombre y su rol efectivo), fecha y hora, y el id de correlacion de
 * la peticion. No registra correos, telefonos, tokens ni contrasenas; la IP queda como "-" igual que el resto de
 * eventos de ciclo de vida.
 */
@Component
@RequiredArgsConstructor
public class RoleChangeAuditRecorder {

    public static final String RESOURCE = "Membresias";
    public static final String ASSIGNED = "MEMBERSHIP_ROLE_ASSIGNED";
    public static final String CHANGED = "MEMBERSHIP_ROLE_CHANGED";
    public static final String REMOVED = "MEMBERSHIP_ROLE_REMOVED";

    private final AuditEventRepository auditEventRepository;
    private final UserRepository userRepository;

    @EventListener
    public void onRoleChanged(MembershipRoleChangedEvent change) {
        UUID actorId = currentUserId();
        String actorName = actorId == null ? "Sistema"
                : userRepository.findById(actorId).map(u -> u.getFullName()).orElse("Usuario " + actorId);
        String actorRole = AuthorizationScope.current()
                .filter(a -> a.userId().equals(actorId))
                .map(a -> a.role().name())
                .orElse(actorId == null ? "SYSTEM" : "UNKNOWN");
        String traceId = MDC.get(CorrelationIdFilter.MDC_TRACE_ID_KEY);

        String details = "userId=" + change.userId()
                + ";membershipId=" + change.membershipId()
                + ";tenantId=" + scope(change.tenantId())
                + (change.kind() == MembershipRoleChangedEvent.Kind.CHANGED
                        && !java.util.Objects.equals(change.previousTenantId(), change.tenantId())
                        ? ";previousTenantId=" + scope(change.previousTenantId()) : "")
                + ";previousRole=" + (change.previousRole() != null ? change.previousRole().name() : "NONE")
                + ";newRole=" + (change.newRole() != null ? change.newRole().name() : "NONE")
                + ";actorUserId=" + (actorId != null ? actorId : "SYSTEM")
                + ";traceId=" + (traceId != null ? traceId : "-")
                + ";at=" + OffsetDateTime.now();

        auditEventRepository.save(AuditEvent.builder()
                .timestamp(OffsetDateTime.now())
                .actorName(actorName)
                .actorEmail("-")
                .actorRole(actorRole)
                .action(switch (change.kind()) {
                    case ASSIGNED -> ASSIGNED;
                    case CHANGED -> CHANGED;
                    case REMOVED -> REMOVED;
                })
                .resource(RESOURCE)
                .tenantId(change.tenantId())
                .result("SUCCESS")
                .details(details)
                .ipAddress("-")
                .build());
    }

    private static String scope(UUID tenantId) {
        return tenantId != null ? tenantId.toString() : "GLOBAL";
    }

    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String principal)) {
            return null;
        }
        try {
            return UUID.fromString(principal);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
