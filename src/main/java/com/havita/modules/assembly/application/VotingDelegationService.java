package com.havita.modules.assembly.application;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.assembly.api.dto.VotingDelegationResponse;
import com.havita.modules.assembly.domain.VotingDelegation;
import com.havita.modules.assembly.domain.VotingDelegationStatus;
import com.havita.modules.assembly.infrastructure.VotingDelegationRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.security.TenantContextHolder;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VotingDelegationService {

    private final VotingDelegationRepository votingDelegationRepository;
    private final com.havita.modules.assembly.infrastructure.AssemblyAttendeeRepository attendeeRepository;
    private final PropertyRepository propertyRepository;
    private final ResidentRepository residentRepository;
    private final UserRepository userRepository;
    private final com.havita.security.AccessValidator accessValidator;
    /** Fase 2 / P2: exclusividad con los poderes de asamblea (sin efecto ni consultas si estan desactivados). */
    private final ProxyRepresentationGuard proxyGuard;

    /** Roles que pueden delegar o revocar (los de la expresion del endpoint, sin la rama global). */
    private static final java.util.Set<com.havita.modules.identity.domain.RoleType> DELEGATION_ROLES = java.util.Set.of(
            com.havita.modules.identity.domain.RoleType.RESIDENT,
            com.havita.modules.identity.domain.RoleType.COMMUNITY_ADMIN,
            com.havita.modules.identity.domain.RoleType.SUPER_ADMIN);

    /**
     * Cierre E3 / E5 — protocolo de bloqueos de los derechos de voto de una unidad, siempre en este orden:
     * membresia del llamante (FOR SHARE) → unidad (FOR NO KEY UPDATE) → asistencias de asambleas abiertas (FOR UPDATE).
     * El registro de asistencia (AssemblyService) toma la misma fila de la unidad antes de comprobar e insertar, asi que
     * delegar o revocar y registrar asistencia nunca pasan sus validaciones a la vez. El voto bloquea la asistencia (E3).
     */
    private void lockVotingRights(UUID propertyId, UUID tenantId) {
        accessValidator.lockAuthorization(DELEGATION_ROLES, java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN));
        if (propertyRepository.lockForVotingRights(propertyId, tenantId).isEmpty()) {
            throw new ResourceNotFoundException("Property", propertyId);
        }
        attendeeRepository.lockOpenByProperty(propertyId, tenantId);
    }

    @Transactional
    public VotingDelegationResponse delegateVote(UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        // Fase 2 / E3 (D8) y cierre E3: unidad y asistencias bloqueadas antes de cualquier comprobacion.
        lockVotingRights(propertyId, tenantId);
        UUID currentUserId = getCurrentUserId();

        Property property = propertyRepository.findByIdAndTenantId(propertyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", propertyId));

        // Cierre E5 (D11): cualquier copropietario ACTIVO puede delegar (antes solo la primera relacion Propietario que
        // devolvia la consulta, sin orden definido). La delegacion queda a nombre de quien la otorga (autor).
        Resident ownerRel = ownerRelationOf(propertyId, tenantId, currentUserId);
        if (ownerRel == null) {
            throw new BusinessRuleConflictException("NOT_THE_OWNER", "Solamente el propietario legítimo y activo puede delegar el derecho de voto.");
        }

        // Validar que exista un arrendatario ACTIVO
        Resident tenantRel = getActiveResident(propertyId, tenantId, "Arrendatario");
        if (tenantRel == null || tenantRel.getUser() == null) {
            throw new BusinessRuleConflictException("NO_ACTIVE_TENANT", "No hay un arrendatario activo registrado en esta unidad para delegarle el voto.");
        }

        // Validar si ya hay una delegación activa
        Optional<VotingDelegation> activeOpt = votingDelegationRepository.findByPropertyIdAndStatusAndTenantId(propertyId, VotingDelegationStatus.ACTIVE, tenantId);
        if (activeOpt.isPresent() && activeOpt.get().getDelegateUser().getId().equals(tenantRel.getUser().getId())) {
            throw new BusinessRuleConflictException("DELEGATION_ALREADY_EXISTS", "El voto ya está delegado a este arrendatario.");
        }

        // Cierre E3 + E5 (D10): no se crea ni activa una delegacion si la unidad ya tiene asistencia registrada en una
        // asamblea ABIERTA: en curso (cierre E3) o programada (D10, p. ej. pre-registro de la administracion). La
        // delegacion es por unidad (vale para todas sus asambleas), asi que cualquier asamblea abierta con asistencia de
        // la unidad es incompatible; una asamblea finalizada no cuenta. Se comprueba con la unidad y sus asistencias
        // bloqueadas y antes de cualquier escritura: un rechazo no deja cambios. Solo esta unidad.
        if (attendeeRepository.existsOpenByProperty(propertyId, tenantId)) {
            throw new BusinessRuleConflictException("DELEGATION_BLOCKED_BY_ATTENDANCE",
                    "No puedes delegar el voto de esta unidad porque ya tiene asistencia registrada en una asamblea programada o en curso.");
        }

        // Fase 2 / P2 (R1): con la unidad ya bloqueada (la misma fila que toman todas las operaciones sobre poderes), un
        // poder APPROVED o USED de una asamblea abierta impide delegar. Antes de cualquier escritura: sin efectos parciales.
        proxyGuard.assertDelegationAllowed(propertyId, tenantId, currentUserId);

        if (activeOpt.isPresent()) {
            VotingDelegation active = activeOpt.get();
            {
                // Invalida la anterior (por si quedó pegada)
                active.setStatus(VotingDelegationStatus.INVALIDATED);
                active.setRevokedAt(OffsetDateTime.now());
                votingDelegationRepository.save(active);
            }
        }

        VotingDelegation delegation = VotingDelegation.builder()
                .property(property)
                .ownerUser(ownerRel.getUser())
                .delegateUser(tenantRel.getUser())
                .status(VotingDelegationStatus.ACTIVE)
                .build();
        delegation.setTenantId(tenantId);
        delegation = votingDelegationRepository.save(delegation);

        log.info("Voto delegado para la unidad {} del propietario {} al arrendatario {}", property.getNumber(), ownerRel.getUser().getId(), tenantRel.getUser().getId());

        return toResponse(delegation);
    }

    @Transactional
    public VotingDelegationResponse revokeVote(UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        // Fase 2 / E3 (D8) y cierre E3: mismo protocolo de bloqueos que delegar (sin cambiar las reglas de revocacion).
        lockVotingRights(propertyId, tenantId);
        UUID currentUserId = getCurrentUserId();
        User currentUser = userRepository.findById(currentUserId).orElseThrow(() -> new ResourceNotFoundException("User", currentUserId));

        // Cierre E5 (D11): quien revoca debe ser un copropietario ACTIVO de la unidad.
        if (ownerRelationOf(propertyId, tenantId, currentUserId) == null) {
            throw new BusinessRuleConflictException("NOT_THE_OWNER", "Solamente el propietario legítimo y activo puede revocar la delegación.");
        }

        VotingDelegation active = votingDelegationRepository.findByPropertyIdAndStatusAndTenantId(propertyId, VotingDelegationStatus.ACTIVE, tenantId)
                .orElseThrow(() -> new BusinessRuleConflictException("NO_ACTIVE_DELEGATION", "No hay una delegación activa para revocar."));

        // Cierre E5 (D11): la delegacion la revoca su autor (el copropietario que la otorgo). Otro copropietario no puede
        // revocarla mientras el autor siga siendo propietario activo de la unidad. Si el autor ya no lo es, se aplica la
        // regla anterior a E5 (la revoca el propietario activo), para no dejar una delegacion que nadie pueda revocar.
        UUID authorId = active.getOwnerUser() != null ? active.getOwnerUser().getId() : null;
        if (!currentUserId.equals(authorId) && authorId != null && ownerRelationOf(propertyId, tenantId, authorId) != null) {
            throw new BusinessRuleConflictException("DELEGATION_GRANTED_BY_OTHER_OWNER",
                    "Solo el copropietario que otorgó esta delegación puede revocarla.");
        }

        // Fase 2 / E5 (D9) y cierre E5 (D12): si el delegado ya tiene asistencia registrada por esta unidad en una asamblea
        // ABIERTA (programada, incluido el pre-registro administrativo, o en curso), la delegacion no se revoca mientras
        // esa asistencia siga vigente: su asistencia y su derecho de voto se mantienen (nada pasa al propietario). Con la
        // unidad y sus asistencias bloqueadas y antes de cualquier escritura: un rechazo no deja cambios. Asambleas
        // finalizadas, otras unidades y asistencias de otra persona no bloquean la revocacion.
        if (attendeeRepository.existsOpenByPropertyAndUser(propertyId, tenantId, active.getDelegateUser().getId())) {
            throw new BusinessRuleConflictException("DELEGATION_REVOCATION_BLOCKED_BY_ATTENDANCE",
                    "No puedes revocar esta delegación porque el delegado ya tiene asistencia registrada en una asamblea programada o en curso.");
        }

        active.setStatus(VotingDelegationStatus.REVOKED);
        active.setRevokedAt(OffsetDateTime.now());
        active.setRevokedByUser(currentUser);

        active = votingDelegationRepository.save(active);
        log.info("Voto revocado para la unidad {} por el propietario {}", active.getProperty().getNumber(), currentUserId);

        return toResponse(active);
    }

    @Transactional(readOnly = true)
    public Optional<VotingDelegationResponse> getActiveDelegation(UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return votingDelegationRepository.findByPropertyIdAndStatusAndTenantId(propertyId, VotingDelegationStatus.ACTIVE, tenantId)
                .map(this::toResponse);
    }

    @Transactional
    public void validateAndInvalidateDelegation(UUID propertyId, UUID tenantId) {
        Optional<VotingDelegation> activeOpt = votingDelegationRepository.findByPropertyIdAndStatusAndTenantId(propertyId, VotingDelegationStatus.ACTIVE, tenantId);
        if (activeOpt.isEmpty()) return;

        VotingDelegation active = activeOpt.get();
        // Cierre E5 (D11): el autor de la delegacion sigue siendo uno de los copropietarios activos.
        boolean ownerValid = ownerRelationOf(propertyId, tenantId, active.getOwnerUser().getId()) != null;

        Resident tenantRel = getActiveResident(propertyId, tenantId, "Arrendatario");
        boolean tenantValid = tenantRel != null && tenantRel.getUser() != null && tenantRel.getUser().getId().equals(active.getDelegateUser().getId());

        if (!ownerValid || !tenantValid) {
            active.setStatus(VotingDelegationStatus.INVALIDATED);
            active.setRevokedAt(OffsetDateTime.now());
            votingDelegationRepository.save(active);
            log.info("Delegación invalidada por cambios en residentes para unidad {}", propertyId);
        }
    }

    /** Cierre E5 (D11): relacion ACTIVE de tipo Propietario del usuario con la unidad (cualquier copropietario), o null. */
    private Resident ownerRelationOf(UUID propertyId, UUID tenantId, UUID userId) {
        return residentRepository.findByTenantIdAndPropertyId(tenantId, propertyId).stream()
                .filter(r -> r.getStatus() == com.havita.modules.resident.domain.ResidentStatus.ACTIVE)
                .filter(r -> r.getType() != null && r.getType().equalsIgnoreCase("Propietario"))
                .filter(r -> r.getUser() != null && r.getUser().getId().equals(userId))
                .findFirst()
                .orElse(null);
    }

    private Resident getActiveResident(UUID propertyId, UUID tenantId, String type) {
        List<Resident> residents = residentRepository.findByTenantIdAndPropertyId(tenantId, propertyId);
        // Solo cuenta la relacion vigente: un propietario o arrendatario dado de baja no delega ni revoca.
        return residents.stream()
                .filter(r -> r.getStatus() == com.havita.modules.resident.domain.ResidentStatus.ACTIVE)
                .filter(r -> r.getType().equalsIgnoreCase(type) && r.getUser() != null)
                .findFirst()
                .orElse(null);
    }

    private UUID getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String)) {
            throw new BusinessRuleConflictException("UNAUTHORIZED", "Usuario no autenticado.");
        }
        return UUID.fromString((String) auth.getPrincipal());
    }

    private VotingDelegationResponse toResponse(VotingDelegation d) {
        return new VotingDelegationResponse(
                d.getId(),
                d.getProperty().getId(),
                d.getOwnerUser().getId(),
                d.getOwnerUser().getFullName(),
                d.getDelegateUser().getId(),
                d.getDelegateUser().getFullName(),
                d.getStatus(),
                d.getGrantedAt(),
                d.getRevokedAt()
        );
    }
}
