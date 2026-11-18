package com.havita.modules.assembly.application;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.InvalidRequestException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.assembly.api.dto.AssistedVoteRequest;
import com.havita.modules.assembly.api.dto.ParticipationResponse;
import com.havita.modules.assembly.api.dto.PollResponse;
import com.havita.modules.assembly.api.dto.ProxyVoteRequest;
import com.havita.modules.assembly.domain.AssemblyAttendee;
import com.havita.modules.assembly.domain.AssemblyProxy;
import com.havita.modules.assembly.domain.Poll;
import com.havita.modules.assembly.domain.PollOption;
import com.havita.modules.assembly.domain.PollParticipation;
import com.havita.modules.assembly.domain.RepresentationType;
import com.havita.modules.assembly.infrastructure.AssemblyProxyJdbcRepository;
import com.havita.modules.assembly.infrastructure.VoteLedgerJdbcRepository;
import com.havita.modules.assembly.infrastructure.VoteLedgerSchema;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.security.AccessValidator;
import com.havita.security.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Fase 2 / P4 — voto mediante poder (PREPARADO, NO ACTIVADO). Solo existe con {@code havita.assembly-proxies.enabled=true}
 * y exige el esquema V104 (si no esta, falla cerrado con 409 VOTE_LEDGER_REQUIRED).
 *
 * <ul>
 *   <li><b>ACCOUNT_PROXY</b>: el apoderado CON CUENTA vota con su propia sesion por la unidad de un poder USED suyo.</li>
 *   <li><b>ADMIN_ASSISTED_PROXY</b> (P4-R1): solo para apoderados EXTERNOS (sin cuenta) presentes. La administracion
 *       verifica en persona su documento y registra el voto que el expresa; el administrador figura como actor, nunca
 *       como titular. No vale para apoderados con cuenta, propietarios ni arrendatarios.</li>
 *   <li><b>Anulacion</b> (P4-R2/R3): solo de votos asistidos, por una administracion vigente, con motivo, con la
 *       pregunta abierta y la asamblea en curso. El voto se conserva y deja de contar. No se habilita una nueva emision
 *       despues de anular (decision pendiente).</li>
 * </ul>
 *
 * Orden de bloqueos, el MISMO que castVote (sin ciclos con E3/E5/P2): membresia del llamante FOR SHARE → pregunta FOR
 * SHARE → asistencia de la unidad FOR UPDATE (serializa todos los votos y anulaciones de la unidad) → asamblea FOR SHARE
 * → membresia del apoderado FOR SHARE (es la del propio llamante) → participacion FOR NO KEY UPDATE (anulacion). El
 * poder USED es final e inmutable (V103), asi que se lee sin bloqueo. Participacion, votos y evento de exito en una sola
 * transaccion; los rechazos se auditan en transaccion propia.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "havita.assembly-proxies.enabled", havingValue = "true")
public class ProxyVotingService {

    /** Cualquier rol de comunidad (la membresia ACTIVA se comprueba ademas contra PostgreSQL). Sin roles globales. */
    private static final Set<RoleType> COMMUNITY_MEMBER_ROLES = Set.of(RoleType.COMMUNITY_ADMIN, RoleType.SECURITY,
            RoleType.STAFF, RoleType.RESIDENT, RoleType.ACCOUNTANT);

    private final PollService polls;
    private final AssemblyProxyJdbcRepository proxies;
    private final VoteLedgerSchema ledgerSchema;
    private final VoteLedgerJdbcRepository ledger;
    private final AssemblyVoteEvents voteEvents;
    private final AccessValidator accessValidator;
    private final PropertyRepository propertyRepository;

    public ProxyVotingService(PollService polls, AssemblyProxyJdbcRepository proxies, VoteLedgerSchema ledgerSchema,
                              VoteLedgerJdbcRepository ledger, AssemblyVoteEvents voteEvents, AccessValidator accessValidator,
                              PropertyRepository propertyRepository) {
        this.polls = polls;
        this.proxies = proxies;
        this.ledgerSchema = ledgerSchema;
        this.ledger = ledger;
        this.voteEvents = voteEvents;
        this.accessValidator = accessValidator;
        this.propertyRepository = propertyRepository;
    }

    // ── voto del apoderado con cuenta ─────────────────────────────────────────

    @Transactional
    public ParticipationResponse castAsAccountProxy(UUID pollId, ProxyVoteRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.lockAuthorization(COMMUNITY_MEMBER_ROLES, Set.of());
        requireLedger();
        UUID actorId = accessValidator.currentUserId();

        Poll poll = polls.lockLivePoll(pollId, tenantId);
        List<UUID> optionIds = polls.requireOptionIds(poll, request.optionIds());
        // Un poder ajeno, de otra asamblea o de otra comunidad "no existe" (404, sin revelar nada).
        AssemblyProxy proxy = proxies.find(request.proxyId(), poll.getAssembly().getId(), tenantId)
                .filter(p -> actorId != null && actorId.equals(p.proxyUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("AssemblyProxy", request.proxyId()));

        AssemblyAttendee attendee = polls.lockAttendanceForVote(poll, proxy.propertyId(), tenantId);
        polls.requireAssemblyAcceptsVotes(poll, tenantId, false);
        RepresentationType type = RepresentationType.ACCOUNT_PROXY;
        requireUsableProxy(poll, proxy, attendee, actorId, type, tenantId);
        // R2 / P4-R8: la membresia del apoderado sigue ACTIVA (y su cuenta), contra PostgreSQL y bloqueada.
        if (!proxies.lockActiveMembership(actorId, tenantId)) {
            throw deny(poll, proxy, actorId, type, "PROXY_USER_NOT_ACTIVE_MEMBER",
                    "La cuenta del apoderado no tiene una membresía activa en esta comunidad.");
        }
        polls.requireNotVoted(poll, proxy.propertyId(), tenantId, actorId, type, proxy.id(), true);

        PollParticipation participation = new PollParticipation(UUID.randomUUID(), tenantId, poll.getAssembly().getId(),
                pollId, proxy.propertyId(), attendee.getId(), type, actorId, null, null, proxy.id(), proxy.grantorResidentId(),
                null, null, null, null, PollParticipation.VALID, OffsetDateTime.now(), null, null, null, null);
        return record(poll, participation, optionIds, AssemblyVoteEvents.VOTE_CAST, tenantId);
    }

    // ── voto asistido (apoderado externo presente) ────────────────────────────

    @Transactional
    public ParticipationResponse castAssisted(UUID pollId, AssistedVoteRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.lockCommunityAdministrator();
        requireLedger();
        UUID adminId = accessValidator.currentUserId();
        if (!Boolean.TRUE.equals(request.expressedByProxyConfirmed())) {
            throw new InvalidRequestException("EXPRESSION_NOT_CONFIRMED", "Confirma que el voto lo expresó el apoderado presente.");
        }

        Poll poll = polls.lockLivePoll(pollId, tenantId);
        List<UUID> optionIds = polls.requireOptionIds(poll, request.optionIds());
        AssemblyProxy proxy = proxies.find(request.proxyId(), poll.getAssembly().getId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("AssemblyProxy", request.proxyId()));

        AssemblyAttendee attendee = polls.lockAttendanceForVote(poll, proxy.propertyId(), tenantId);
        polls.requireAssemblyAcceptsVotes(poll, tenantId, true);
        RepresentationType type = RepresentationType.ADMIN_ASSISTED_PROXY;
        // P4-R1: un apoderado con cuenta vota el mismo; la administracion no vota por el.
        if (proxy.proxyUserId() != null) {
            throw deny(poll, proxy, adminId, type, "ASSISTED_VOTE_ONLY_FOR_EXTERNAL_PROXY",
                    "El apoderado tiene cuenta Havita: debe votar él mismo con su sesión.");
        }
        requireUsableProxy(poll, proxy, attendee, adminId, type, tenantId);
        boolean identityMatches = proxy.proxyDocumentType().equalsIgnoreCase(request.documentType().trim())
                && proxy.proxyDocumentNumber().equalsIgnoreCase(request.documentNumber().trim());
        if (!identityMatches) {
            throw deny(poll, proxy, adminId, type, "PROXY_IDENTITY_MISMATCH",
                    "El documento presentado no coincide con el del apoderado del poder.");
        }
        // P5-A (V105): si el voto asistido de la unidad fue ANULADO (y no hay otro vigente), la nueva emision continua
        // la cadena: otra participacion que referencia a la anulada, con todas las verificaciones repetidas arriba.
        UUID supersedes = null;
        java.util.Optional<PollParticipation> head = ledger.findReissueHead(pollId, proxy.propertyId(), tenantId);
        if (head.isPresent() && ledger.onlyVoidedParticipations(pollId, proxy.propertyId())) {
            PollParticipation h = head.get();
            if (h.representationType() != RepresentationType.ADMIN_ASSISTED_PROXY || !proxy.id().equals(h.proxyId())) {
                throw deny(poll, proxy, adminId, type, "REISSUE_NOT_ALLOWED",
                        "Solo se puede volver a emitir el voto asistido anulado del mismo apoderado.");
            }
            supersedes = h.id();
        } else {
            polls.requireNotVoted(poll, proxy.propertyId(), tenantId, adminId, type, proxy.id(), true);
        }

        OffsetDateTime now = OffsetDateTime.now();
        PollParticipation participation = new PollParticipation(UUID.randomUUID(), tenantId, poll.getAssembly().getId(),
                pollId, proxy.propertyId(), attendee.getId(), type, adminId, null, null, proxy.id(), proxy.grantorResidentId(),
                adminId, now, PollParticipation.IN_PERSON_DOCUMENT, Boolean.TRUE, PollParticipation.VALID, now, null, null, null,
                supersedes);
        return record(poll, participation, optionIds,
                supersedes != null ? AssemblyVoteEvents.ASSISTED_VOTE_REISSUED : AssemblyVoteEvents.ASSISTED_VOTE_CAST, tenantId);
    }

    // ── anulacion de un voto asistido ─────────────────────────────────────────

    @Transactional
    public ParticipationResponse voidAssisted(UUID pollId, UUID participationId, String rawReason) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.lockCommunityAdministrator();
        requireLedger();
        UUID adminId = accessValidator.currentUserId();
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.isEmpty() || reason.length() > 500) {
            throw new InvalidRequestException("VOID_REASON_REQUIRED", "Indica el motivo de la anulación (máximo 500 caracteres).");
        }
        // Participacion de esta pregunta y comunidad (404 si no, sin revelar nada).
        PollParticipation found = ledger.find(participationId, pollId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("PollParticipation", participationId));

        Poll poll;
        try {
            poll = polls.lockLivePoll(pollId, tenantId);
        } catch (BusinessRuleConflictException e) {
            voidDenied(found, adminId, e.getCode());
            throw e;
        }
        AssemblyAttendee attendee = polls.lockAttendanceForVote(poll, found.propertyId(), tenantId);
        try {
            polls.requireAssemblyAcceptsVotes(poll, tenantId, true);
        } catch (BusinessRuleConflictException e) {
            voidDenied(found, adminId, e.getCode());
            throw e;
        }
        PollParticipation current = ledger.lock(participationId, tenantId).orElseThrow();
        if (!current.attendeeId().equals(attendee.getId())) {
            throw new IllegalStateException("participacion sin su asistencia");
        }
        if (current.representationType() != RepresentationType.ADMIN_ASSISTED_PROXY) {
            voidDenied(current, adminId, "VOID_ONLY_FOR_ASSISTED_VOTES");
            throw new BusinessRuleConflictException("VOID_ONLY_FOR_ASSISTED_VOTES",
                    "Solo un voto asistido por la administración puede anularse.");
        }
        if (!PollParticipation.VALID.equals(current.status())) {
            voidDenied(current, adminId, "PARTICIPATION_ALREADY_VOIDED");
            throw new BusinessRuleConflictException("PARTICIPATION_ALREADY_VOIDED", "El voto ya estaba anulado.");
        }

        ledger.markVoided(participationId, adminId, OffsetDateTime.now(), reason);
        PollParticipation voided = ledger.lock(participationId, tenantId).orElseThrow();
        voteEvents.success(voided, AssemblyVoteEvents.ASSISTED_VOTE_VOIDED, reason);
        log.info("Voto asistido {} anulado en la pregunta {} (unidad {})", participationId, pollId, voided.propertyId());
        return toResponse(voided, polls.publishVote(poll, tenantId));
    }

    // ── historial (auditoria autorizada) ──────────────────────────────────────

    /**
     * P5-A: historial completo de participaciones de una pregunta (vigentes y anuladas, con su cadena de nuevas
     * emisiones y las opciones elegidas), solo para la administracion vigente de la comunidad. Sin datos de
     * identificacion del apoderado (ni nombre ni documento).
     */
    @Transactional(readOnly = true)
    public List<com.havita.modules.assembly.api.dto.ParticipationAuditResponse> history(UUID pollId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.requireCommunityAdministrator();
        requireLedger();
        polls.requirePollOfTenant(pollId, tenantId);
        return ledger.history(pollId, tenantId).stream()
                .map(p -> new com.havita.modules.assembly.api.dto.ParticipationAuditResponse(p.id(), p.propertyId(),
                        p.representationType().name(), p.status(), p.actorUserId(), p.castAt(), ledger.optionsOf(p.id()),
                        p.supersedesParticipationId(), p.identityVerifiedByUserId(), p.identityVerifiedAt(),
                        p.voidedByUserId(), p.voidedAt(), p.voidReason()))
                .toList();
    }

    // ── reglas comunes ────────────────────────────────────────────────────────

    /** Sin V104 no hay participaciones ni auditoria: el voto por poder no se emite (fallo cerrado). */
    private void requireLedger() {
        if (!ledgerSchema.active()) {
            throw new BusinessRuleConflictException("VOTE_LEDGER_REQUIRED",
                    "El voto mediante poder requiere el esquema de participaciones (V104), que no está aplicado.");
        }
    }

    /**
     * El poder sigue siendo titulo de la unidad (asistencia ya bloqueada): USED con ESTA asistencia por apoderado, su
     * otorgante sigue siendo propietario activo y la unidad no tiene delegacion activa (exclusividad de P2).
     *
     * P5-D: el limite de representacion se evalua al APROBAR y al USAR el poder (con el valor de la comunidad o el
     * global), NO al votar: un limite reducido despues no se aplica retroactivamente a poderes ya utilizados.
     */
    private void requireUsableProxy(Poll poll, AssemblyProxy proxy, AssemblyAttendee attendee, UUID actorId,
                                    RepresentationType type, UUID tenantId) {
        boolean usedHere = proxies.findUsedAttendee(proxy.id(), tenantId).filter(attendee.getId()::equals).isPresent()
                && Boolean.TRUE.equals(attendee.getIsPowerOfAttorney());
        if (!usedHere) {
            throw deny(poll, proxy, actorId, type, "PROXY_NOT_USED",
                    "El poder no se utilizó para registrar la asistencia de la unidad en esta asamblea.");
        }
        if (!proxies.isActiveOwnerRelation(proxy.grantorResidentId(), proxy.propertyId(), tenantId)) {
            throw deny(poll, proxy, actorId, type, "GRANTOR_NOT_ACTIVE_OWNER",
                    "El otorgante ya no es propietario activo de la unidad; el poder no puede usarse.");
        }
        if (proxies.existsActiveDelegation(proxy.propertyId(), tenantId)) {
            throw deny(poll, proxy, actorId, type, "PROXY_CONFLICTS_WITH_DELEGATION",
                    "La unidad tiene una delegación de voto activa incompatible con el poder.");
        }
    }

    private BusinessRuleConflictException deny(Poll poll, AssemblyProxy proxy, UUID actorId, RepresentationType type,
                                               String code, String message) {
        voteEvents.denied(proxy.tenantId(), poll.getAssembly().getId(), poll.getId(), proxy.propertyId(), null, actorId, type,
                proxy.id(), AssemblyVoteEvents.UNAUTHORIZED_VOTE_REJECTED, code);
        return new BusinessRuleConflictException(code, message);
    }

    private void voidDenied(PollParticipation p, UUID adminId, String code) {
        voteEvents.denied(p.tenantId(), p.assemblyId(), p.pollId(), p.propertyId(), p.id(), adminId, p.representationType(),
                p.proxyId(), AssemblyVoteEvents.VOID_REJECTED, code);
    }

    private ParticipationResponse record(Poll poll, PollParticipation participation, List<UUID> optionIds, String action,
                                         UUID tenantId) {
        Property property = propertyRepository.findByIdAndTenantId(participation.propertyId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", participation.propertyId()));
        BigDecimal coef = property.getCoefficient() != null ? property.getCoefficient() : BigDecimal.ZERO;
        List<PollOption> options = polls.optionsOf(poll, optionIds);
        polls.recordParticipation(participation, options, null, coef, action);
        log.info("Voto {} registrado en la pregunta {} por la unidad {} ({})", participation.id(), poll.getId(),
                property.getNumber(), participation.representationType());
        return toResponse(participation, polls.publishVote(poll, tenantId));
    }

    private static ParticipationResponse toResponse(PollParticipation p, PollResponse poll) {
        return new ParticipationResponse(p.id(), p.pollId(), p.assemblyId(), p.propertyId(), p.representationType().name(),
                p.status(), p.castAt(), p.voidedAt(), p.supersedesParticipationId(), poll);
    }
}
