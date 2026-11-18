package com.havita.modules.assembly.application;
import com.havita.modules.assembly.domain.PollStatus;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.assembly.api.dto.CastVoteRequest;
import com.havita.modules.assembly.api.dto.CreatePollRequest;
import com.havita.modules.assembly.api.dto.PollResponse;
import com.havita.modules.assembly.domain.Assembly;
import com.havita.modules.assembly.domain.Poll;
import com.havita.modules.assembly.domain.PollOption;
import com.havita.modules.assembly.domain.PollParticipation;
import com.havita.modules.assembly.domain.PollVote;
import com.havita.modules.assembly.infrastructure.AssemblyAttendeeRepository;
import com.havita.modules.assembly.infrastructure.AssemblyRepository;
import com.havita.modules.assembly.infrastructure.PollRepository;
import com.havita.modules.assembly.infrastructure.PollVoteRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.sync.domain.SyncAudience;
import com.havita.security.AccessValidator;
import com.havita.security.TenantContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.havita.modules.sync.domain.SyncEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID; import java.util.Map;
import java.util.Optional;

import com.havita.modules.assembly.infrastructure.VotingDelegationRepository;
import com.havita.modules.assembly.domain.VotingDelegationStatus;
import com.havita.modules.assembly.domain.VotingDelegation;

@Slf4j
@Service
@RequiredArgsConstructor
public class PollService {

    private final PollRepository pollRepository;
    private final PollVoteRepository pollVoteRepository;
    private final AssemblyRepository assemblyRepository;
    private final AssemblyAttendeeRepository attendeeRepository;
    private final PropertyRepository propertyRepository;
    private final ResidentRepository residentRepository;
    private final VotingDelegationRepository votingDelegationRepository;
    private final com.havita.modules.sync.application.SseService sseService;
    private final AccessValidator accessValidator;
    private final com.havita.security.ResidentUnitResolver unitResolver;
    // Fase 2 / P4: participaciones y auditoria de votos (solo si la base tiene V104; ver VoteLedgerSchema).
    private final com.havita.modules.assembly.infrastructure.VoteLedgerSchema ledgerSchema;
    private final com.havita.modules.assembly.infrastructure.VoteLedgerJdbcRepository ledger;
    private final AssemblyVoteEvents voteEvents;

    @Transactional(readOnly = true)
    public List<PollResponse> findByAssembly(UUID assemblyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        assemblyRepository.findByIdAndTenantId(assemblyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assembly", assemblyId));
        return pollRepository.findByAssemblyId(assemblyId)
                .stream()
                .filter(this::isVisibleToCaller)
                .map(this::toCallerResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PollResponse> findAllByTenant() {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return pollRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream()
                .filter(this::isVisibleToCaller)
                .map(this::toCallerResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PollResponse findById(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Poll poll = pollRepository.findByIdAndTenantId(id, tenantId)
                .filter(this::isVisibleToCaller)
                .orElseThrow(() -> new ResourceNotFoundException("Poll", id));
        return toCallerResponse(poll);
    }

    @Transactional
    public PollResponse createPoll(CreatePollRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        Assembly assembly = assemblyRepository.findByIdAndTenantId(request.assemblyId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assembly", request.assemblyId()));

        Poll poll = Poll.builder()
                .assembly(assembly)
                .question(request.question().trim())
                .type(request.type() != null ? request.type() : "SINGLE_CHOICE")
                .status(PollStatus.DRAFT)
                .showResultsToResidents(request.showResultsToResidents() != null ? request.showResultsToResidents() : true)
                .durationMinutes(request.durationMinutes())
                .options(new ArrayList<>())
                .build();
        poll.setTenantId(tenantId);

        int order = 1;
        for (String optText : request.options()) {
            PollOption opt = PollOption.builder()
                    .poll(poll)
                    .text(optText.trim())
                    .optionOrder(order++)
                    .build();
            poll.getOptions().add(opt);
        }

        poll = pollRepository.save(poll);
        log.info("Votación creada: '{}' para asamblea {}", poll.getQuestion(), assembly.getTitle());
        PollResponse resp = toResponse(poll);
        // Una votacion nueva nace en DRAFT: solo la gestion la ve hasta que se abre.
        sseService.publishEvent(new SyncEvent("POLL_CREATED", tenantId, toMap(resp), SyncAudience.staff()));
        return resp;
    }

    @Transactional
    public PollResponse openPoll(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        pollRepository.lockRow(id, tenantId); // cierre E5: bloqueo antes de leer (ver PollRepository.lockRow)
        Poll poll = pollRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Poll", id));

        poll.open();
        poll = pollRepository.save(poll);
        log.info("Votación {} abierta (LIVE)", id);
        PollResponse resp = toResponse(poll);
        publishPollUpdated(tenantId, resp);
        return resp;
    }

    @Transactional
    public PollResponse closePoll(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        pollRepository.lockRow(id, tenantId); // cierre E5: espera a los votos en curso antes de fijar closed_at
        Poll poll = pollRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Poll", id));

        poll.close();
        poll = pollRepository.save(poll);
        log.info("Votación {} cerrada (CLOSED)", id);
        PollResponse resp = toResponse(poll);
        publishPollUpdated(tenantId, resp);
        return resp;
    }

    /** Roles que pueden emitir votos (igual que la expresion del endpoint). */
    private static final java.util.Set<com.havita.modules.identity.domain.RoleType> VOTER_ROLES = java.util.Set.of(
            com.havita.modules.identity.domain.RoleType.RESIDENT,
            com.havita.modules.identity.domain.RoleType.COMMUNITY_ADMIN,
            com.havita.modules.identity.domain.RoleType.SUPER_ADMIN);

    /** Motivo por el que alguien no puede votar por una unidad (codigo estable + mensaje de siempre). */
    private record VotingProblem(String code, String message) {}

    /**
     * Emite el voto de una unidad en una pregunta.
     *
     * Fase 2 / E3 (D8) — una sola participacion por unidad y pregunta, tambien con peticiones concurrentes. Orden de
     * bloqueos (siempre el mismo, sin ciclos):
     * <ol>
     *   <li>membresia del llamante FOR SHARE (SEC-9 P1): un permiso revocado a mitad no vota;</li>
     *   <li>pregunta FOR SHARE: votos de unidades distintas en paralelo; el cierre (UPDATE) espera y un voto posterior ve
     *       CLOSED;</li>
     *   <li>fila de asistencia de la unidad FOR UPDATE (unica por asamblea y unidad, obligatoria para votar): serializa
     *       los votos de esa unidad (incluidos propietario, delegado, apoderado y voto asistido) y los cambios de
     *       delegacion (VotingDelegationService la bloquea tambien);</li>
     *   <li>asamblea FOR SHARE (choca con finalizar).</li>
     * </ol>
     * Con el bloqueo tomado se vuelven a evaluar el derecho de voto (delegacion vigente) y si la unidad ya voto. Una
     * peticion SINGLE_CHOICE solo admite una opcion y no se aceptan votos de una asamblea finalizada.
     *
     * Fase 2 / P4: si la base tiene V104 ({@link com.havita.modules.assembly.infrastructure.VoteLedgerSchema}), el voto se
     * registra como PARTICIPACION (actor, titulo y asistencia; V-2) y PostgreSQL rechaza ademas cualquier segundo voto
     * vigente de la unidad (V-1). Sin V104 (bases existentes) el voto se registra como siempre. V-5: un administrador
     * que es el representante registrado de la unidad vota por ella; el rol administrativo, por si solo, no da voto.
     */
    @Transactional
    public PollResponse castVote(UUID pollId, CastVoteRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        // 1. Permiso del llamante, lo primero de la transaccion.
        accessValidator.lockAuthorization(VOTER_ROLES, java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN));

        // 2. Pregunta FOR SHARE y su estado.
        Poll poll = lockLivePoll(pollId, tenantId);
        List<UUID> optionIds = requireOptionIds(poll, request.optionIds());

        boolean residentCaller = accessValidator.isResidentCaller();
        if (residentCaller && !accessValidator.isActiveResidentOfProperty(request.propertyId())) {
            throw new AccessDeniedException("No tiene derecho a votar por este inmueble.");
        }

        // 3. Fila de asistencia de la unidad FOR UPDATE: a partir de aqui, una sola peticion por unidad a la vez.
        com.havita.modules.assembly.domain.AssemblyAttendee attendee = lockAttendanceForVote(poll, request.propertyId(), tenantId);

        // Cierre E5 / D14: estado de la asamblea releido con FOR SHARE despues de la asistencia (orden global).
        requireAssemblyAcceptsVotes(poll, tenantId, false);

        boolean ledgerActive = ledgerSchema.active();
        UUID assemblyId = poll.getAssembly().getId();

        // Fase 13-16: derecho de voto segun asistencia, relacion con la unidad y delegacion (evaluado con el bloqueo).
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = auth != null && auth.getAuthorities().stream().anyMatch(a ->
            a.getAuthority().equals("ROLE_SUPER_ADMIN") || a.getAuthority().equals("ROLE_COMMUNITY_ADMIN"));
        UUID currentUserId = accessValidator.currentUserId();
        VotingProblem problem = votingRightProblem(tenantId, attendee, request.propertyId(), currentUserId, isAdmin);
        if (problem != null) {
            if (ledgerActive) {
                voteEvents.denied(tenantId, assemblyId, pollId, request.propertyId(), null, currentUserId, null, null,
                        AssemblyVoteEvents.UNAUTHORIZED_VOTE_REJECTED, problem.code());
            }
            throw new BusinessRuleConflictException(problem.code(), problem.message());
        }

        // 4. Ya voto: comprobado DESPUES de tomar el bloqueo, asi dos peticiones no pueden pasar a la vez.
        requireNotVoted(poll, request.propertyId(), tenantId, currentUserId, null, null, ledgerActive);

        Property property = propertyRepository.findByIdAndTenantId(request.propertyId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", request.propertyId()));

        // Cierre E5 / D14: el voto queda asociado a la REPRESENTACION registrada de la unidad (auditable), no a un
        // residentId enviado por el cliente; si se envia uno distinto del representante, se rechaza.
        Resident resident = attendee.getResident();
        if (request.residentId() != null && (resident == null || !resident.getId().equals(request.residentId()))) {
            throw new AccessDeniedException("El residente indicado no es el representante registrado de este inmueble.");
        }

        BigDecimal coef = property.getCoefficient() != null ? property.getCoefficient() : BigDecimal.ZERO;
        List<PollOption> options = optionsOf(poll, optionIds);

        if (ledgerActive) {
            // P4 (V-2): titulo derivado de la autorizacion real (nunca del cliente) y registrado con el voto.
            DirectTitle title = directTitle(tenantId, poll, attendee, resident, currentUserId);
            PollParticipation participation = new PollParticipation(UUID.randomUUID(), tenantId, assemblyId, pollId,
                    property.getId(), attendee.getId(), title.type(), currentUserId, resident.getId(), title.delegationId(),
                    null, null, null, null, null, null, PollParticipation.VALID, java.time.OffsetDateTime.now(), null, null, null, null);
            recordParticipation(participation, options, resident.getId(), coef, AssemblyVoteEvents.VOTE_CAST);
        } else {
            for (PollOption option : options) {
                PollVote vote = PollVote.builder()
                        .poll(poll)
                        .pollOption(option)
                        .property(property)
                        .resident(resident)
                        .coefficient(coef)
                        .build();
                vote.setTenantId(tenantId);
                pollVoteRepository.save(vote);
            }
        }

        log.info("Voto registrado en votación {} por inmueble {} (Coef: {})", pollId, property.getNumber(), coef);
        return publishVote(poll, tenantId);
    }

    // ── Fase 2 / P4: piezas comunes del voto (las usan castVote y ProxyVotingService, mismo paquete) ──────────

    /** P5-A: la pregunta existe en esta comunidad (404 si no, sin revelar nada). */
    void requirePollOfTenant(UUID pollId, UUID tenantId) {
        pollRepository.findByIdAndTenantId(pollId, tenantId).orElseThrow(() -> new ResourceNotFoundException("Poll", pollId));
    }

    /** Pregunta de la comunidad bloqueada FOR SHARE; debe estar LIVE y su asamblea no finalizada (404 si no es suya). */
    Poll lockLivePoll(UUID pollId, UUID tenantId) {
        Poll poll = pollRepository.lockForVote(pollId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Poll", pollId));
        if (poll.getStatus() != PollStatus.LIVE) {
            throw new BusinessRuleConflictException(
                    "POLL_NOT_LIVE",
                    "La votación no está activa o ya ha sido cerrada.");
        }
        if (poll.getAssembly() != null
                && poll.getAssembly().getStatus() == com.havita.modules.assembly.domain.AssemblyStatus.ENDED) {
            throw new BusinessRuleConflictException("ASSEMBLY_ENDED", "La asamblea ya finalizó; no se reciben más votos.");
        }
        return poll;
    }

    /** Opciones pedidas (sin repetir); SINGLE_CHOICE exige exactamente una (400, antes de cualquier bloqueo de la unidad). */
    List<UUID> requireOptionIds(Poll poll, List<UUID> requested) {
        List<UUID> optionIds = (requested == null ? List.<UUID>of() : requested).stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (optionIds.isEmpty() || ("SINGLE_CHOICE".equalsIgnoreCase(poll.getType()) && optionIds.size() != 1)) {
            throw new com.havita.common.exception.InvalidRequestException("INVALID_VOTE_OPTIONS",
                    "Esta pregunta admite una sola opción por unidad.");
        }
        return optionIds;
    }

    /** Las opciones, todas de esta pregunta (404 si alguna no lo es). */
    List<PollOption> optionsOf(Poll poll, List<UUID> optionIds) {
        List<PollOption> options = new ArrayList<>();
        for (UUID optionId : optionIds) {
            options.add(poll.getOptions().stream()
                    .filter(o -> o.getId().equals(optionId))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("PollOption", optionId)));
        }
        return options;
    }

    /** Asistencia de la unidad en la asamblea de la pregunta, FOR UPDATE (punto de serializacion de los votos de la unidad). */
    com.havita.modules.assembly.domain.AssemblyAttendee lockAttendanceForVote(Poll poll, UUID propertyId, UUID tenantId) {
        return attendeeRepository
                .lockForVote(poll.getAssembly().getId(), propertyId, tenantId)
                .orElseThrow(() -> new BusinessRuleConflictException(
                    "PROPERTY_NOT_REGISTERED_IN_ASSEMBLY",
                    "El inmueble no está registrado en el quórum/asistencia de la asamblea."));
    }

    /** Estado de la asamblea releido FOR SHARE (ultimo bloqueo del orden). {@code inProgress}: exige EN CURSO. */
    void requireAssemblyAcceptsVotes(Poll poll, UUID tenantId, boolean inProgress) {
        List<String> assemblyStatus = assemblyRepository.lockStatusForVote(poll.getAssembly().getId(), tenantId);
        if (assemblyStatus.isEmpty() || "ENDED".equals(assemblyStatus.get(0))) {
            throw new BusinessRuleConflictException("ASSEMBLY_ENDED", "La asamblea ya finalizó; no se reciben más votos.");
        }
        if (inProgress && !"IN_PROGRESS".equals(assemblyStatus.get(0))) {
            throw new BusinessRuleConflictException("ASSEMBLY_NOT_IN_PROGRESS", "La asamblea no está en curso.");
        }
    }

    /**
     * La unidad no tiene ya un voto en la pregunta (cualquier mecanismo: directo, poder o asistido, vigente o anulado).
     * P4: tras anular un voto asistido NO se habilita una nueva emision (pendiente de decision; ver
     * tasks/P4_PROXY_VOTING_IMPLEMENTATION.md): se rechaza con un codigo propio.
     */
    void requireNotVoted(Poll poll, UUID propertyId, UUID tenantId, UUID actorUserId,
                         com.havita.modules.assembly.domain.RepresentationType type, UUID proxyId, boolean ledgerActive) {
        if (!pollVoteRepository.existsByPollIdAndPropertyId(poll.getId(), propertyId)) {
            return;
        }
        boolean voided = ledgerActive && ledger.onlyVoidedParticipations(poll.getId(), propertyId);
        String code = voided ? "VOIDED_VOTE_REVOTE_NOT_AUTHORIZED" : "PROPERTY_ALREADY_VOTED";
        if (ledgerActive) {
            voteEvents.denied(tenantId, poll.getAssembly().getId(), poll.getId(), propertyId, null, actorUserId, type, proxyId,
                    AssemblyVoteEvents.DUPLICATE_VOTE_REJECTED, code);
        }
        throw new BusinessRuleConflictException(code, voided
                ? "El voto de esta unidad fue anulado; por esta vía no se admite una nueva emisión."
                : "El inmueble ya ha ejercido su voto en esta votación.");
    }

    /** Participacion + una fila de poll_votes por opcion + evento de exito, todo en la transaccion del voto. */
    PollParticipation recordParticipation(PollParticipation participation, List<PollOption> options, UUID residentId,
                                          BigDecimal coefficient, String action) {
        ledger.insertParticipation(participation);
        for (PollOption option : options) {
            ledger.insertVote(participation, option.getId(), residentId, coefficient);
        }
        voteEvents.success(participation, action, null);
        return participation;
    }

    /** Resultado actualizado de la pregunta, publicado por SSE (gestion y residentes segun showResultsToResidents). */
    PollResponse publishVote(Poll poll, UUID tenantId) {
        PollResponse resp = toResponse(poll);
        publishPollUpdated(tenantId, resp);
        return resp;
    }

    private record DirectTitle(com.havita.modules.assembly.domain.RepresentationType type, UUID delegationId) {}

    /**
     * P4 (V-2): titulo del representante registrado, derivado de la base con la asistencia bloqueada: propietario
     * (OWNER si es el unico propietario activo, CO_OWNER si hay varios) sin delegacion vigente, o arrendatario con la
     * delegacion ACTIVA a su favor. Cualquier otra cosa se rechaza (y se audita).
     */
    private DirectTitle directTitle(UUID tenantId, Poll poll, com.havita.modules.assembly.domain.AssemblyAttendee attendee,
                                    Resident representative, UUID actorUserId) {
        UUID propertyId = attendee.getProperty().getId();
        String type = representative.getType() == null ? "" : representative.getType();
        Optional<VotingDelegation> delegation = votingDelegationRepository
                .findByPropertyIdAndStatusAndTenantId(propertyId, VotingDelegationStatus.ACTIVE, tenantId);
        String code;
        if ("Propietario".equalsIgnoreCase(type)) {
            if (delegation.isEmpty()) {
                long owners = residentRepository.findByTenantIdAndPropertyId(tenantId, propertyId).stream()
                        .filter(r -> r.getStatus() == com.havita.modules.resident.domain.ResidentStatus.ACTIVE)
                        .filter(r -> "Propietario".equalsIgnoreCase(r.getType()))
                        .count();
                return new DirectTitle(owners > 1 ? com.havita.modules.assembly.domain.RepresentationType.CO_OWNER
                        : com.havita.modules.assembly.domain.RepresentationType.OWNER, null);
            }
            code = "VOTING_RIGHT_DELEGATED";
        } else if ("Arrendatario".equalsIgnoreCase(type)) {
            Optional<VotingDelegation> mine = delegation.filter(d -> d.getDelegateUser() != null
                    && d.getDelegateUser().getId().equals(actorUserId));
            if (mine.isPresent()) {
                return new DirectTitle(com.havita.modules.assembly.domain.RepresentationType.TENANT_DELEGATE, mine.get().getId());
            }
            code = "VOTING_RIGHT_NOT_DELEGATED";
        } else {
            code = "NO_VOTING_RIGHTS";
        }
        voteEvents.denied(tenantId, poll.getAssembly().getId(), poll.getId(), propertyId, null, actorUserId, null, null,
                AssemblyVoteEvents.UNAUTHORIZED_VOTE_REJECTED, code);
        throw new BusinessRuleConflictException(code,
                "La representación registrada ya no da derecho a votar por este inmueble.");
    }

    /**
     * Derecho de voto (cierre E5 / D14: solo el representante identificado), compartido para que castVote y la consulta
     * de unidades habilitadas (findMyVotingUnits) decidan igual. null = puede votar.
     *
     * Fase 2 / P4 (V-5): el veto a la administracion era global y excluia tambien a un administrador que ES el
     * representante registrado de la unidad (propietario, copropietario o arrendatario delegado). Ahora el derecho sale
     * SOLO de la representacion: un administrador vota si es el representante registrado y activo; si no lo es, se
     * rechaza como antes (ADMIN_VOTE_NOT_SUPPORTED): el rol no da voto por ninguna unidad.
     */
    private VotingProblem votingRightProblem(UUID tenantId, com.havita.modules.assembly.domain.AssemblyAttendee attendee,
                                             UUID propertyId, UUID currentUserId, boolean isAdmin) {
        // Cierre E5 / D14: solo vota el REPRESENTANTE identificado en la asistencia (una persona por unidad y asamblea),
        // y solo mientras su relacion con la unidad siga activa. Una asistencia SIN persona identificada (registros
        // anteriores a D14, un residente sin cuenta o la asistencia de un apoderado) no otorga derecho de voto por esta
        // ruta. La administracion debe identificar al representante (PUT /assemblies/{id}/attendees/{attendeeId}/representative).
        Resident representative = attendee.getResident();
        boolean isRepresentative = currentUserId != null && representative != null && representative.getUser() != null
                && representative.getUser().getId().equals(currentUserId)
                && representative.getStatus() == com.havita.modules.resident.domain.ResidentStatus.ACTIVE;
        if (isAdmin && !isRepresentative) {
            return new VotingProblem("ADMIN_VOTE_NOT_SUPPORTED",
                    "La administración no puede emitir votos en nombre de una unidad: vota su representante registrado.");
        }
        if (currentUserId == null) {
            return new VotingProblem("UNAUTHORIZED_VOTE", "No está autorizado para votar por este inmueble.");
        }
        if (representative == null || representative.getUser() == null) {
            return new VotingProblem("REPRESENTATIVE_NOT_IDENTIFIED",
                    "La asistencia de esta unidad no tiene un representante identificado. La administración debe asignarlo antes de votar.");
        }
        return isRepresentative ? null
                : new VotingProblem("UNAUTHORIZED_VOTE", "Usted no es el residente autorizado (apoderado/propietario) para votar por este inmueble en esta asamblea.");
    }

    /**
     * Fase 2 / E3 (D5): unidades del residente para una pregunta, con su estado (ya voto, puede votar o por que no).
     * Solo sus unidades ACTIVE en esta comunidad (ResidentUnitResolver); la regla es la misma de castVote. Lectura sin
     * bloqueos: el voto real vuelve a comprobarlo todo con el bloqueo tomado.
     */
    @Transactional(readOnly = true)
    public List<com.havita.modules.assembly.api.dto.MyVotingUnitResponse> findMyVotingUnits(UUID pollId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Poll poll = pollRepository.findByIdAndTenantId(pollId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Poll", pollId));
        boolean open = poll.getStatus() == PollStatus.LIVE && (poll.getAssembly() == null
                || poll.getAssembly().getStatus() != com.havita.modules.assembly.domain.AssemblyStatus.ENDED);
        UUID currentUserId = accessValidator.currentUserId();
        List<com.havita.modules.assembly.api.dto.MyVotingUnitResponse> result = new java.util.ArrayList<>();
        for (com.havita.security.ResidentUnitResolver.UnitOption unit : unitResolver.currentUnits()) {
            boolean voted = pollVoteRepository.existsByPollIdAndPropertyId(pollId, unit.propertyId());
            // Sin Optional.map: votingRightProblem devuelve null cuando SI puede votar.
            Optional<com.havita.modules.assembly.domain.AssemblyAttendee> attendee = poll.getAssembly() == null
                    ? Optional.empty()
                    : attendeeRepository.findByAssemblyIdAndPropertyId(poll.getAssembly().getId(), unit.propertyId());
            VotingProblem problem = attendee.isEmpty()
                    ? new VotingProblem("PROPERTY_NOT_REGISTERED_IN_ASSEMBLY",
                            "El inmueble no está registrado en el quórum/asistencia de la asamblea.")
                    : votingRightProblem(tenantId, attendee.get(), unit.propertyId(), currentUserId, false);
            String status = voted ? "VOTED" : !open ? "CLOSED" : problem != null ? "NOT_ELIGIBLE" : "PENDING";
            result.add(new com.havita.modules.assembly.api.dto.MyVotingUnitResponse(
                    unit.propertyId(), unit.label(), unit.number(), unit.relations(), status,
                    voted, "PENDING".equals(status),
                    problem != null && !voted ? problem.code() : null,
                    problem != null && !voted ? problem.message() : null));
        }
        return result;
    }

    private PollResponse toResponse(Poll p) {
        // Fase 2 / P4 (V-3): con V104, el resultado vigente excluye los votos de participaciones ANULADAS (que se
        // conservan); los votos historicos sin participacion cuentan como siempre. Sin V104, el calculo de siempre.
        com.havita.modules.assembly.infrastructure.VoteLedgerJdbcRepository.Tally tally =
                ledgerSchema.active() ? ledger.tally(p.getId()) : null;
        long totalVotes = tally != null ? tally.totalVotes() : pollVoteRepository.countByPollId(p.getId());
        BigDecimal totalVotedCoef = tally != null ? tally.totalCoefficient() : pollVoteRepository.sumTotalVotedCoefficient(p.getId());

        List<PollResponse.PollOptionDto> optionDtos = p.getOptions().stream().map(opt -> {
            com.havita.modules.assembly.infrastructure.VoteLedgerJdbcRepository.OptionTally ot = tally == null ? null
                    : tally.byOption().getOrDefault(opt.getId(),
                            new com.havita.modules.assembly.infrastructure.VoteLedgerJdbcRepository.OptionTally(0, BigDecimal.ZERO));
            long votesCount = ot != null ? ot.votes() : pollVoteRepository.countByPollOptionId(opt.getId());
            BigDecimal coefSum = ot != null ? ot.coefficient() : pollVoteRepository.sumCoefficientByOptionId(opt.getId());

            BigDecimal pct = BigDecimal.ZERO;
            if (totalVotedCoef != null && totalVotedCoef.compareTo(BigDecimal.ZERO) > 0 && coefSum != null) {
                pct = coefSum.multiply(BigDecimal.valueOf(100)).divide(totalVotedCoef, 2, RoundingMode.HALF_UP);
            } else if (totalVotes > 0) {
                // Fallback a voto nominal si los coeficientes son cero o nulos
                pct = BigDecimal.valueOf(votesCount).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalVotes), 2, RoundingMode.HALF_UP);
            }

            return new PollResponse.PollOptionDto(
                    opt.getId(),
                    opt.getText(),
                    votesCount,
                    coefSum,
                    pct
            );
        }).toList();

        return new PollResponse(
                p.getId(),
                p.getTenantId(),
                p.getAssembly().getId(),
                p.getAssembly().getTitle(),
                p.getQuestion(),
                p.getType(),
                optionDtos,
                p.getStatus(),
                p.getShowResultsToResidents(),
                totalVotes,
                totalVotedCoef != null ? totalVotedCoef : BigDecimal.ZERO,
                p.getOpenedAt(),
                p.getClosedAt(),
                p.getDurationMinutes(),
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }

    /** Roles de gestion: todas las votaciones. RESIDENT: las que ya no estan en borrador. */
    private boolean isVisibleToCaller(Poll poll) {
        return !accessValidator.isResidentCaller() || poll.getStatus() != PollStatus.DRAFT;
    }

    /** Respeta showResultsToResidents: a un residente no se le envian los resultados por opcion si estan ocultos. */
    private PollResponse toCallerResponse(Poll poll) {
        PollResponse resp = toResponse(poll);
        return accessValidator.isResidentCaller() ? forResidents(resp) : resp;
    }

    private static PollResponse forResidents(PollResponse r) {
        if (Boolean.TRUE.equals(r.showResultsToResidents())) {
            return r;
        }
        List<PollResponse.PollOptionDto> hidden = r.options().stream()
                .map(o -> new PollResponse.PollOptionDto(o.id(), o.text(), 0L, null, null))
                .toList();
        return new PollResponse(r.id(), r.tenantId(), r.assemblyId(), r.assemblyTitle(), r.question(), r.type(),
                hidden, r.status(), r.showResultsToResidents(), r.totalVotes(), null,
                r.openedAt(), r.closedAt(), r.durationMinutes(), r.createdAt(), r.updatedAt());
    }

    /** La gestion recibe los resultados completos; los residentes, la version que respeta showResultsToResidents. */
    private void publishPollUpdated(UUID tenantId, PollResponse resp) {
        if (Boolean.TRUE.equals(resp.showResultsToResidents())) {
            sseService.publishEvent(new SyncEvent("POLL_UPDATED", tenantId, toMap(resp)));
            return;
        }
        sseService.publishEvent(new SyncEvent("POLL_UPDATED", tenantId, toMap(resp), SyncAudience.staff()));
        sseService.publishEvent(new SyncEvent("POLL_UPDATED", tenantId, toMap(forResidents(resp)), SyncAudience.residents()));
    }

    private Map<String, Object> toMap(PollResponse resp) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        return mapper.convertValue(resp, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }
}
