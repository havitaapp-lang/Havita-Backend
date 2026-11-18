package com.havita.modules.resident.application;

import com.havita.modules.audit.api.dto.RecordAuditEventRequest;
import com.havita.modules.audit.application.AuditService;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.resident.domain.Resident;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Trazabilidad de bajas y reactivaciones de una relacion residente-unidad en la auditoria existente.
 *
 * Al reactivar se limpian endedAt/endedBy/endReason de la relacion, asi que el registro de auditoria es lo que
 * conserva quien dio de baja, cuando y por que, y quien lo revirtio. Solo guarda identificadores, sin datos de
 * contacto del residente.
 */
@Component
@RequiredArgsConstructor
public class ResidentLifecycleAudit {

    static final String ENDED = "RESIDENT_RELATION_ENDED";
    static final String REACTIVATED = "RESIDENT_RELATION_REACTIVATED";
    static final String REACTIVATION_DENIED = "RESIDENT_REACTIVATION_DENIED";
    static final String OWNERSHIP_TRANSFERRED = "PROPERTY_OWNERSHIP_TRANSFERRED";
    static final String TRANSFER_INVITATION_REGENERATED = "TRANSFER_INVITATION_REGENERATED";

    private final AuditService auditService;
    private final UserRepository userRepository;

    /**
     * SEC-8: transferencia de titularidad hecha por la administracion. {@code method} es ACCOUNT (cuenta existente
     * vinculada de inmediato) o INVITATION (el destinatario se registra con un enlace ligado a su correo).
     */
    public void ownershipTransferred(Resident newOwner, java.util.List<Resident> previousOwners, String method) {
        record(OWNERSHIP_TRANSFERRED, newOwner, "SUCCESS",
                "method=" + method
                        + ";linkedUserId=" + (newOwner.getUser() != null ? newOwner.getUser().getId() : null)
                        + ";previousOwnerResidentIds=" + previousOwners.stream().map(r -> r.getId().toString()).toList());
    }

    /** SEC-8: nuevo enlace para un traspaso pendiente; mismo destinatario y misma transferencia. */
    public void transferInvitationRegenerated(Resident boundOwner, UUID revokedInvitationId, UUID newInvitationId) {
        record(TRANSFER_INVITATION_REGENERATED, boundOwner, "SUCCESS",
                "revokedInvitationId=" + revokedInvitationId + ";newInvitationId=" + newInvitationId);
    }

    public void relationEnded(Resident relation, String endReason) {
        record(ENDED, relation, "SUCCESS", "endReason=" + endReason);
    }

    /** Antes de limpiar los campos de baja: deja constancia de la baja que se revierte y de por que via. */
    public void relationReactivated(Resident relation, String channel) {
        record(REACTIVATED, relation, "SUCCESS", "channel=" + channel
                + ";previousEndReason=" + relation.getEndReason()
                + ";previousEndedAt=" + relation.getEndedAt()
                + ";previousEndedBy=" + (relation.getEndedBy() != null ? relation.getEndedBy().getId() : null));
    }

    /** En su propia transaccion: la operacion denegada se revierte, pero el intento debe quedar registrado. */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void reactivationDenied(Resident relation, String channel) {
        record(REACTIVATION_DENIED, relation, "FORBIDDEN", "channel=" + channel + ";previousEndReason=" + relation.getEndReason());
    }

    private void record(String action, Resident relation, String result, String details) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User actor = null;
        String role = "SYSTEM";
        if (auth != null && auth.getPrincipal() instanceof String principal) {
            try {
                actor = userRepository.findById(UUID.fromString(principal)).orElse(null);
            } catch (IllegalArgumentException ignored) {
                // principal no es un id de usuario (p. ej. anonimo)
            }
            role = auth.getAuthorities().stream().findFirst()
                    .map(a -> a.getAuthority().replace("ROLE_", "")).orElse("SYSTEM");
        }
        auditService.recordEvent(new RecordAuditEventRequest(
                actor != null ? actor.getFullName() : "Sistema",
                actor != null ? actor.getEmail() : "-",
                actor != null ? role : "SYSTEM",
                action,
                "Residentes",
                relation.getTenantId(),
                null,
                result,
                "residentId=" + relation.getId()
                        + ";propertyId=" + (relation.getProperty() != null ? relation.getProperty().getId() : null)
                        + ";userId=" + (relation.getUser() != null ? relation.getUser().getId() : null)
                        + ";" + details
                        + ";at=" + OffsetDateTime.now(),
                "-"
        ));
    }
}
