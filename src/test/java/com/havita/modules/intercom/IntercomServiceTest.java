package com.havita.modules.intercom;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.TenantAccessDeniedException;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.intercom.api.dto.IntercomCallResponse;
import com.havita.modules.intercom.application.CloudflareRealtimeKitClient;
import com.havita.modules.intercom.application.IntercomService;
import com.havita.modules.intercom.domain.IntercomCall;
import com.havita.modules.intercom.domain.IntercomCallStatus;
import com.havita.modules.intercom.infrastructure.IntercomCallRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.sync.application.SyncEventPublisher;
import com.havita.modules.sync.domain.SyncEvent;
import com.havita.security.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class IntercomServiceTest {

    @Mock private IntercomCallRepository intercomCallRepository;
    @Mock private PropertyRepository propertyRepository;
    @Mock private ResidentRepository residentRepository;
    @Mock private UserRepository userRepository;
    @Mock private CloudflareRealtimeKitClient realtimeKitClient;
    @Mock private SyncEventPublisher syncEventPublisher;
    @Mock private com.havita.modules.intercom.application.RealtimeKitResourceCleaner resourceCleaner;
    // P8-B: avisos Web Push (eventos tras el commit).
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private IntercomService intercomService;

    private UUID tenantId;
    private UUID callerUserId;
    private UUID recipientUserId;
    private UUID callId;
    private User callerUser;
    private User recipientUser;
    private Resident recipientResident;
    private Property property;
    private IntercomCall call;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        TenantContextHolder.setTenantId(tenantId);

        callerUserId = UUID.randomUUID();
        recipientUserId = UUID.randomUUID();
        callId = UUID.randomUUID();

        callerUser = new User();
        callerUser.setId(callerUserId);
        callerUser.setFullName("Portero Carlos");

        recipientUser = new User();
        recipientUser.setId(recipientUserId);
        recipientUser.setFullName("Residente Ana");

        recipientResident = new Resident();
        recipientResident.setId(UUID.randomUUID());
        recipientResident.setUser(recipientUser);
        recipientResident.setName("Residente Ana");
        recipientResident.setType("OWNER");

        property = new Property();
        property.setId(UUID.randomUUID());
        property.setNumber("Apto 101");

        call = IntercomCall.builder()
                .property(property)
                .callerUser(callerUser)
                .callerName(callerUser.getFullName())
                .recipientResident(recipientResident)
                .recipientUser(recipientUser)
                .recipientName(recipientResident.getName())
                .propertyUnit("Torre 1 - Apto 101")
                .realtimeMeetingId("cf-meeting-123")
                .callerParticipantId("caller-part-1")
                .recipientParticipantId("recip-part-2")
                .status(IntercomCallStatus.RINGING)
                .ringingAt(OffsetDateTime.now())
                .createdByUserId(callerUserId)
                .build();
        call.setId(callId);
        call.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void markConnected_IdempotentDuplicateHandling() {
        call.setStatus(IntercomCallStatus.ACCEPTED);
        call.setAcceptedAt(OffsetDateTime.now());

        when(intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)).thenReturn(Optional.of(call));
        when(intercomCallRepository.save(any(IntercomCall.class))).thenAnswer(i -> i.getArgument(0));

        // 1. Recipient first connection notification
        IntercomCallResponse res1 = intercomService.markConnected(callId, recipientUserId);
        assertNotNull(call.getRecipientConnectedAt());
        assertNull(call.getCallerConnectedAt());
        assertNull(call.getConnectedAt());

        // 2. Recipient duplicate connection notification (e.g. 7ms later)
        IntercomCallResponse res2 = intercomService.markConnected(callId, recipientUserId);
        // Repository save should NOT have been invoked a second time for duplicate
        verify(intercomCallRepository, times(1)).save(any(IntercomCall.class));
        verify(syncEventPublisher, never()).publish(any(SyncEvent.class));

        // 3. Caller first connection notification
        IntercomCallResponse res3 = intercomService.markConnected(callId, callerUserId);
        assertNotNull(call.getCallerConnectedAt());
        assertNotNull(call.getConnectedAt());
        // INTERCOM_CALL_CONNECTED published exactly once
        verify(syncEventPublisher, times(1)).publish(argThat(event -> "INTERCOM_CALL_CONNECTED".equals(event.type())));

        // 4. Caller duplicate connection notification (e.g. 9ms later)
        IntercomCallResponse res4 = intercomService.markConnected(callId, callerUserId);
        // Verify no extra saves or SSE publications
        verify(intercomCallRepository, times(2)).save(any(IntercomCall.class));
        verify(syncEventPublisher, times(1)).publish(argThat(event -> "INTERCOM_CALL_CONNECTED".equals(event.type())));
    }

    @Test
    void acceptCall_AuthoritativeValidation() {
        when(intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)).thenReturn(Optional.of(call));
        when(intercomCallRepository.save(any(IntercomCall.class))).thenAnswer(i -> i.getArgument(0));
        when(realtimeKitClient.addParticipant(any(), any(), any()))
                .thenReturn(new CloudflareRealtimeKitClient.ParticipantToken("recip-1", "token-123"));

        IntercomCallResponse response = intercomService.acceptCall(callId, recipientUserId);
        assertEquals(IntercomCallStatus.ACCEPTED, call.getStatus());
        assertNotNull(call.getAcceptedAt());
        verify(syncEventPublisher, times(1)).publish(argThat(event -> "INTERCOM_CALL_ACCEPTED".equals(event.type())));

        // Attempting to accept an already accepted (or cancelled) call must fail
        assertThrows(BusinessRuleConflictException.class, () -> intercomService.acceptCall(callId, recipientUserId));
    }

    @Test
    void acceptCall_OnlyRecipientCanAccept() {
        when(intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)).thenReturn(Optional.of(call));

        assertThrows(TenantAccessDeniedException.class, () -> intercomService.acceptCall(callId, callerUserId));
    }

    @Test
    void cancelCall_AuthoritativeCancellation() {
        when(intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)).thenReturn(Optional.of(call));
        when(intercomCallRepository.save(any(IntercomCall.class))).thenAnswer(i -> i.getArgument(0));

        IntercomCallResponse response = intercomService.cancelCall(callId, callerUserId);
        assertEquals(IntercomCallStatus.CANCELLED, call.getStatus());
        assertEquals(0, call.getDurationSeconds());
        assertEquals("CANCELLED_BY_CALLER", call.getEndReason());
        verify(syncEventPublisher, times(1)).publish(argThat(event -> "INTERCOM_CALL_CANCELLED".equals(event.type())));
    }

    @Test
    void cancelCall_OnlyCallerCanCancel() {
        when(intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)).thenReturn(Optional.of(call));

        assertThrows(TenantAccessDeniedException.class, () -> intercomService.cancelCall(callId, recipientUserId));
    }

    @Test
    void endCall_DurationStrictlyCalculatedFromConnectedAt() {
        call.setStatus(IntercomCallStatus.ACCEPTED);
        OffsetDateTime acceptedTime = OffsetDateTime.now().minusSeconds(15);
        OffsetDateTime connectedTime = OffsetDateTime.now().minusSeconds(10);
        call.setAcceptedAt(acceptedTime);
        call.setConnectedAt(connectedTime);

        when(intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)).thenReturn(Optional.of(call));
        when(intercomCallRepository.save(any(IntercomCall.class))).thenAnswer(i -> i.getArgument(0));

        IntercomCallResponse response = intercomService.endCall(callId, callerUserId);
        assertEquals(IntercomCallStatus.ENDED, call.getStatus());
        // Duration should be based on connectedAt (approx 10s), NOT acceptedAt (15s)
        assertTrue(call.getDurationSeconds() >= 9 && call.getDurationSeconds() <= 11);
        assertEquals("HUNG_UP_BY_CALLER", call.getEndReason());
        verify(syncEventPublisher, times(1)).publish(argThat(event -> "INTERCOM_CALL_ENDED".equals(event.type())));
    }

    @Test
    void endCall_AcceptedNeverConnectedGivesZeroDuration() {
        call.setStatus(IntercomCallStatus.ACCEPTED);
        call.setAcceptedAt(OffsetDateTime.now().minusSeconds(5));
        call.setConnectedAt(null);

        when(intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)).thenReturn(Optional.of(call));
        when(intercomCallRepository.save(any(IntercomCall.class))).thenAnswer(i -> i.getArgument(0));

        IntercomCallResponse response = intercomService.endCall(callId, recipientUserId);
        assertEquals(IntercomCallStatus.ENDED, call.getStatus());
        assertEquals(0, call.getDurationSeconds());
        assertEquals("ACCEPTED_NEVER_CONNECTED", call.getEndReason());
        assertEquals("AUDIO_HANDSHAKE_INCOMPLETE", call.getTechnicalFailureReason());
    }

    @Test
    void rejectCall_AuthoritativeRejection() {
        when(intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)).thenReturn(Optional.of(call));
        when(intercomCallRepository.save(any(IntercomCall.class))).thenAnswer(i -> i.getArgument(0));

        IntercomCallResponse response = intercomService.rejectCall(callId, recipientUserId);
        assertEquals(IntercomCallStatus.REJECTED, call.getStatus());
        assertEquals(0, call.getDurationSeconds());
        assertEquals("REJECTED_BY_RESIDENT", call.getEndReason());
        verify(syncEventPublisher, times(1)).publish(argThat(event -> "INTERCOM_CALL_REJECTED".equals(event.type())));
    }

    @Test
    void acceptCall_RevokedResidentCannotAccept() {
        recipientResident.setStatus(com.havita.modules.resident.domain.ResidentStatus.INACTIVE);
        when(intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)).thenReturn(Optional.of(call));

        assertThrows(TenantAccessDeniedException.class, () -> intercomService.acceptCall(callId, recipientUserId));
        assertEquals(IntercomCallStatus.RINGING, call.getStatus());
        verify(intercomCallRepository, never()).save(any(IntercomCall.class));
        verify(syncEventPublisher, never()).publish(any());
    }

    @Test
    void acceptCall_ResidentUnlinkedFromAccountCannotAccept() {
        User other = new User();
        other.setId(UUID.randomUUID());
        recipientResident.setUser(other);
        when(intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)).thenReturn(Optional.of(call));

        assertThrows(TenantAccessDeniedException.class, () -> intercomService.acceptCall(callId, recipientUserId));
        assertEquals(IntercomCallStatus.RINGING, call.getStatus());
    }

    @Test
    void ringTimeout_SkipsCallAcceptedMeanwhile() {
        // P8-A6: la consulta devuelve la llamada, pero al releerla con bloqueo ya esta ACEPTADA: no se sobrescribe.
        call.setStatus(IntercomCallStatus.ACCEPTED);
        when(intercomCallRepository.findIdsByStatusAndCreatedAtBefore(eq(IntercomCallStatus.RINGING), any()))
                .thenReturn(java.util.List.of(callId));
        when(intercomCallRepository.findByIdForUpdate(callId)).thenReturn(Optional.of(call));

        intercomService.handleRingTimeouts();

        assertEquals(IntercomCallStatus.ACCEPTED, call.getStatus());
        verify(intercomCallRepository, never()).save(any(IntercomCall.class));
        verify(syncEventPublisher, never()).publish(any());
        verifyNoInteractions(realtimeKitClient);
    }

    @Test
    void ringTimeout_MarksStillRingingCallAsMissed() {
        when(intercomCallRepository.findIdsByStatusAndCreatedAtBefore(eq(IntercomCallStatus.RINGING), any()))
                .thenReturn(java.util.List.of(callId));
        when(intercomCallRepository.findByIdForUpdate(callId)).thenReturn(Optional.of(call));

        intercomService.handleRingTimeouts();

        assertEquals(IntercomCallStatus.MISSED, call.getStatus());
        assertEquals("NO_ANSWER_TIMEOUT", call.getEndReason());
        verify(syncEventPublisher, times(1)).publish(argThat(event -> "INTERCOM_CALL_MISSED".equals(event.type())));
    }

    @Test
    void acceptedNeverConnected_ClosedAfterTimeout_ConnectedUntouched() {
        call.setStatus(IntercomCallStatus.ACCEPTED);
        call.setAcceptedAt(OffsetDateTime.now().minusMinutes(5));
        when(intercomCallRepository.findIdsAcceptedNeverConnectedBefore(eq(IntercomCallStatus.ACCEPTED), any()))
                .thenReturn(java.util.List.of(callId));
        when(intercomCallRepository.findByIdForUpdate(callId)).thenReturn(Optional.of(call));

        intercomService.handleAcceptedNeverConnectedTimeouts();

        assertEquals(IntercomCallStatus.ENDED, call.getStatus());
        assertEquals(0, call.getDurationSeconds());
        assertEquals("ACCEPTED_NEVER_CONNECTED_TIMEOUT", call.getEndReason());
        verify(syncEventPublisher, times(1)).publish(argThat(event -> "INTERCOM_CALL_ENDED".equals(event.type())));

        // Si entre la consulta y el bloqueo quedo conectada, no se toca.
        IntercomCall connected = IntercomCall.builder().status(IntercomCallStatus.ACCEPTED)
                .acceptedAt(OffsetDateTime.now().minusMinutes(5)).connectedAt(OffsetDateTime.now().minusMinutes(4)).build();
        UUID connectedId = UUID.randomUUID();
        when(intercomCallRepository.findIdsAcceptedNeverConnectedBefore(eq(IntercomCallStatus.ACCEPTED), any()))
                .thenReturn(java.util.List.of(connectedId));
        when(intercomCallRepository.findByIdForUpdate(connectedId)).thenReturn(Optional.of(connected));
        intercomService.handleAcceptedNeverConnectedTimeouts();
        assertEquals(IntercomCallStatus.ACCEPTED, connected.getStatus());
    }

    // ---- P8-A2: creacion de participantes (en paralelo con la bandera) y limpieza si falla ----

    private com.havita.modules.intercom.api.dto.InitiateIntercomCallRequest initiateFixture() {
        recipientResident.setProperty(property);
        recipientResident.setStatus(com.havita.modules.resident.domain.ResidentStatus.ACTIVE);
        when(userRepository.findById(callerUserId)).thenReturn(Optional.of(callerUser));
        when(propertyRepository.findByIdAndTenantId(property.getId(), tenantId)).thenReturn(Optional.of(property));
        when(residentRepository.findByIdAndTenantId(recipientResident.getId(), tenantId)).thenReturn(Optional.of(recipientResident));
        when(realtimeKitClient.createMeeting(any())).thenReturn("meeting-1");
        return new com.havita.modules.intercom.api.dto.InitiateIntercomCallRequest(property.getId(), recipientResident.getId());
    }

    @Test
    void initiateCall_ParallelParticipantCreation_CreatesBothAndSavesRinging() {
        org.springframework.test.util.ReflectionTestUtils.setField(intercomService, "parallelParticipantCreation", true);
        var req = initiateFixture();
        when(realtimeKitClient.addParticipant(eq("meeting-1"), any(), eq(callerUserId.toString())))
                .thenReturn(new CloudflareRealtimeKitClient.ParticipantToken("p-caller", "t-caller"));
        when(realtimeKitClient.addParticipant(eq("meeting-1"), any(), eq(recipientUserId.toString())))
                .thenReturn(new CloudflareRealtimeKitClient.ParticipantToken("p-recipient", "t-recipient"));
        when(intercomCallRepository.save(any(IntercomCall.class))).thenAnswer(i -> {
            IntercomCall c = i.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        IntercomCallResponse response = intercomService.initiateCall(req, callerUserId);

        assertEquals(IntercomCallStatus.RINGING, response.status());
        assertEquals("t-caller", response.participantToken()); // la porteria recibe SOLO su token
        ArgumentCaptor<IntercomCall> saved = ArgumentCaptor.forClass(IntercomCall.class);
        verify(intercomCallRepository).save(saved.capture());
        assertEquals("p-caller", saved.getValue().getCallerParticipantId());
        assertEquals("p-recipient", saved.getValue().getRecipientParticipantId());
        verify(realtimeKitClient, never()).closeMeeting(any());
    }

    @Test
    void initiateCall_ParallelParticipantFailure_CleansUpAndSavesNothing() {
        org.springframework.test.util.ReflectionTestUtils.setField(intercomService, "parallelParticipantCreation", true);
        var req = initiateFixture();
        when(realtimeKitClient.addParticipant(eq("meeting-1"), any(), eq(callerUserId.toString())))
                .thenReturn(new CloudflareRealtimeKitClient.ParticipantToken("p-caller", "t-caller"));
        when(realtimeKitClient.addParticipant(eq("meeting-1"), any(), eq(recipientUserId.toString())))
                .thenThrow(new BusinessRuleConflictException("REALTIMEKIT_PARTICIPANT_FAILED", "fallo simulado"));

        BusinessRuleConflictException e = assertThrows(BusinessRuleConflictException.class,
                () -> intercomService.initiateCall(req, callerUserId));
        assertEquals("REALTIMEKIT_PARTICIPANT_FAILED", e.getCode());
        verify(intercomCallRepository, never()).save(any(IntercomCall.class));
        verify(syncEventPublisher, never()).publish(any());
        // Limpieza en Cloudflare (con reintentos, P8-A3): el participante creado y la reunion.
        verify(resourceCleaner).cleanupAsync("meeting-1", java.util.List.of("p-caller"));
    }

    @Test
    void initiateCall_SequentialParticipantFailure_AlsoCleansUp() {
        var req = initiateFixture(); // bandera desactivada (por defecto)
        when(realtimeKitClient.addParticipant(eq("meeting-1"), any(), eq(callerUserId.toString())))
                .thenReturn(new CloudflareRealtimeKitClient.ParticipantToken("p-caller", "t-caller"));
        when(realtimeKitClient.addParticipant(eq("meeting-1"), any(), eq(recipientUserId.toString())))
                .thenThrow(new BusinessRuleConflictException("REALTIMEKIT_PARTICIPANT_FAILED", "fallo simulado"));

        assertThrows(BusinessRuleConflictException.class, () -> intercomService.initiateCall(req, callerUserId));
        verify(intercomCallRepository, never()).save(any(IntercomCall.class));
        verify(resourceCleaner).cleanupAsync("meeting-1", java.util.List.of("p-caller"));
    }

    // ---- P8-A3: barrera de Cloudflare (reunion INACTIVE) y preparacion anticipada ----

    private void earlyToken(boolean on) {
        org.springframework.test.util.ReflectionTestUtils.setField(intercomService, "earlyTokenEnabled", on);
    }

    @Test
    void earlyToken_InitiateClosesGateBeforeIssuingTokens() {
        earlyToken(true);
        var req = initiateFixture();
        when(realtimeKitClient.setMeetingActive("meeting-1", false)).thenReturn(true);
        when(realtimeKitClient.addParticipant(eq("meeting-1"), any(), any()))
                .thenReturn(new CloudflareRealtimeKitClient.ParticipantToken("p-1", "t-1"));
        when(intercomCallRepository.save(any(IntercomCall.class))).thenAnswer(i -> {
            IntercomCall c = i.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        intercomService.initiateCall(req, callerUserId);

        var order = inOrder(realtimeKitClient);
        order.verify(realtimeKitClient).createMeeting(any());
        order.verify(realtimeKitClient).setMeetingActive("meeting-1", false);
        order.verify(realtimeKitClient, times(2)).addParticipant(eq("meeting-1"), any(), any());
        verify(realtimeKitClient, never()).setMeetingActive("meeting-1", true);
    }

    @Test
    void earlyToken_GateFailureAbortsWithoutTokens() {
        earlyToken(true);
        var req = initiateFixture();
        when(realtimeKitClient.setMeetingActive("meeting-1", false)).thenReturn(false);

        BusinessRuleConflictException e = assertThrows(BusinessRuleConflictException.class,
                () -> intercomService.initiateCall(req, callerUserId));
        assertEquals("REALTIMEKIT_GATE_FAILED", e.getCode());
        verify(realtimeKitClient, never()).addParticipant(any(), any(), any());
        verify(intercomCallRepository, never()).save(any(IntercomCall.class));
        verify(resourceCleaner).cleanupAsync("meeting-1", java.util.List.of());
    }

    @Test
    void earlyToken_AcceptOpensGateOnlyForAuthorizedRecipient() {
        earlyToken(true);
        when(intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)).thenReturn(Optional.of(call));
        when(realtimeKitClient.setMeetingActive("cf-meeting-123", true)).thenReturn(true);
        when(intercomCallRepository.save(any(IntercomCall.class))).thenAnswer(i -> i.getArgument(0));
        when(realtimeKitClient.addParticipant(any(), any(), any()))
                .thenReturn(new CloudflareRealtimeKitClient.ParticipantToken("recip-1", "token-123"));

        // Otro usuario: ni se abre la barrera ni cambia la llamada.
        assertThrows(TenantAccessDeniedException.class, () -> intercomService.acceptCall(callId, callerUserId));
        verify(realtimeKitClient, never()).setMeetingActive(any(), eq(true));

        intercomService.acceptCall(callId, recipientUserId);
        verify(realtimeKitClient).setMeetingActive("cf-meeting-123", true);
        assertEquals(IntercomCallStatus.ACCEPTED, call.getStatus());
    }

    @Test
    void earlyToken_ActivationFailureKeepsRinging() {
        earlyToken(true);
        when(intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)).thenReturn(Optional.of(call));
        when(realtimeKitClient.setMeetingActive("cf-meeting-123", true)).thenReturn(false);

        BusinessRuleConflictException e = assertThrows(BusinessRuleConflictException.class,
                () -> intercomService.acceptCall(callId, recipientUserId));
        assertEquals("REALTIMEKIT_ACTIVATION_FAILED", e.getCode());
        assertEquals(IntercomCallStatus.RINGING, call.getStatus());
        assertNull(call.getAcceptedAt());
        verify(intercomCallRepository, never()).save(any(IntercomCall.class));
        verify(syncEventPublisher, never()).publish(any());
    }

    @Test
    void earlyToken_FlagOffUsesNoGate() {
        earlyToken(false);
        when(intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)).thenReturn(Optional.of(call));
        when(intercomCallRepository.save(any(IntercomCall.class))).thenAnswer(i -> i.getArgument(0));
        when(realtimeKitClient.addParticipant(any(), any(), any()))
                .thenReturn(new CloudflareRealtimeKitClient.ParticipantToken("recip-1", "token-123"));
        intercomService.acceptCall(callId, recipientUserId);
        verify(realtimeKitClient, never()).setMeetingActive(any(), anyBoolean());
        assertThrows(com.havita.common.exception.ResourceNotFoundException.class,
                () -> intercomService.prepareCall(callId, recipientUserId));
    }

    @Test
    void earlyToken_PrepareOnlyForRingingAuthorizedRecipient() {
        earlyToken(true);
        when(intercomCallRepository.findByIdAndTenantId(callId, tenantId)).thenReturn(Optional.of(call));
        // Sin token en memoria (la llamada no se inicio en este proceso): se niega sin filtrar nada.
        BusinessRuleConflictException e = assertThrows(BusinessRuleConflictException.class,
                () -> intercomService.prepareCall(callId, recipientUserId));
        assertEquals("CALL_PREPARE_UNAVAILABLE", e.getCode());
        // Otro usuario o residente dado de baja: 403.
        assertThrows(TenantAccessDeniedException.class, () -> intercomService.prepareCall(callId, callerUserId));
        recipientResident.setStatus(com.havita.modules.resident.domain.ResidentStatus.INACTIVE);
        assertThrows(TenantAccessDeniedException.class, () -> intercomService.prepareCall(callId, recipientUserId));
        recipientResident.setStatus(com.havita.modules.resident.domain.ResidentStatus.ACTIVE);
        // Ya no timbra: 409.
        call.setStatus(IntercomCallStatus.ACCEPTED);
        BusinessRuleConflictException e2 = assertThrows(BusinessRuleConflictException.class,
                () -> intercomService.prepareCall(callId, recipientUserId));
        assertEquals("CALL_NOT_RINGING", e2.getCode());
    }

    @Test
    void cleanup_GoesThroughRetryingCleanerWithBothParticipants() {
        when(intercomCallRepository.findByIdAndTenantIdWithLock(callId, tenantId)).thenReturn(Optional.of(call));
        when(intercomCallRepository.save(any(IntercomCall.class))).thenAnswer(i -> i.getArgument(0));
        intercomService.rejectCall(callId, recipientUserId);
        verify(resourceCleaner).cleanupAsync("cf-meeting-123", java.util.List.of("caller-part-1", "recip-part-2"));
        verifyNoInteractions(realtimeKitClient);
    }
}
