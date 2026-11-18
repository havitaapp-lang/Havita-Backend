package com.havita.modules.assembly.application;
import com.havita.modules.assembly.domain.AssemblyStatus;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.assembly.api.dto.AssemblyResponse;
import com.havita.modules.assembly.api.dto.AttendeeResponse;
import com.havita.modules.assembly.api.dto.CreateAssemblyRequest;
import com.havita.modules.assembly.api.dto.RegisterAttendeeRequest;
import com.havita.modules.assembly.domain.Assembly;
import com.havita.modules.assembly.domain.AssemblyAttendee;
import com.havita.modules.assembly.infrastructure.AssemblyAttendeeRepository;
import com.havita.modules.assembly.infrastructure.AssemblyRepository;
import com.havita.modules.assembly.infrastructure.PollRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.sync.application.SyncEventPublisher;
import com.havita.modules.sync.domain.SyncEvent;
import com.havita.security.AccessValidator;
import com.havita.security.TenantContextHolder;
import org.springframework.security.access.AccessDeniedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssemblyService {

    private final AssemblyRepository assemblyRepository;
    private final AssemblyAttendeeRepository attendeeRepository;
    private final PollRepository pollRepository;
    private final PropertyRepository propertyRepository;
    private final ResidentRepository residentRepository;
    private final com.havita.modules.assembly.infrastructure.VotingDelegationRepository votingDelegationRepository;
    private final SyncEventPublisher syncEventPublisher;
    private final AccessValidator accessValidator;
    private final com.havita.security.ResidentUnitResolver unitResolver;
    private final jakarta.persistence.EntityManager entityManager;
    private final com.havita.modules.assembly.infrastructure.PollVoteRepository pollVoteRepository;
    /** Fase 2 / P2: exclusividad con los poderes de asamblea (sin efecto ni consultas si estan desactivados). */
    private final ProxyRepresentationGuard proxyGuard;
    /** Fase 2 / P4: auditoria de transiciones rechazadas, solo si la base tiene V104 (assembly_vote_events). */
    private final com.havita.modules.assembly.infrastructure.VoteLedgerSchema ledgerSchema;
    private final AssemblyVoteEvents voteEvents;
    /** Avisos push a residentes (se envian tras el commit). */
    private final org.springframework.context.ApplicationEventPublisher events;

    /** Publica un evento SSE al tenant correspondiente tras el commit de la transacción. */
    private void publishAssemblyEvent(String type, UUID tenantId, Map<String, Object> payload) {
        try {
            syncEventPublisher.publish(new SyncEvent(type, tenantId, payload, OffsetDateTime.now()));
        } catch (Exception e) {
            log.warn("[Assembly] No se pudo publicar el evento SSE '{}': {}", type, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Page<AssemblyResponse> findAll(Pageable pageable) {
        return findAll(null, pageable);
    }

    /**
     * Cierre E5: filtro opcional por estado. El portal buscaba la asamblea EN CURSO solo en la primera pagina (20, sin
     * orden): con mas de 20 asambleas el residente podia no ver la asistencia ni la votacion en vivo.
     */
    @Transactional(readOnly = true)
    public Page<AssemblyResponse> findAll(AssemblyStatus status, Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Page<Assembly> page = status == null
                ? assemblyRepository.findByTenantId(tenantId, pageable)
                : assemblyRepository.findByTenantIdAndStatus(tenantId, status, pageable);
        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public AssemblyResponse findById(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Assembly assembly = assemblyRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assembly", id));
        return toResponse(assembly);
    }

    @Transactional
    public AssemblyResponse create(CreateAssemblyRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        long totalProps = propertyRepository.countByTenantId(tenantId);

        Assembly assembly = Assembly.builder()
                .title(request.title().trim())
                .type(request.type() != null ? request.type() : "ORDINARIA")
                .modality(request.modality() != null ? request.modality() : "MIXTA")
                .assemblyDate(request.assemblyDate())
                .startTime(request.startTime())
                .status(AssemblyStatus.SCHEDULED)
                .totalProperties((int) totalProps)
                .registeredAttendees(0)
                .quorumPercentage(BigDecimal.ZERO)
                .description(request.description())
                .build();
        assembly.setTenantId(tenantId);

        assembly = assemblyRepository.save(assembly);
        log.info("Asamblea programada: '{}' para el día {}", assembly.getTitle(), assembly.getAssemblyDate());

        AssemblyResponse response = toResponse(assembly);
        publishAssemblyEvent("ASSEMBLY_CREATED", tenantId, Map.of(
                "assemblyId", assembly.getId().toString(),
                "title", assembly.getTitle(),
                "status", assembly.getStatus().name()
        ));
        return response;
    }

    /**
     * Inicia una asamblea programada.
     *
     * Fase 2 / P4 (V-4): la unica transicion valida es SCHEDULED -> IN_PROGRESS. Antes, una asamblea FINALIZADA volvia
     * a EN CURSO (y con ella la recepcion de votos y asistencias); ahora se rechaza con 409 y un codigo estable, sin
     * cambios, y el intento queda auditado si la base tiene V104. No existe mecanismo de reapertura. La administracion
     * vigente se comprueba contra PostgreSQL dentro de la transaccion (membresia FOR SHARE, antes de la asamblea).
     */
    @Transactional
    public AssemblyResponse startAssembly(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.lockCommunityAdministrator();
        // Fase 2 / E5: fila de la asamblea bloqueada ANTES de leerla, para no pisar un quorum recien recalculado.
        assemblyRepository.lockRow(id, tenantId);
        Assembly assembly = assemblyRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assembly", id));

        if (assembly.getStatus() != AssemblyStatus.SCHEDULED) {
            boolean ended = assembly.getStatus() == AssemblyStatus.ENDED;
            String code = ended ? "ASSEMBLY_ALREADY_ENDED" : "ASSEMBLY_ALREADY_IN_PROGRESS";
            if (ledgerSchema.active()) {
                voteEvents.transitionDenied(tenantId, assembly.getId(), accessValidator.currentUserId(), code);
            }
            throw new BusinessRuleConflictException(code, ended
                    ? "La asamblea ya finalizó y no puede reabrirse."
                    : "La asamblea ya está en curso.");
        }

        assembly.start();
        assembly = assemblyRepository.save(assembly);
        log.info("Asamblea {} iniciada", id);

        AssemblyResponse response = toResponse(assembly);
        publishAssemblyEvent("ASSEMBLY_STARTED", tenantId, Map.of(
                "assemblyId", assembly.getId().toString(),
                "title", assembly.getTitle(),
                "status", assembly.getStatus().name()
        ));
        events.publishEvent(com.havita.modules.push.application.ResidentPushRequested.community(tenantId, null,
                "Comenzó la asamblea de la comunidad. Toca para participar", "ASSEMBLY_STARTED", "/resident"));
        return response;
    }

    @Transactional
    public AssemblyResponse finishAssembly(UUID id) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        // Fase 2 / E5: fila de la asamblea bloqueada ANTES de leerla, para no pisar un quorum recien recalculado.
        assemblyRepository.lockRow(id, tenantId);
        Assembly assembly = assemblyRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assembly", id));

        assembly.finish();
        assembly = assemblyRepository.save(assembly);
        log.info("Asamblea {} finalizada", id);

        AssemblyResponse response = toResponse(assembly);
        publishAssemblyEvent("ASSEMBLY_ENDED", tenantId, Map.of(
                "assemblyId", assembly.getId().toString(),
                "title", assembly.getTitle(),
                "status", assembly.getStatus().name()
        ));
        return response;
    }

    // --- Quórum y Asistencia ---

    /**
     * Cierre E3: bloquea la fila de la unidad (FOR NO KEY UPDATE) que serializa asistencia y delegacion de voto. La unidad
     * existe antes que la asistencia, asi que la exclusion no depende de una fila que todavia no existe.
     */
    private void lockUnitVotingRights(UUID propertyId, UUID tenantId) {
        if (propertyRepository.lockForVotingRights(propertyId, tenantId).isEmpty()) {
            throw new ResourceNotFoundException("Property", propertyId);
        }
    }

    /**
     * Fase 2 / E5: despues de la unidad, la fila de la asamblea (FOR NO KEY UPDATE) y su estado releido. Dos asistencias
     * de unidades distintas en la misma asamblea recalculaban el quorum cada una con su propia foto y la segunda pisaba
     * a la primera (registered_attendees = 1 con dos asistencias); y un registro podia reescribir el estado de una
     * asamblea finalizada a la vez. Orden global: membresia → pregunta → unidad → asistencia → asamblea.
     */
    void lockAssemblyForAttendance(Assembly assembly, UUID tenantId) {
        assemblyRepository.lockRow(assembly.getId(), tenantId);
        entityManager.refresh(assembly);
    }

    /** Cierre E5: roles que pueden auto-registrar asistencia (los de la expresion del endpoint). */
    private static final java.util.Set<com.havita.modules.identity.domain.RoleType> SELF_REGISTER_ROLES = java.util.Set.of(
            com.havita.modules.identity.domain.RoleType.RESIDENT,
            com.havita.modules.identity.domain.RoleType.COMMUNITY_ADMIN,
            com.havita.modules.identity.domain.RoleType.SUPER_ADMIN);

    /** Cierre E5 (D13): roles que pueden registrar asistencia administrativamente (los de la expresion del endpoint). */
    private static final java.util.Set<com.havita.modules.identity.domain.RoleType> ADMIN_REGISTER_ROLES = java.util.Set.of(
            com.havita.modules.identity.domain.RoleType.COMMUNITY_ADMIN,
            com.havita.modules.identity.domain.RoleType.SUPER_ADMIN);

    @Transactional
    public AttendeeResponse selfRegisterAttendee(UUID assemblyId, com.havita.modules.assembly.api.dto.SelfRegisterAttendeeRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        // Cierre E5: membresia vigente del llamante (FOR SHARE), lo primero de la transaccion (orden global de bloqueos).
        accessValidator.lockAuthorization(SELF_REGISTER_ROLES, java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN));

        Assembly assembly = assemblyRepository.findByIdAndTenantId(assemblyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assembly", assemblyId));

        if (assembly.getStatus() != com.havita.modules.assembly.domain.AssemblyStatus.IN_PROGRESS) {
            throw new BusinessRuleConflictException("ASSEMBLY_NOT_IN_PROGRESS", "La asamblea no está en curso para registrar asistencia.");
        }

        // Antes de revelar si la unidad ya registro asistencia, el residente debe pertenecer hoy a ella.
        if (accessValidator.isResidentCaller() && !accessValidator.isActiveResidentOfProperty(request.propertyId())) {
            throw new AccessDeniedException("No tiene derecho a registrar la asistencia de este inmueble.");
        }

        // Cierre E3: mismo protocolo que delegar el voto (unidad FOR NO KEY UPDATE antes de comprobar e insertar). Asi la
        // comprobacion de delegacion de abajo y la de asistencia de VotingDelegationService nunca pasan a la vez.
        lockUnitVotingRights(request.propertyId(), tenantId);
        lockAssemblyForAttendance(assembly, tenantId);
        if (assembly.getStatus() != com.havita.modules.assembly.domain.AssemblyStatus.IN_PROGRESS) {
            throw new BusinessRuleConflictException("ASSEMBLY_NOT_IN_PROGRESS", "La asamblea no está en curso para registrar asistencia.");
        }

        if (attendeeRepository.existsByAssemblyIdAndPropertyId(assemblyId, request.propertyId())) {
            throw new BusinessRuleConflictException("ALREADY_REGISTERED", "El inmueble ya ha sido registrado en la lista de asistencia.");
        }
        // Fase 2 / P2 (R6): con la unidad y la asamblea bloqueadas, un poder APPROVED de la unidad es su representacion.
        proxyGuard.assertTraditionalAttendanceAllowed(assemblyId, request.propertyId(), tenantId, accessValidator.currentUserId(), false);

        Property property = propertyRepository.findByIdAndTenantId(request.propertyId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", request.propertyId()));

        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String)) {
            throw new BusinessRuleConflictException("UNAUTHORIZED", "Usuario no autenticado.");
        }
        UUID currentUserId = UUID.fromString((String) auth.getPrincipal());

        // Fase 2 / E5: misma regla que el listado de unidades (findMyAttendanceUnits), evaluada con la unidad bloqueada.
        List<Resident> propertyResidents = residentRepository.findByTenantIdAndPropertyId(tenantId, request.propertyId()).stream()
                .filter(r -> r.getStatus() == com.havita.modules.resident.domain.ResidentStatus.ACTIVE)
                .toList();
        java.util.Optional<com.havita.modules.assembly.domain.VotingDelegation> activeDelegation =
            votingDelegationRepository.findByPropertyIdAndStatusAndTenantId(request.propertyId(), com.havita.modules.assembly.domain.VotingDelegationStatus.ACTIVE, tenantId);
        AttendanceRight right = attendanceRight(propertyResidents, activeDelegation.orElse(null), currentUserId);
        if (right.code() != null) {
            throw new BusinessRuleConflictException(right.code(), right.message());
        }
        Resident actingResident = right.acting();

        BigDecimal coef = property.getCoefficient() != null ? property.getCoefficient() : BigDecimal.ZERO;
        AssemblyAttendee attendee = AssemblyAttendee.builder()
                .assembly(assembly)
                .property(property)
                .resident(actingResident)
                .isPowerOfAttorney(false)
                .coefficient(coef)
                .build();
        attendee.setTenantId(tenantId);
        attendee = attendeeRepository.save(attendee);

        recalculateQuorum(assembly, tenantId);

        log.info("Auto-Registro en quórum para unidad {} en asamblea {} por usuario {}", property.getNumber(), assemblyId, currentUserId);

        return new AttendeeResponse(
                  attendee.getId(),
                  assembly.getId(),
                  property.getId(),
                  property.getNumber(),
                  property.getName(),
                  actingResident != null ? actingResident.getId() : null,
                  actingResident != null ? actingResident.getName() : null,
                  attendee.getCoefficient(),
                  attendee.getIsPowerOfAttorney(),
                  attendee.getRepresentativeName(),
                  attendee.getCheckedInAt()
          );
    }

    /**
     * Fase 2 / E5: derecho de un usuario a registrar por si mismo la asistencia de una unidad. {@code acting} es SU
     * relacion con la unidad (la que queda en la asistencia); {@code code} no nulo = no puede.
     */
    private record AttendanceRight(String code, String message, Resident acting) {
        static AttendanceRight denied(String code, String message) {
            return new AttendanceRight(code, message, null);
        }
    }

    /**
     * Fase 2 / E5 — misma semantica que antes de E5 con dos correcciones:
     * <ul>
     *   <li>copropietarios: cuenta CUALQUIER relacion ACTIVE de tipo Propietario del usuario con la unidad (antes solo la
     *       primera que devolvia la consulta, sin orden definido, asi que un copropietario podia quedar fuera);</li>
     *   <li>arrendatario: necesita una delegacion vigente A SU FAVOR (antes bastaba con que existiera una delegacion),
     *       la misma condicion con la que el voto lo habilita.</li>
     * </ul>
     * Una sola representacion por unidad y asamblea la garantizan la restriccion unica (asamblea, unidad) y el bloqueo
     * de la unidad: el primero que registra representa a la unidad; el resto recibe ALREADY_REGISTERED.
     */
    private static AttendanceRight attendanceRight(List<Resident> activeResidents,
                                                   com.havita.modules.assembly.domain.VotingDelegation delegation,
                                                   UUID userId) {
        Resident myOwner = myRelation(activeResidents, "Propietario", userId);
        Resident myTenant = myRelation(activeResidents, "Arrendatario", userId);
        if (myOwner == null && myTenant == null) {
            return AttendanceRight.denied("NO_VOTING_RIGHTS", "Solo el propietario o el arrendatario delegado pueden registrar la asistencia.");
        }
        return representationRight(myOwner != null ? myOwner : myTenant, delegation);
    }

    /**
     * Cierre E5 (D11/D13) — UNA sola regla para que una relacion de residente represente a su unidad en una asamblea, la
     * misma en la auto-inscripcion y en el registro administrativo (y la que aplica el voto, PollService):
     * <ul>
     *   <li>la relacion debe estar ACTIVE y tener cuenta (si no, no se puede comprobar quien es);</li>
     *   <li>Propietario (cualquier copropietario): solo si NO hay delegacion vigente;</li>
     *   <li>Arrendatario: solo con una delegacion vigente A SU FAVOR (no vale la de otro arrendatario ni una revocada);</li>
     *   <li>otros tipos (p. ej. Residente del hogar): no representan a la unidad.</li>
     * </ul>
     */
    private static AttendanceRight representationRight(Resident relation,
                                                       com.havita.modules.assembly.domain.VotingDelegation delegation) {
        if (relation == null || relation.getStatus() != com.havita.modules.resident.domain.ResidentStatus.ACTIVE || relation.getUser() == null) {
            return AttendanceRight.denied("NO_VOTING_RIGHTS", "Solo el propietario o el arrendatario delegado pueden registrar la asistencia.");
        }
        String type = relation.getType() == null ? "" : relation.getType();
        if (type.equalsIgnoreCase("Propietario")) {
            return delegation != null
                    ? AttendanceRight.denied("VOTING_RIGHT_DELEGATED", "El derecho de voto está delegado al arrendatario. Él debe registrar la asistencia.")
                    : new AttendanceRight(null, null, relation);
        }
        if (type.equalsIgnoreCase("Arrendatario")) {
            boolean delegatedToThem = delegation != null && delegation.getDelegateUser() != null
                    && delegation.getDelegateUser().getId().equals(relation.getUser().getId());
            return delegatedToThem
                    ? new AttendanceRight(null, null, relation)
                    : AttendanceRight.denied("VOTING_RIGHT_NOT_DELEGATED", "No tienes el derecho de voto delegado por el propietario.");
        }
        return AttendanceRight.denied("NO_VOTING_RIGHTS", "Solo el propietario o el arrendatario delegado pueden registrar la asistencia.");
    }

    private static Resident myRelation(List<Resident> activeResidents, String type, UUID userId) {
        return activeResidents.stream()
                .filter(r -> r.getType() != null && r.getType().equalsIgnoreCase(type))
                .filter(r -> r.getUser() != null && r.getUser().getId().equals(userId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Fase 2 / E5: unidades ACTIVE del residente (ResidentUnitResolver) frente a una asamblea, con su estado de asistencia:
     * REGISTERED (la unidad ya tiene asistencia), PENDING (puede registrarla ahora), NOT_ELIGIBLE (motivo) o CLOSED (la
     * asamblea no esta en curso). Lectura sin bloqueos: el registro real vuelve a comprobarlo todo con la unidad bloqueada.
     * Numero fijo de consultas (unidades, asistencias, relaciones y delegaciones), independiente del numero de unidades.
     */
    @Transactional(readOnly = true)
    public List<com.havita.modules.assembly.api.dto.MyAttendanceUnitResponse> findMyAttendanceUnits(UUID assemblyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Assembly assembly = assemblyRepository.findByIdAndTenantId(assemblyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assembly", assemblyId));
        UUID userId = accessValidator.currentUserId();
        List<com.havita.security.ResidentUnitResolver.UnitOption> units = unitResolver.currentUnits();
        if (units.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = units.stream().map(com.havita.security.ResidentUnitResolver.UnitOption::propertyId).toList();
        Map<UUID, AssemblyAttendee> attendance = new java.util.HashMap<>();
        attendeeRepository.findByAssemblyAndProperties(assemblyId, tenantId, ids)
                .forEach(a -> attendance.put(a.getProperty().getId(), a));
        Map<UUID, List<Resident>> residentsByUnit = new java.util.HashMap<>();
        residentRepository.findByTenantIdAndPropertyIdInAndStatus(tenantId, ids, com.havita.modules.resident.domain.ResidentStatus.ACTIVE)
                .forEach(r -> residentsByUnit.computeIfAbsent(r.getProperty().getId(), k -> new java.util.ArrayList<>()).add(r));
        Map<UUID, com.havita.modules.assembly.domain.VotingDelegation> delegationByUnit = new java.util.HashMap<>();
        votingDelegationRepository.findByStatusAndTenantId(com.havita.modules.assembly.domain.VotingDelegationStatus.ACTIVE, tenantId)
                .stream().filter(d -> ids.contains(d.getProperty().getId()))
                .forEach(d -> delegationByUnit.put(d.getProperty().getId(), d));
        boolean inProgress = assembly.getStatus() == AssemblyStatus.IN_PROGRESS;

        List<com.havita.modules.assembly.api.dto.MyAttendanceUnitResponse> result = new java.util.ArrayList<>();
        for (com.havita.security.ResidentUnitResolver.UnitOption unit : units) {
            AssemblyAttendee a = attendance.get(unit.propertyId());
            String status;
            String code = null;
            String message = null;
            boolean byMe = false;
            if (a != null) {
                status = "REGISTERED";
                byMe = a.getResident() != null && a.getResident().getUser() != null
                        && a.getResident().getUser().getId().equals(userId);
            } else if (!inProgress) {
                status = "CLOSED";
                code = "ASSEMBLY_NOT_IN_PROGRESS";
                message = "La asamblea no está en curso para registrar asistencia.";
            } else {
                AttendanceRight right = attendanceRight(residentsByUnit.getOrDefault(unit.propertyId(), List.of()),
                        delegationByUnit.get(unit.propertyId()), userId);
                status = right.code() == null ? "PENDING" : "NOT_ELIGIBLE";
                code = right.code();
                message = right.message();
            }
            result.add(new com.havita.modules.assembly.api.dto.MyAttendanceUnitResponse(
                    unit.propertyId(), unit.label(), unit.number(), unit.relations(), status,
                    a != null, byMe, "PENDING".equals(status), code, message,
                    a != null && a.getResident() != null ? a.getResident().getName() : null));
        }
        return result;
    }

    @Transactional
    public AttendeeResponse registerAttendee(UUID assemblyId, RegisterAttendeeRequest request) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        // Cierre E5 (D13): membresia administrativa vigente en ESTA comunidad (FOR SHARE), lo primero de la transaccion.
        accessValidator.lockAuthorization(ADMIN_REGISTER_ROLES, java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN));

        Assembly assembly = assemblyRepository.findByIdAndTenantId(assemblyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assembly", assemblyId));

        // Cierre E3: mismo protocolo de bloqueo que la auto-inscripcion y la delegacion de voto (unidad de ESTA comunidad,
        // si no 404) y, despues, la asamblea con su estado releido.
        lockUnitVotingRights(request.propertyId(), tenantId);
        lockAssemblyForAttendance(assembly, tenantId);

        // Cierre E5 (D13): pre-registro en asambleas programadas y registro en asambleas en curso; nunca en una finalizada.
        if (assembly.getStatus() == AssemblyStatus.ENDED) {
            throw new BusinessRuleConflictException("ASSEMBLY_ENDED", "La asamblea ya finalizó; no se registran más asistencias.");
        }

        // Una sola representacion por unidad y asamblea: no se duplica ni se sustituye en silencio.
        if (attendeeRepository.existsByAssemblyIdAndPropertyId(assemblyId, request.propertyId())) {
            throw new BusinessRuleConflictException(
                    "PROPERTY_ALREADY_REGISTERED",
                    "El inmueble ya ha sido registrado en la lista de asistencia de esta asamblea.");
        }
        // Fase 2 / P2 (R6): con la unidad y la asamblea bloqueadas, un poder APPROVED de la unidad es su representacion.
        proxyGuard.assertTraditionalAttendanceAllowed(assemblyId, request.propertyId(), tenantId, accessValidator.currentUserId(), true);

        Property property = propertyRepository.findByIdAndTenantId(request.propertyId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", request.propertyId()));

        // Cierre E5 / D14: la administracion identifica SIEMPRE a quien representa la unidad. El registro por apoderado no
        // se acepta: el modelo actual solo guarda un nombre libre (sin identidad del apoderado ni autorizacion verificable
        // de quien lo otorga), y no hay un voto administrativo auditable con el que el apoderado pueda votar.
        if (Boolean.TRUE.equals(request.isPowerOfAttorney())) {
            throw new BusinessRuleConflictException("POWER_OF_ATTORNEY_NOT_SUPPORTED",
                    "El registro por apoderado no está disponible: Havita aún no gestiona poderes con una autorización verificable.");
        }
        if (request.residentId() == null) {
            throw new BusinessRuleConflictException("REPRESENTATIVE_REQUIRED",
                    "Selecciona el propietario, copropietario o arrendatario delegado que representa a la unidad.");
        }

        // Cierre E5 (D13/D14): la persona que representa a la unidad debe ser una relacion ACTIVE de ESTA unidad con
        // derecho a representarla (misma regla que la auto-inscripcion).
        Resident resident = validateRepresentative(request.residentId(), property, tenantId);

        BigDecimal coef = property.getCoefficient() != null ? property.getCoefficient() : BigDecimal.ZERO;

        AssemblyAttendee attendee = AssemblyAttendee.builder()
                .assembly(assembly)
                .property(property)
                .resident(resident)
                .coefficient(coef)
                .isPowerOfAttorney(false)
                .representativeName(null)
                .build();
        attendee.setTenantId(tenantId);

        attendee = attendeeRepository.save(attendee);

        // Recalcular quórum de la asamblea
        recalculateQuorum(assembly, tenantId);

        log.info("Asistente registrado a asamblea {}: inmueble {} con coeficiente {}",
                assembly.getTitle(), property.getNumber(), coef);

        return new AttendeeResponse(
                attendee.getId(),
                assembly.getId(),
                property.getId(),
                property.getNumber(),
                property.getName(),
                resident != null ? resident.getId() : null,
                resident != null ? resident.getName() : null,
                attendee.getCoefficient(),
                attendee.getIsPowerOfAttorney(),
                attendee.getRepresentativeName(),
                attendee.getCheckedInAt()
        );
    }

    /**
     * Cierre E5 (D13/D14): valida que la persona indicada represente a la unidad: relacion de ESTA comunidad (si no, 404),
     * de ESTA unidad y ACTIVE (si no, INVALID_UNIT_REPRESENTATION) y con derecho a representarla segun la delegacion
     * vigente (representationRight). Se llama con la unidad y la asamblea ya bloqueadas.
     */
    private Resident validateRepresentative(UUID residentId, Property property, UUID tenantId) {
        Resident resident = residentRepository.findByIdAndTenantId(residentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Resident", residentId));
        if (resident.getProperty() == null || !resident.getProperty().getId().equals(property.getId())
                || resident.getStatus() != com.havita.modules.resident.domain.ResidentStatus.ACTIVE) {
            throw new BusinessRuleConflictException("INVALID_UNIT_REPRESENTATION",
                    "La persona indicada no tiene una relación activa con esta unidad.");
        }
        AttendanceRight right = representationRight(resident,
                votingDelegationRepository.findByPropertyIdAndStatusAndTenantId(property.getId(),
                        com.havita.modules.assembly.domain.VotingDelegationStatus.ACTIVE, tenantId).orElse(null));
        if (right.code() != null) {
            throw new BusinessRuleConflictException(right.code(), right.message());
        }
        return resident;
    }

    /**
     * Cierre E5 / D14: personas de la unidad que la administracion puede registrar como representante (propietarios,
     * copropietarios y arrendatario), con la misma regla que el registro. Solo lectura; el registro vuelve a validarlo
     * todo con la unidad bloqueada.
     */
    @Transactional(readOnly = true)
    public List<com.havita.modules.assembly.api.dto.EligibleRepresentativeResponse> findEligibleRepresentatives(UUID assemblyId, UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        assemblyRepository.findByIdAndTenantId(assemblyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assembly", assemblyId));
        propertyRepository.findByIdAndTenantId(propertyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", propertyId));
        com.havita.modules.assembly.domain.VotingDelegation delegation = votingDelegationRepository
                .findByPropertyIdAndStatusAndTenantId(propertyId, com.havita.modules.assembly.domain.VotingDelegationStatus.ACTIVE, tenantId)
                .orElse(null);
        return residentRepository.findByTenantIdAndPropertyIdInAndStatus(tenantId, List.of(propertyId),
                        com.havita.modules.resident.domain.ResidentStatus.ACTIVE).stream()
                .filter(r -> r.getType() != null && (r.getType().equalsIgnoreCase("Propietario") || r.getType().equalsIgnoreCase("Arrendatario")))
                .sorted(java.util.Comparator.comparing((Resident r) -> r.getType().equalsIgnoreCase("Propietario") ? 0 : 1)
                        .thenComparing(r -> r.getName() == null ? "" : r.getName()).thenComparing(Resident::getId))
                .map(r -> {
                    AttendanceRight right = representationRight(r, delegation);
                    return new com.havita.modules.assembly.api.dto.EligibleRepresentativeResponse(
                            r.getId(), r.getName(), r.getType(), right.code() == null, right.code(), right.message());
                })
                .toList();
    }

    /**
     * Cierre E5 / D14: regulariza EXPLICITAMENTE una asistencia sin persona identificada (anterior a D14) de una asamblea
     * abierta, asignandole su representante. No cambia el quorum. Se rechaza si la asistencia ya tiene representante, es
     * de apoderado, la asamblea finalizo o la unidad ya voto en esta asamblea (esos votos no se atribuyen a nadie).
     * Orden de bloqueos: membresia → unidad → asamblea (el UPDATE de la asistencia espera a un voto en curso).
     */
    @Transactional
    public AttendeeResponse assignRepresentative(UUID assemblyId, UUID attendeeId, UUID residentId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.lockAuthorization(ADMIN_REGISTER_ROLES, java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN));
        Assembly assembly = assemblyRepository.findByIdAndTenantId(assemblyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assembly", assemblyId));
        AssemblyAttendee attendee = attendeeRepository.findById(attendeeId)
                .filter(a -> tenantId.equals(a.getTenantId()) && a.getAssembly().getId().equals(assemblyId))
                .orElseThrow(() -> new ResourceNotFoundException("Attendee", attendeeId));
        Property property = attendee.getProperty();
        lockUnitVotingRights(property.getId(), tenantId);
        lockAssemblyForAttendance(assembly, tenantId);
        entityManager.refresh(attendee);
        if (assembly.getStatus() == AssemblyStatus.ENDED) {
            throw new BusinessRuleConflictException("ASSEMBLY_ENDED", "La asamblea ya finalizó; no se modifican sus asistencias.");
        }
        if (attendee.getResident() != null) {
            throw new BusinessRuleConflictException("REPRESENTATIVE_ALREADY_SET",
                    "Esta asistencia ya tiene un representante; no se sustituye.");
        }
        if (Boolean.TRUE.equals(attendee.getIsPowerOfAttorney())) {
            throw new BusinessRuleConflictException("POWER_OF_ATTORNEY_NOT_SUPPORTED",
                    "La asistencia por apoderado no se puede regularizar: Havita aún no gestiona poderes verificables.");
        }
        if (pollVoteRepository.existsByPropertyAndAssembly(property.getId(), assemblyId)) {
            throw new BusinessRuleConflictException("ATTENDANCE_HAS_VOTES",
                    "La unidad ya tiene votos en esta asamblea sin representante identificado; no se atribuyen a otra persona.");
        }
        Resident resident = validateRepresentative(residentId, property, tenantId);
        attendee.setResident(resident);
        attendee = attendeeRepository.save(attendee);
        log.info("Representante asignado a la asistencia {} de la unidad {} en la asamblea {}", attendeeId, property.getNumber(), assemblyId);
        return new AttendeeResponse(attendee.getId(), assembly.getId(), property.getId(), property.getNumber(), property.getName(),
                resident.getId(), resident.getName(), attendee.getCoefficient(), attendee.getIsPowerOfAttorney(),
                attendee.getRepresentativeName(), attendee.getCheckedInAt());
    }

    @Transactional(readOnly = true)
    public List<AttendeeResponse> findAttendees(UUID assemblyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        assemblyRepository.findByIdAndTenantId(assemblyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assembly", assemblyId));
        // La gestion ve el quorum completo; un residente solo el registro de sus unidades activas
        // (nombres, unidades y coeficientes de los demas no se exponen).
        java.util.function.Predicate<AssemblyAttendee> visible = accessValidator.isResidentCaller()
                ? visibleUnits(accessValidator.activePropertyIds())
                : a -> true;
        return attendeeRepository.findByAssemblyId(assemblyId)
                .stream()
                .filter(visible)
                .map(a -> new AttendeeResponse(
                        a.getId(),
                        a.getAssembly().getId(),
                        a.getProperty().getId(),
                        a.getProperty().getNumber(),
                        a.getProperty().getName(),
                        a.getResident() != null ? a.getResident().getId() : null,
                        a.getResident() != null ? a.getResident().getName() : null,
                        a.getCoefficient(),
                        a.getIsPowerOfAttorney(),
                        a.getRepresentativeName(),
                        a.getCheckedInAt()
                ))
                .toList();
    }

    private static java.util.function.Predicate<AssemblyAttendee> visibleUnits(java.util.Set<UUID> units) {
        return a -> a.getProperty() != null && units.contains(a.getProperty().getId());
    }

    /**
     * Fase 2 / P2: asistencia de una unidad por un PODER ya validado y bloqueado por AssemblyProxyService (unidad y
     * asamblea bloqueadas en el orden global). Sin persona del censo (resident = null): la unidad cuenta para el quorum,
     * pero esta asistencia no habilita a nadie a votar (castVote exige un representante identificado; el voto por poder
     * es una etapa posterior). Marcada como apoderado: assignRepresentative no la convierte en otra representacion.
     * saveAndFlush: el poder la referencia (FK) en la misma transaccion.
     */
    AssemblyAttendee saveProxyAttendee(Assembly assembly, Property property, String proxyFullName, UUID tenantId) {
        BigDecimal coef = property.getCoefficient() != null ? property.getCoefficient() : BigDecimal.ZERO;
        AssemblyAttendee attendee = AssemblyAttendee.builder()
                .assembly(assembly)
                .property(property)
                .resident(null)
                .coefficient(coef)
                .isPowerOfAttorney(true)
                .representativeName(proxyFullName)
                .build();
        attendee.setTenantId(tenantId);
        attendee = attendeeRepository.saveAndFlush(attendee);
        recalculateQuorum(assembly, tenantId);
        return attendee;
    }

    void recalculateQuorum(Assembly assembly, UUID tenantId) {
        long totalAttendees = attendeeRepository.countByAssemblyId(assembly.getId());
        BigDecimal totalAttendedCoef = attendeeRepository.sumAttendedCoefficient(assembly.getId());
        BigDecimal totalTenantCoef = propertyRepository.sumCoefficientByTenantId(tenantId);

        BigDecimal quorumPercent = BigDecimal.ZERO;
        if (totalTenantCoef != null && totalTenantCoef.compareTo(BigDecimal.ZERO) > 0) {
            quorumPercent = totalAttendedCoef
                    .multiply(BigDecimal.valueOf(100))
                    .divide(totalTenantCoef, 2, RoundingMode.HALF_UP);
        }

        assembly.setRegisteredAttendees((int) totalAttendees);
        assembly.setQuorumPercentage(quorumPercent);
        assemblyRepository.save(assembly);

        // Notificar quórum actualizado en tiempo real
        publishAssemblyEvent("ASSEMBLY_QUORUM_UPDATED", tenantId, Map.of(
                "assemblyId", assembly.getId().toString(),
                "registeredAttendees", (int) totalAttendees,
                "quorumPercentage", quorumPercent.doubleValue()
        ));
    }

    @Transactional(readOnly = true)
    public java.util.List<com.havita.modules.assembly.api.dto.VotingRightResponse> getVotingRights(UUID assemblyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        assemblyRepository.findByIdAndTenantId(assemblyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Assembly", assemblyId));
        
        java.util.List<com.havita.modules.property.domain.Property> properties = propertyRepository.findByTenantId(tenantId);
        
        java.util.List<com.havita.modules.assembly.domain.VotingDelegation> delegations = 
            votingDelegationRepository.findByStatusAndTenantId(com.havita.modules.assembly.domain.VotingDelegationStatus.ACTIVE, tenantId);
        java.util.Map<UUID, com.havita.modules.assembly.domain.VotingDelegation> delMap = new java.util.HashMap<>();
        for (com.havita.modules.assembly.domain.VotingDelegation d : delegations) {
            delMap.put(d.getProperty().getId(), d);
        }

        java.util.List<com.havita.modules.resident.domain.Resident> activeResidents = residentRepository.findByTenantId(tenantId);
        java.util.Map<UUID, String> ownerMap = new java.util.HashMap<>();
        for (com.havita.modules.resident.domain.Resident r : activeResidents) {
            if ("Propietario".equalsIgnoreCase(r.getType()) && r.getStatus() == com.havita.modules.resident.domain.ResidentStatus.ACTIVE) {
                ownerMap.put(r.getProperty().getId(), r.getUser() != null ? r.getUser().getFullName() : r.getName());
            }
        }

        java.util.List<com.havita.modules.assembly.api.dto.VotingRightResponse> result = new java.util.ArrayList<>();
        for (com.havita.modules.property.domain.Property p : properties) {
            if (p.getStatus() != com.havita.modules.property.domain.PropertyStatus.OCCUPIED) {
                continue;
            }

            String voterName = "Sin Asignar";
            String voterRole = "-";

            com.havita.modules.assembly.domain.VotingDelegation del = delMap.get(p.getId());
            if (del != null) {
                voterName = del.getDelegateUser().getFullName();
                voterRole = "Arrendatario Delegado";
            } else if (ownerMap.containsKey(p.getId())) {
                voterName = ownerMap.get(p.getId());
                voterRole = "Propietario";
            }

            result.add(new com.havita.modules.assembly.api.dto.VotingRightResponse(
                    p.getId(),
                    p.getNumber(),
                    p.getName(),
                    voterName,
                    voterRole
            ));
        }

        return result;
    }

    private AssemblyResponse toResponse(Assembly a) {
        long pollsCount = pollRepository.countByAssemblyId(a.getId());
        return new AssemblyResponse(
                a.getId(),
                a.getTenantId(),
                a.getTitle(),
                a.getType(),
                a.getModality(),
                a.getAssemblyDate(),
                a.getStartTime(),
                a.getStatus(),
                a.getTotalProperties(),
                a.getRegisteredAttendees(),
                a.getQuorumPercentage(),
                pollsCount,
                a.getDescription(),
                a.getCreatedAt(),
                a.getUpdatedAt()
        );
    }
}
