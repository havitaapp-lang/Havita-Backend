package com.havita.modules.intercom.application;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.common.exception.TenantAccessDeniedException;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.intercom.api.dto.InitiateIntercomCallRequest;
import com.havita.modules.intercom.api.dto.IntercomCallResponse;
import com.havita.modules.intercom.api.dto.IntercomResidentOptionResponse;
import com.havita.modules.intercom.api.dto.IntercomUnitDirectoryResponse;
import com.havita.modules.intercom.domain.IntercomCall;
import com.havita.modules.intercom.domain.IntercomCallStatus;
import com.havita.modules.intercom.infrastructure.IntercomCallRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.sync.application.SyncEventPublisher;
import com.havita.modules.sync.domain.SyncEvent;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntercomService {

    private final IntercomCallRepository intercomCallRepository;
    private final PropertyRepository propertyRepository;
    private final ResidentRepository residentRepository;
    private final UserRepository userRepository;
    private final CloudflareRealtimeKitClient realtimeKitClient;
    private final SyncEventPublisher syncEventPublisher;
    private final RealtimeKitResourceCleaner resourceCleaner;
    // P8-B: avisos Web Push (despachados tras el commit por IntercomPushNotifier; no dependen del SSE).
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** Plazo de timbre: una llamada RINGING mas antigua pasa a MISSED. Tambien acota el TTL del aviso push (P8-B). */
    public static final int RING_TIMEOUT_SECONDS = 45;

    /**
     * P8-A3 (desactivado por defecto): preparacion anticipada con barrera del lado de Cloudflare.
     * <ul>
     *   <li>La reunion pasa a INACTIVE ANTES de emitir los tokens: segun Cloudflare, nadie puede unirse a una reunion
     *       INACTIVE.</li>
     *   <li>El residente llamado puede pedir su token mientras timbra ({@link #prepareCall}) para inicializar el SDK sin
     *       unirse ni capturar audio.</li>
     *   <li>Solo al aceptar, el backend pasa la reunion a ACTIVE.</li>
     * </ul>
     * La barrera la impone el servidor de Cloudflare, no el cliente; se verifica con un intento de union antes de
     * aceptar en las pruebas reales (P8A3).
     */
    @Value("${havita.intercom.early-token.enabled:false}")
    private boolean earlyTokenEnabled = false;

    // Temporary in-memory cache for recipient tokens: callId -> recipientToken
    // Only delivered once to the authorized recipient upon acceptCall()
    private final Map<UUID, String> pendingRecipientTokens = new ConcurrentHashMap<>();

    // Also store caller tokens for recovery if caller reconnects: callId -> callerToken
    private final Map<UUID, String> callerTokens = new ConcurrentHashMap<>();

    /**
     * P8-A6: plazo para que una llamada ACEPTADA quede conectada (los dos participantes en la sala). Si no, se cierra
     * como ENDED / ACCEPTED_NEVER_CONNECTED_TIMEOUT. Antes no habia salida de ACCEPTED salvo colgar: una llamada
     * abandonada dejaba a porteria y residente en CALLER_BUSY / RECIPIENT_BUSY indefinidamente.
     */
    @Value("${havita.intercom.accept-connect-timeout-seconds:60}")
    private long acceptConnectTimeoutSeconds = 60;

    /** P8-A2: crear los dos participantes de RealtimeKit en paralelo (desactivado por defecto). */
    @Value("${havita.intercom.parallel-participant-creation:false}")
    private boolean parallelParticipantCreation = false;

    /** Hilos para las dos peticiones REST independientes; demonio, para no bloquear el apagado. */
    private static final java.util.concurrent.ExecutorService PARTICIPANT_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "intercom-rtk-participant");
                t.setDaemon(true);
                return t;
            });

    /**
     * Crea los participantes de porteria y residente. Si falla cualquiera, borra el que se creara y cierra la reunion
     * (sin esperar el resultado). Despues relanza el error original, sin guardar la llamada.
     */
    private CloudflareRealtimeKitClient.ParticipantToken[] createParticipants(String meetingId,
            String callerName, String callerCustomId, String recipientName, String recipientCustomId) {
        CloudflareRealtimeKitClient.ParticipantToken caller = null;
        CloudflareRealtimeKitClient.ParticipantToken recipient = null;
        try {
            if (parallelParticipantCreation) {
                CompletableFuture<CloudflareRealtimeKitClient.ParticipantToken> c = CompletableFuture.supplyAsync(
                        () -> realtimeKitClient.addParticipant(meetingId, callerName, callerCustomId), PARTICIPANT_EXECUTOR);
                CompletableFuture<CloudflareRealtimeKitClient.ParticipantToken> r = CompletableFuture.supplyAsync(
                        () -> realtimeKitClient.addParticipant(meetingId, recipientName, recipientCustomId), PARTICIPANT_EXECUTOR);
                try {
                    CompletableFuture.allOf(c, r).join();
                } catch (java.util.concurrent.CompletionException ignored) {
                    // Se trata abajo, despues de recoger lo que si se creo.
                }
                caller = c.isCompletedExceptionally() ? null : c.join();
                recipient = r.isCompletedExceptionally() ? null : r.join();
                if (caller == null || recipient == null) {
                    Throwable cause = (caller == null ? c : r).handle((v, e) -> e).join();
                    throw cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null
                            ? cause.getCause() : cause;
                }
            } else {
                caller = realtimeKitClient.addParticipant(meetingId, callerName, callerCustomId);
                recipient = realtimeKitClient.addParticipant(meetingId, recipientName, recipientCustomId);
            }
            return new CloudflareRealtimeKitClient.ParticipantToken[]{caller, recipient};
        } catch (Throwable failure) {
            List<String> created = new java.util.ArrayList<>();
            if (caller != null) created.add(caller.participantId());
            if (recipient != null) created.add(recipient.participantId());
            resourceCleaner.cleanupAsync(meetingId, created);
            if (failure instanceof RuntimeException re) throw re;
            throw new BusinessRuleConflictException("REALTIMEKIT_PARTICIPANT_FAILED",
                    "No se pudo registrar al participante en la sala de audio.");
        }
    }

    @Transactional(readOnly = true)
    public Page<IntercomUnitDirectoryResponse> searchUnits(String query, Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Page<Property> propertiesPage;
        if (query != null && !query.trim().isEmpty()) {
            propertiesPage = propertyRepository.searchProperties(tenantId, query.trim(), pageable);
        } else {
            propertiesPage = propertyRepository.findByTenantId(tenantId, pageable);
        }

        return propertiesPage.map(prop -> {
            List<Resident> activeResidents = residentRepository.findByPropertyIdAndStatus(prop.getId(), ResidentStatus.ACTIVE);
            String structureName = prop.getStructure() != null ? prop.getStructure().getName() : null;
            List<String> residentNames = activeResidents.stream()
                    .map(Resident::getName)
                    .filter(n -> n != null && !n.isBlank())
                    .toList();
            return new IntercomUnitDirectoryResponse(
                    prop.getId(),
                    prop.getNumber(),
                    prop.getName(),
                    prop.getPath(),
                    prop.getType(),
                    structureName,
                    prop.getLevel(),
                    activeResidents.size(),
                    residentNames
            );
        });
    }

    @Transactional(readOnly = true)
    public List<IntercomResidentOptionResponse> getUnitResidents(UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Property property = propertyRepository.findByIdAndTenantId(propertyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("PROPERTY_NOT_FOUND", "La unidad no fue encontrada."));

        List<Resident> residents = residentRepository.findByPropertyIdAndStatus(property.getId(), ResidentStatus.ACTIVE);

        return residents.stream().map(r -> {
            boolean hasUser = r.getUser() != null;
            boolean isAvailable = hasUser && r.getStatus() == ResidentStatus.ACTIVE;
            String phone = (r.getPhone() != null && !r.getPhone().isBlank())
                    ? r.getPhone()
                    : (hasUser && r.getUser().getPhone() != null ? r.getUser().getPhone() : null);
            return new IntercomResidentOptionResponse(
                    r.getId(),
                    hasUser ? r.getUser().getId() : null,
                    r.getName(),
                    r.getType(),
                    r.getIsMainContact(),
                    hasUser,
                    isAvailable,
                    phone
            );
        }).toList();
    }

    @Transactional
    public IntercomCallResponse initiateCall(InitiateIntercomCallRequest request, UUID callerUserId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        User caller = userRepository.findById(callerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("CALLER_NOT_FOUND", "Usuario de portería no encontrado."));

        Property property = propertyRepository.findByIdAndTenantId(request.propertyId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("PROPERTY_NOT_FOUND", "La propiedad seleccionada no existe."));

        Resident recipientResident = residentRepository.findByIdAndTenantId(request.recipientResidentId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("RESIDENT_NOT_FOUND", "El residente seleccionado no existe."));

        if (!recipientResident.getProperty().getId().equals(property.getId())) {
            throw new BusinessRuleConflictException("RESIDENT_PROPERTY_MISMATCH",
                    "El residente no pertenece a la unidad seleccionada.");
        }

        if (recipientResident.getStatus() != ResidentStatus.ACTIVE) {
            throw new BusinessRuleConflictException("RESIDENT_INACTIVE",
                    "El residente no se encuentra activo.");
        }

        if (recipientResident.getUser() == null) {
            throw new BusinessRuleConflictException("RESIDENT_NO_USER_ACCOUNT",
                    "El residente no tiene una cuenta de usuario vinculada para recibir llamadas.");
        }

        User recipientUser = recipientResident.getUser();

        // P8-A6: las comprobaciones de «ocupado» se hacen con los dos participantes bloqueados (orden fijo, sin
        // interbloqueos). Antes eran comprobar-y-luego-insertar sin bloqueo: dos llamadas simultaneas al mismo residente
        // terminaban ambas RINGING (reproducido en IntercomLifecycleIT.simultaneousCallsToSameRecipient).
        java.util.stream.Stream.of("intercom:" + tenantId + ":" + callerUserId, "intercom:" + tenantId + ":" + recipientUser.getId())
                .distinct().sorted().forEach(intercomCallRepository::lockParticipantForCall);

        // Concurrency check: Does caller have an active call in this tenant?
        List<IntercomCallStatus> activeStatuses = List.of(IntercomCallStatus.INITIATING, IntercomCallStatus.RINGING, IntercomCallStatus.ACCEPTED);
        Optional<IntercomCall> ongoingCaller = intercomCallRepository.findFirstByTenantIdAndCallerUserIdAndStatusIn(tenantId, callerUserId, activeStatuses);
        if (ongoingCaller.isPresent()) {
            throw new BusinessRuleConflictException("CALLER_BUSY", "Tienes una llamada activa en curso. Finalízala antes de iniciar otra.");
        }

        // Concurrency check: Is recipient currently ringing or in a call?
        Optional<IntercomCall> ongoingRecipient = intercomCallRepository.findFirstByTenantIdAndRecipientUserIdAndStatusIn(tenantId, recipientUser.getId(), activeStatuses);
        if (ongoingRecipient.isPresent()) {
            throw new BusinessRuleConflictException("RECIPIENT_BUSY", "El residente o la unidad seleccionada se encuentra actualmente en otra llamada.");
        }

        long initiateNano = System.nanoTime();

        String propertyUnit = (property.getPath() != null && !property.getPath().isBlank())
                ? property.getPath()
                : (property.getName() != null && !property.getName().isBlank() ? property.getName() : property.getNumber());

        String callerDisplayName = (caller.getFullName() != null && !caller.getFullName().isBlank())
                ? caller.getFullName() + " (Portería)"
                : "Portería Principal";

        // 1. Create Cloudflare RealtimeKit meeting
        long tCreateStart = System.nanoTime();
        String meetingId = realtimeKitClient.createMeeting("Citofonía: " + propertyUnit);

        // P8-A3 (bandera): la barrera se cierra ANTES de emitir ningun token. Si no se puede cerrar, no hay llamada.
        if (earlyTokenEnabled && !realtimeKitClient.setMeetingActive(meetingId, false)) {
            resourceCleaner.cleanupAsync(meetingId, List.of());
            throw new BusinessRuleConflictException("REALTIMEKIT_GATE_FAILED",
                    "No se pudo preparar la sala de audio de forma segura. Intenta de nuevo.");
        }

        // 2-3. Participantes de porteria y residente en RealtimeKit. Son independientes entre si; con
        // havita.intercom.parallel-participant-creation=true (P8-A2, desactivado por defecto) se crean en paralelo.
        // Si algo falla tras crear la reunion, se limpia lo creado en Cloudflare (antes quedaba huerfano).
        CloudflareRealtimeKitClient.ParticipantToken[] tokens = createParticipants(meetingId,
                callerDisplayName, callerUserId.toString(), recipientResident.getName(), recipientUser.getId().toString());
        CloudflareRealtimeKitClient.ParticipantToken callerToken = tokens[0];
        CloudflareRealtimeKitClient.ParticipantToken recipientToken = tokens[1];

        // 4. Save call record
        OffsetDateTime now = OffsetDateTime.now();
        IntercomCall call = IntercomCall.builder()
                .property(property)
                .callerUser(caller)
                .callerName(callerDisplayName)
                .recipientResident(recipientResident)
                .recipientUser(recipientUser)
                .recipientName(recipientResident.getName())
                .propertyUnit(propertyUnit)
                .realtimeMeetingId(meetingId)
                .callerParticipantId(callerToken.participantId())
                .recipientParticipantId(recipientToken.participantId())
                .status(IntercomCallStatus.RINGING)
                .ringingAt(now)
                .createdByUserId(callerUserId)
                .build();
        call.setTenantId(tenantId);
        long tDbStart = System.nanoTime();
        intercomCallRepository.save(call);
        long dbSaveDurationMs = (System.nanoTime() - tDbStart) / 1_000_000;

        // Store tokens securely in-memory
        pendingRecipientTokens.put(call.getId(), recipientToken.token());
        callerTokens.put(call.getId(), callerToken.token());

        // 5. Publish real-time SSE event to notify resident
        Map<String, Object> payload = new HashMap<>();
        payload.put("callId", call.getId());
        payload.put("propertyId", property.getId());
        payload.put("propertyUnit", propertyUnit);
        payload.put("propertyNumber", property.getNumber());
        payload.put("propertyTower", property.getStructure() != null ? property.getStructure().getName() : null);
        payload.put("callerUserId", callerUserId);
        payload.put("callerName", callerDisplayName);
        payload.put("recipientUserId", recipientUser.getId());
        payload.put("recipientResidentId", recipientResident.getId());
        payload.put("recipientName", recipientResident.getName());
        payload.put("status", IntercomCallStatus.RINGING.name());
        payload.put("ringingAt", now);

        syncEventPublisher.publish(new SyncEvent("INTERCOM_CALL_RINGING", tenantId, payload, callAudience(call)));
        // P8-B: aviso push a los dispositivos del residente llamado (app en segundo plano, cerrada o movil bloqueado).
        eventPublisher.publishEvent(new IntercomPushEvent(call.getId(), tenantId, IntercomPushEvent.Kind.RINGING, null));
        long totalInitiateDurationMs = (System.nanoTime() - initiateNano) / 1_000_000;

        // P8-A8: solo identificadores en los registros (sin nombres de personas).
        log.info("Intercom call {} initiated: caller {} -> recipient user {} (property {}, meeting {}) in {}ms",
                call.getId(), callerUserId, recipientUser.getId(), property.getId(), meetingId, totalInitiateDurationMs);

        return toResponse(call, callerToken.token());
    }

    @Transactional
    public IntercomCallResponse acceptCall(UUID callId, UUID recipientUserId) {
        return acceptCall(callId, recipientUserId, null);
    }

    /**
     * @param deviceEndpoint P8-B: endpoint de push del dispositivo que contesta (opcional). Solo sirve para no enviarle a
     *                       ese mismo dispositivo el aviso «contestada en otro dispositivo».
     */
    @Transactional
    public IntercomCallResponse acceptCall(UUID callId, UUID recipientUserId, String deviceEndpoint) {
        long acceptNano = System.nanoTime();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        long tLockStart = System.nanoTime();
        IntercomCall call = intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("CALL_NOT_FOUND", "Llamada no encontrada."));
        long lockAcquisitionDurationMs = (System.nanoTime() - tLockStart) / 1_000_000;

        if (call.getRecipientUser() == null || !call.getRecipientUser().getId().equals(recipientUserId)) {
            throw new TenantAccessDeniedException("No estás autorizado para aceptar esta llamada.");
        }

        // P8-A8: la relacion del residente con la unidad debe seguir ACTIVA y vinculada a la misma cuenta. La membresia
        // de la comunidad ya se valida en cada peticion, pero un residente dado de baja de ESTA unidad podia aceptar.
        Resident recipientResident = call.getRecipientResident();
        if (recipientResident == null || recipientResident.getStatus() != ResidentStatus.ACTIVE
                || recipientResident.getUser() == null || !recipientResident.getUser().getId().equals(recipientUserId)) {
            throw new TenantAccessDeniedException("Ya no estás autorizado para atender llamadas de esta unidad.");
        }

        if (call.getStatus() != IntercomCallStatus.RINGING) {
            throw new BusinessRuleConflictException("CALL_NOT_RINGING",
                    "La llamada ya no se encuentra timbrando (estado: " + call.getStatus() + ").");
        }

        // P8-A3 (bandera): solo el backend, al aceptar el residente autorizado, abre la barrera. Si falla, la llamada
        // sigue timbrando y nadie puede entrar.
        if (earlyTokenEnabled && !realtimeKitClient.setMeetingActive(call.getRealtimeMeetingId(), true)) {
            throw new BusinessRuleConflictException("REALTIMEKIT_ACTIVATION_FAILED",
                    "No se pudo abrir la sala de audio. Intenta contestar de nuevo.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        call.setStatus(IntercomCallStatus.ACCEPTED);
        call.setAcceptedAt(now);
        intercomCallRepository.save(call);

        String recipientToken = pendingRecipientTokens.remove(call.getId());
        if (recipientToken == null) {
            log.warn("Recipient token was not found in pending cache for call {}. Refreshing participant...", callId);
            CloudflareRealtimeKitClient.ParticipantToken refreshed = realtimeKitClient.addParticipant(
                    call.getRealtimeMeetingId(), call.getRecipientName(), recipientUserId.toString());
            recipientToken = refreshed.token();
        }

        // Notify caller and others that call was accepted
        Map<String, Object> payload = new HashMap<>();
        payload.put("callId", call.getId());
        payload.put("status", IntercomCallStatus.ACCEPTED.name());
        payload.put("acceptedAt", now);
        syncEventPublisher.publish(new SyncEvent("INTERCOM_CALL_ACCEPTED", tenantId, payload, callAudience(call)));
        eventPublisher.publishEvent(new IntercomPushEvent(call.getId(), tenantId, IntercomPushEvent.Kind.ANSWERED, deviceEndpoint));
        long totalAcceptDurationMs = (System.nanoTime() - acceptNano) / 1_000_000;

        log.info("Intercom call {} atomically accepted by recipient {} in {}ms. Ringing: {}, Accepted: {}",
                call.getId(), recipientUserId, totalAcceptDurationMs, call.getRingingAt(), now);
        return toResponse(call, recipientToken);
    }

    @Transactional
    public IntercomCallResponse rejectCall(UUID callId, UUID recipientUserId) {
        return rejectCall(callId, recipientUserId, null);
    }

    /** @param deviceEndpoint P8-B: como en {@link #acceptCall(UUID, UUID, String)}. */
    @Transactional
    public IntercomCallResponse rejectCall(UUID callId, UUID recipientUserId, String deviceEndpoint) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        IntercomCall call = intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("CALL_NOT_FOUND", "Llamada no encontrada."));

        if (call.getRecipientUser() == null || !call.getRecipientUser().getId().equals(recipientUserId)) {
            throw new TenantAccessDeniedException("No estás autorizado para rechazar esta llamada.");
        }

        if (call.getStatus() != IntercomCallStatus.RINGING) {
            return toResponse(call, null);
        }

        OffsetDateTime now = OffsetDateTime.now();
        call.setStatus(IntercomCallStatus.REJECTED);
        call.setEndedAt(now);
        call.setDurationSeconds(0);
        call.setEndReason("REJECTED_BY_RESIDENT");
        intercomCallRepository.save(call);

        cleanupCallResources(call);

        Map<String, Object> payload = new HashMap<>();
        payload.put("callId", call.getId());
        payload.put("status", IntercomCallStatus.REJECTED.name());
        payload.put("endedAt", now);
        payload.put("durationSeconds", 0);
        syncEventPublisher.publish(new SyncEvent("INTERCOM_CALL_REJECTED", tenantId, payload, callAudience(call)));
        eventPublisher.publishEvent(new IntercomPushEvent(call.getId(), tenantId, IntercomPushEvent.Kind.REJECTED, deviceEndpoint));

        log.info("Intercom call {} rejected by recipient {}", call.getId(), recipientUserId);
        return toResponse(call, null);
    }

    @Transactional
    public IntercomCallResponse cancelCall(UUID callId, UUID callerUserId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        IntercomCall call = intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("CALL_NOT_FOUND", "Llamada no encontrada."));

        if (!call.getCallerUser().getId().equals(callerUserId)) {
            throw new TenantAccessDeniedException("Solo quien inició la llamada puede cancelarla.");
        }

        if (call.getStatus() != IntercomCallStatus.RINGING && call.getStatus() != IntercomCallStatus.INITIATING) {
            return toResponse(call, null);
        }

        OffsetDateTime now = OffsetDateTime.now();
        call.setStatus(IntercomCallStatus.CANCELLED);
        call.setEndedAt(now);
        call.setDurationSeconds(0);
        call.setEndReason("CANCELLED_BY_CALLER");
        intercomCallRepository.save(call);

        cleanupCallResources(call);

        Map<String, Object> payload = new HashMap<>();
        payload.put("callId", call.getId());
        payload.put("status", IntercomCallStatus.CANCELLED.name());
        payload.put("endedAt", now);
        payload.put("durationSeconds", 0);
        syncEventPublisher.publish(new SyncEvent("INTERCOM_CALL_CANCELLED", tenantId, payload, callAudience(call)));
        eventPublisher.publishEvent(new IntercomPushEvent(call.getId(), tenantId, IntercomPushEvent.Kind.CANCELLED, null));

        log.info("Intercom call {} cancelled by caller {}. Ringing: {}, Cancelled: {}",
                call.getId(), callerUserId, call.getRingingAt(), now);
        return toResponse(call, null);
    }

    @Transactional
    public IntercomCallResponse endCall(UUID callId, UUID currentUserId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        IntercomCall call = intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("CALL_NOT_FOUND", "Llamada no encontrada."));

        boolean isCaller = call.getCallerUser().getId().equals(currentUserId);
        boolean isRecipient = call.getRecipientUser() != null && call.getRecipientUser().getId().equals(currentUserId);

        if (!isCaller && !isRecipient) {
            throw new TenantAccessDeniedException("No perteneces a esta llamada.");
        }

        if (call.getStatus() == IntercomCallStatus.ENDED || call.getStatus() == IntercomCallStatus.CANCELLED ||
            call.getStatus() == IntercomCallStatus.REJECTED || call.getStatus() == IntercomCallStatus.MISSED) {
            return toResponse(call, null);
        }

        OffsetDateTime now = OffsetDateTime.now();
        int duration = 0;
        if (call.getStatus() == IntercomCallStatus.ACCEPTED) {
            if (call.getConnectedAt() != null) {
                duration = (int) Duration.between(call.getConnectedAt(), now).toSeconds();
                call.setEndReason(isCaller ? "HUNG_UP_BY_CALLER" : "HUNG_UP_BY_RESIDENT");
            } else if (call.getAcceptedAt() != null) {
                // Resident accepted but bilateral audio was never established
                duration = 0;
                call.setEndReason("ACCEPTED_NEVER_CONNECTED");
                call.setTechnicalFailureReason("AUDIO_HANDSHAKE_INCOMPLETE");
            }
            if (duration < 0) duration = 0;
        }

        call.setStatus(IntercomCallStatus.ENDED);
        call.setEndedAt(now);
        call.setDurationSeconds(duration);
        intercomCallRepository.save(call);

        cleanupCallResources(call);

        Map<String, Object> payload = new HashMap<>();
        payload.put("callId", call.getId());
        payload.put("status", IntercomCallStatus.ENDED.name());
        payload.put("endedAt", now);
        payload.put("durationSeconds", duration);
        syncEventPublisher.publish(new SyncEvent("INTERCOM_CALL_ENDED", tenantId, payload, callAudience(call)));

        log.info("Intercom call {} ended by user {}. Ringing: {}, Accepted: {}, Connected: {}, Ended: {}. Duration: {}s",
                call.getId(), currentUserId, call.getRingingAt(), call.getAcceptedAt(), call.getConnectedAt(), now, duration);
        return toResponse(call, null);
    }

    @Transactional
    public IntercomCallResponse markConnected(UUID callId, UUID currentUserId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        IntercomCall call = intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("CALL_NOT_FOUND", "Llamada no encontrada."));

        boolean isCaller = call.getCallerUser().getId().equals(currentUserId);
        boolean isRecipient = call.getRecipientUser() != null && call.getRecipientUser().getId().equals(currentUserId);

        if (!isCaller && !isRecipient) {
            throw new TenantAccessDeniedException("No perteneces a esta llamada.");
        }

        if (call.getStatus() != IntercomCallStatus.ACCEPTED) {
            return toResponse(call, null);
        }

        OffsetDateTime now = OffsetDateTime.now();
        boolean newlyBothConnected = false;
        OffsetDateTime effectiveConnectedAt = null;

        if (isCaller) {
            if (call.getCallerConnectedAt() != null) {
                // Idempotent: already marked connected for caller
                String token = callerTokens.get(call.getId());
                return toResponse(call, token);
            }
            call.setCallerConnectedAt(now);
            log.info("Intercom call {} caller joined room at {}", call.getId(), now);
        } else {
            if (call.getRecipientConnectedAt() != null) {
                // Idempotent: already marked connected for recipient
                String token = pendingRecipientTokens.get(call.getId());
                return toResponse(call, token);
            }
            call.setRecipientConnectedAt(now);
            log.info("Intercom call {} recipient joined room at {}", call.getId(), now);
        }

        if (call.getCallerConnectedAt() != null && call.getRecipientConnectedAt() != null && call.getConnectedAt() == null) {
            effectiveConnectedAt = call.getCallerConnectedAt().isAfter(call.getRecipientConnectedAt())
                    ? call.getCallerConnectedAt() : call.getRecipientConnectedAt();
            call.setConnectedAt(effectiveConnectedAt);
            newlyBothConnected = true;

            long acceptToBothMs = call.getAcceptedAt() != null
                    ? Duration.between(call.getAcceptedAt(), effectiveConnectedAt).toMillis()
                    : 0;

            log.info("Intercom call {} BOTH participants connected. Ringing: {}, Accepted: {}, Connected: {}, acceptToBothConnectedMs: {}ms",
                    call.getId(), call.getRingingAt(), call.getAcceptedAt(), effectiveConnectedAt, acceptToBothMs);
        }

        intercomCallRepository.save(call);

        if (newlyBothConnected) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("callId", call.getId());
            payload.put("status", IntercomCallStatus.ACCEPTED.name());
            payload.put("callerConnectedAt", call.getCallerConnectedAt());
            payload.put("recipientConnectedAt", call.getRecipientConnectedAt());
            payload.put("connectedAt", effectiveConnectedAt);
            syncEventPublisher.publish(new SyncEvent("INTERCOM_CALL_CONNECTED", tenantId, payload, callAudience(call)));
        }

        String token = isCaller ? callerTokens.get(call.getId()) : pendingRecipientTokens.get(call.getId());
        return toResponse(call, token);
    }

    /**
     * P8-A3 (bandera): token del residente llamado MIENTRAS TIMBRA, para que inicialice el SDK sin unirse ni capturar
     * audio. Garantias:
     * <ul>
     *   <li>Mismas reglas que aceptar: solo el residente destinatario, con relacion ACTIVA con la unidad y vinculada a su
     *       cuenta, de la comunidad del contexto, y solo con la llamada en RINGING.</li>
     *   <li>La reunion esta INACTIVE desde antes de emitir el token: Cloudflare impide unirse hasta que el backend la
     *       active al aceptar.</li>
     *   <li>Al rechazar, cancelar o perder la llamada, el participante se borra, lo que revoca sus tokens.</li>
     * </ul>
     * Con la bandera desactivada responde como si la ruta no existiera (404).
     */
    @Transactional(readOnly = true)
    public IntercomCallResponse prepareCall(UUID callId, UUID recipientUserId) {
        if (!earlyTokenEnabled) {
            throw new ResourceNotFoundException("CALL_PREPARE_DISABLED", "Recurso no disponible.");
        }
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        IntercomCall call = intercomCallRepository.findByIdAndTenantId(callId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("CALL_NOT_FOUND", "Llamada no encontrada."));
        if (call.getRecipientUser() == null || !call.getRecipientUser().getId().equals(recipientUserId)) {
            throw new TenantAccessDeniedException("No estás autorizado para esta llamada.");
        }
        Resident recipientResident = call.getRecipientResident();
        if (recipientResident == null || recipientResident.getStatus() != ResidentStatus.ACTIVE
                || recipientResident.getUser() == null || !recipientResident.getUser().getId().equals(recipientUserId)) {
            throw new TenantAccessDeniedException("Ya no estás autorizado para atender llamadas de esta unidad.");
        }
        if (call.getStatus() != IntercomCallStatus.RINGING) {
            throw new BusinessRuleConflictException("CALL_NOT_RINGING", "La llamada ya no se encuentra timbrando.");
        }
        String token = pendingRecipientTokens.get(call.getId());
        if (token == null) {
            // Sin token en memoria (p. ej. tras un reinicio): se contesta por el flujo normal.
            throw new BusinessRuleConflictException("CALL_PREPARE_UNAVAILABLE", "Preparación no disponible para esta llamada.");
        }
        return toResponse(call, token);
    }

    /**
     * P8-B: llamada entrante que esta timbrando AHORA para el usuario en la comunidad actual, o vacio. Permite recuperar
     * una llamada cuyo evento SSE se perdio (app abierta tarde, pulsar el aviso push, reconexion).
     * Mismas condiciones que para aceptar: el usuario es el llamado, su relacion con la unidad sigue ACTIVA y la llamada
     * sigue en RINGING dentro del plazo. Nunca devuelve el token de RealtimeKit (solo {@link #acceptCall} lo entrega).
     */
    @Transactional(readOnly = true)
    public Optional<IntercomCallResponse> findIncomingCall(UUID currentUserId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return intercomCallRepository.findFirstByTenantIdAndRecipientUserIdAndStatusIn(
                        tenantId, currentUserId, List.of(IntercomCallStatus.RINGING))
                .filter(call -> {
                    Resident r = call.getRecipientResident();
                    return r != null && r.getStatus() == ResidentStatus.ACTIVE && r.getUser() != null
                            && r.getUser().getId().equals(currentUserId)
                            && call.getRingingAt() != null
                            && call.getRingingAt().isAfter(OffsetDateTime.now().minusSeconds(RING_TIMEOUT_SECONDS));
                })
                .map(call -> toResponse(call, null));
    }

    @Transactional(readOnly = true)
    public IntercomCallResponse getCallDetail(UUID callId, UUID currentUserId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        IntercomCall call = intercomCallRepository.findByIdAndTenantId(callId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("CALL_NOT_FOUND", "Llamada no encontrada."));

        boolean isCaller = call.getCallerUser().getId().equals(currentUserId);
        boolean isRecipient = call.getRecipientUser() != null && call.getRecipientUser().getId().equals(currentUserId);

        if (!isCaller && !isRecipient) {
            throw new TenantAccessDeniedException("No tienes permiso para consultar esta llamada.");
        }

        String token = null;
        if (isCaller) {
            token = callerTokens.get(call.getId());
        } else if (isRecipient && call.getStatus() == IntercomCallStatus.ACCEPTED) {
            token = pendingRecipientTokens.get(call.getId());
        }

        return toResponse(call, token);
    }

    @Transactional(readOnly = true)
    public Page<IntercomCallResponse> getCallHistory(Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return intercomCallRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable)
                .map(call -> toResponse(call, null));
    }

    /**
     * P8-A3: interruptor de las tareas programadas de citofonia (por defecto activas). Solo lo desactivan las pruebas de
     * integracion de citofonia, que comparten PostgreSQL con otros contextos y llaman a las tareas de forma explicita.
     */
    @Value("${havita.intercom.scheduler.enabled:true}")
    private boolean schedulerEnabled = true;

    @Scheduled(fixedDelay = 10000)
    @Transactional
    public void scheduledRingTimeouts() {
        if (schedulerEnabled) handleRingTimeouts();
    }

    @Scheduled(fixedDelay = 10000)
    @Transactional
    public void scheduledAcceptedNeverConnectedTimeouts() {
        if (schedulerEnabled) handleAcceptedNeverConnectedTimeouts();
    }

    @Transactional
    public void handleRingTimeouts() {
        OffsetDateTime timeoutThreshold = OffsetDateTime.now().minusSeconds(RING_TIMEOUT_SECONDS);
        // P8-A6: solo ids; cada llamada se relee con bloqueo y se comprueba que SIGUE timbrando. Antes se guardaba la
        // entidad leida sin bloqueo, y una aceptacion concurrente podia quedar sobrescrita como MISSED.
        List<UUID> ringingCallIds = intercomCallRepository.findIdsByStatusAndCreatedAtBefore(
                IntercomCallStatus.RINGING, timeoutThreshold);

        for (UUID ringingCallId : ringingCallIds) {
            IntercomCall call = intercomCallRepository.findByIdForUpdate(ringingCallId).orElse(null);
            if (call == null || call.getStatus() != IntercomCallStatus.RINGING) {
                continue;
            }
            OffsetDateTime now = OffsetDateTime.now();
            call.setStatus(IntercomCallStatus.MISSED);
            call.setEndedAt(now);
            call.setEndReason("NO_ANSWER_TIMEOUT");
            intercomCallRepository.save(call);

            cleanupCallResources(call);

            Map<String, Object> payload = new HashMap<>();
            payload.put("callId", call.getId());
            payload.put("status", IntercomCallStatus.MISSED.name());
            payload.put("endedAt", now);
            syncEventPublisher.publish(new SyncEvent("INTERCOM_CALL_MISSED", call.getTenantId(), payload, callAudience(call)));
            eventPublisher.publishEvent(new IntercomPushEvent(call.getId(), call.getTenantId(), IntercomPushEvent.Kind.MISSED, null));
            log.info("Intercom call {} timed out and was marked as MISSED", call.getId());
        }
    }

    /**
     * P8-A6: llamadas aceptadas que nunca llegaron a conectarse (ningun audio establecido) dentro del plazo. Se
     * cierran igual que {@link #endCall} cuando no hubo conexion: ENDED, duracion 0 y motivo tecnico. Se liberan los
     * recursos de RealtimeKit y se avisa por SSE a los dos participantes.
     */
    @Transactional
    public void handleAcceptedNeverConnectedTimeouts() {
        OffsetDateTime threshold = OffsetDateTime.now().minusSeconds(acceptConnectTimeoutSeconds);
        List<UUID> stalled = intercomCallRepository.findIdsAcceptedNeverConnectedBefore(IntercomCallStatus.ACCEPTED, threshold);

        for (UUID stalledCallId : stalled) {
            IntercomCall call = intercomCallRepository.findByIdForUpdate(stalledCallId).orElse(null);
            if (call == null || call.getStatus() != IntercomCallStatus.ACCEPTED || call.getConnectedAt() != null
                    || call.getAcceptedAt() == null || call.getAcceptedAt().isAfter(threshold)) {
                continue;
            }
            OffsetDateTime now = OffsetDateTime.now();
            call.setStatus(IntercomCallStatus.ENDED);
            call.setEndedAt(now);
            call.setDurationSeconds(0);
            call.setEndReason("ACCEPTED_NEVER_CONNECTED_TIMEOUT");
            call.setTechnicalFailureReason("AUDIO_HANDSHAKE_TIMEOUT");
            intercomCallRepository.save(call);

            cleanupCallResources(call);

            Map<String, Object> payload = new HashMap<>();
            payload.put("callId", call.getId());
            payload.put("status", IntercomCallStatus.ENDED.name());
            payload.put("endedAt", now);
            payload.put("durationSeconds", 0);
            payload.put("endReason", call.getEndReason());
            syncEventPublisher.publish(new SyncEvent("INTERCOM_CALL_ENDED", call.getTenantId(), payload, callAudience(call)));
            log.info("Intercom call {} accepted at {} never connected within {}s: closed as ENDED",
                    call.getId(), call.getAcceptedAt(), acceptConnectTimeoutSeconds);
        }
    }

    /** Eventos de una llamada: gestion (porteria/admin) y el residente llamado mientras siga activo en la unidad. */
    private static com.havita.modules.sync.domain.SyncAudience callAudience(IntercomCall call) {
        return com.havita.modules.sync.domain.SyncAudience.unitUsers(
                call.getProperty() != null ? call.getProperty().getId() : null,
                java.util.Collections.singletonList(call.getRecipientUser() != null ? call.getRecipientUser().getId() : null));
    }

    private void cleanupCallResources(IntercomCall call) {
        pendingRecipientTokens.remove(call.getId());
        callerTokens.remove(call.getId());

        String meetingId = call.getRealtimeMeetingId();
        String callerParticipantId = call.getCallerParticipantId();
        String recipientParticipantId = call.getRecipientParticipantId();

        // P8-A3: kick-all, DELETE de participantes (revoca sus tokens) y reunion INACTIVE, de forma asincrona, con
        // reintentos controlados y conciliacion periodica (RealtimeKitResourceCleaner). Antes era un unico intento.
        if (meetingId != null && !meetingId.isBlank()) {
            List<String> participants = new java.util.ArrayList<>();
            if (callerParticipantId != null) participants.add(callerParticipantId);
            if (recipientParticipantId != null) participants.add(recipientParticipantId);
            resourceCleaner.cleanupAsync(meetingId, participants);
        }
    }

    private IntercomCallResponse toResponse(IntercomCall call, String participantToken) {
        String propNumber = call.getProperty() != null ? call.getProperty().getNumber() : call.getPropertyUnit();
        String propTower = (call.getProperty() != null && call.getProperty().getStructure() != null)
                ? call.getProperty().getStructure().getName() : null;
        String callerRole = "SECURITY";
        String recipientRole = (call.getRecipientResident() != null && call.getRecipientResident().getType() != null)
                ? call.getRecipientResident().getType() : "RESIDENT";

        return new IntercomCallResponse(
                call.getId(),
                call.getId(),
                call.getProperty().getId(),
                call.getPropertyUnit(),
                propNumber,
                propTower,
                call.getCallerUser().getId(),
                call.getCallerName(),
                callerRole,
                call.getRecipientResident().getId(),
                call.getRecipientUser() != null ? call.getRecipientUser().getId() : null,
                call.getRecipientName(),
                recipientRole,
                call.getStatus(),
                call.getRealtimeMeetingId(),
                call.getRealtimeMeetingId(),
                participantToken,
                call.getRingingAt(),
                call.getAcceptedAt(),
                call.getCallerConnectedAt(),
                call.getRecipientConnectedAt(),
                call.getConnectedAt(),
                call.getEndedAt(),
                call.getDurationSeconds(),
                call.getEndReason(),
                call.getCreatedAt()
        );
    }
}
