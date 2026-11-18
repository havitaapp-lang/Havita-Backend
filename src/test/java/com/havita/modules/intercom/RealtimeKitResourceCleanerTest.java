package com.havita.modules.intercom;

import com.havita.modules.intercom.application.CloudflareRealtimeKitClient;
import com.havita.modules.intercom.application.RealtimeKitResourceCleaner;
import com.havita.modules.intercom.domain.IntercomCall;
import com.havita.modules.intercom.domain.IntercomCallStatus;
import com.havita.modules.intercom.infrastructure.IntercomCallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** P8-A3 — limpieza de RealtimeKit con reintentos controlados y conciliación (cliente simulado). */
class RealtimeKitResourceCleanerTest {

    private CloudflareRealtimeKitClient client;
    private IntercomCallRepository calls;
    private RealtimeKitResourceCleaner cleaner;

    @BeforeEach
    void setUp() {
        client = mock(CloudflareRealtimeKitClient.class);
        calls = mock(IntercomCallRepository.class);
        cleaner = new RealtimeKitResourceCleaner(client, calls);
        ReflectionTestUtils.setField(cleaner, "retryDelaysMs", new long[]{0, 50, 100});
        when(client.isConfigured()).thenReturn(true);
        when(client.kickAllParticipants(any())).thenReturn(true);
        when(client.closeMeeting(any())).thenReturn(true);
        when(calls.findEndedSince(anyList(), any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("éxito al primer intento: kick-all, DELETE de cada participante y reunión INACTIVE")
    void firstAttempt() {
        when(client.deleteParticipant(eq("m1"), any())).thenReturn(true);
        cleaner.cleanupAsync("m1", List.of("p1", "p2"));
        verify(client, timeout(2000)).closeMeeting("m1");
        verify(client).deleteParticipant("m1", "p1");
        verify(client).deleteParticipant("m1", "p2");
        assertThat(cleaner.pendingMeetings()).isEmpty();
    }

    @Test
    @DisplayName("fallo transitorio: se reintenta y se completa sin quedar pendiente")
    void retriesTransientFailure() {
        when(client.deleteParticipant("m2", "p1")).thenReturn(false).thenReturn(true);
        cleaner.cleanupAsync("m2", List.of("p1"));
        verify(client, timeout(3000).times(2)).deleteParticipant("m2", "p1");
        verify(client, timeout(3000).times(2)).closeMeeting("m2");
        assertThat(cleaner.pendingMeetings()).isEmpty();
    }

    @Test
    @DisplayName("fallo persistente: 3 intentos y queda pendiente; la conciliación lo completa")
    void pendingThenReconciled() throws Exception {
        when(client.deleteParticipant("m3", "p1")).thenReturn(false);
        cleaner.cleanupAsync("m3", List.of("p1"));
        verify(client, timeout(3000).times(3)).deleteParticipant("m3", "p1");
        long deadline = System.currentTimeMillis() + 3000;
        while (cleaner.pendingMeetings().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertThat(cleaner.pendingMeetings()).containsExactly("m3");

        // Conciliación: Cloudflare informa de que el participante sigue registrado; ahora el DELETE funciona.
        when(client.listParticipantIds("m3")).thenReturn(List.of("p1"));
        when(client.deleteParticipant("m3", "p1")).thenReturn(true);
        cleaner.reconcile();
        verify(client, times(4)).deleteParticipant("m3", "p1");
        assertThat(cleaner.pendingMeetings()).isEmpty();
    }

    @Test
    @DisplayName("conciliación de llamadas terminadas: borra participantes huérfanos y no repite reuniones verificadas")
    void reconcilesEndedCallsOnce() {
        IntercomCall ended = IntercomCall.builder().realtimeMeetingId("m4").status(IntercomCallStatus.ENDED).build();
        IntercomCall clean = IntercomCall.builder().realtimeMeetingId("m5").status(IntercomCallStatus.REJECTED).build();
        when(calls.findEndedSince(anyList(), any())).thenReturn(List.of(ended, clean));
        when(client.listParticipantIds("m4")).thenReturn(List.of("orphan-1"));
        when(client.listParticipantIds("m5")).thenReturn(List.of());
        when(client.deleteParticipant("m4", "orphan-1")).thenReturn(true);

        cleaner.reconcile();
        verify(client).deleteParticipant("m4", "orphan-1");
        verify(client).closeMeeting("m4");
        verify(client, never()).deleteParticipant(eq("m5"), any());

        cleaner.reconcile(); // ya verificadas: no se vuelven a consultar
        verify(client, times(1)).listParticipantIds("m4");
        verify(client, times(1)).listParticipantIds("m5");
    }

    @Test
    @DisplayName("si Cloudflare no responde a la consulta, la reunión se reintentará en la próxima pasada")
    void unqueryableIsRetriedLater() {
        IntercomCall ended = IntercomCall.builder().realtimeMeetingId("m6").status(IntercomCallStatus.ENDED).build();
        when(calls.findEndedSince(anyList(), any())).thenReturn(List.of(ended));
        when(client.listParticipantIds("m6")).thenReturn(null).thenReturn(List.of());
        cleaner.reconcile();
        cleaner.reconcile();
        verify(client, times(2)).listParticipantIds("m6");
    }

    @Test
    @DisplayName("sin RealtimeKit configurado o desactivada, la conciliación no hace nada")
    void disabledOrUnconfigured() {
        when(client.isConfigured()).thenReturn(false);
        cleaner.reconcile();
        verifyNoInteractions(calls);
        when(client.isConfigured()).thenReturn(true);
        ReflectionTestUtils.setField(cleaner, "reconciliationEnabled", false);
        cleaner.reconcile();
        verifyNoInteractions(calls);
    }
}
