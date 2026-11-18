package com.havita.modules.intercom.application;

import com.havita.modules.intercom.domain.IntercomCall;
import com.havita.modules.intercom.domain.IntercomCallStatus;
import com.havita.modules.intercom.infrastructure.IntercomCallRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * P8-A3 — limpieza fiable de los recursos de RealtimeKit de una llamada terminada.
 *
 * <p>Por cada llamada terminada se hace, en este orden:
 * <ol>
 *   <li>{@code kick-all} de la sesion activa;</li>
 *   <li>{@code DELETE} de cada participante, que revoca todos sus tokens;</li>
 *   <li>reunion en {@code INACTIVE}.</li>
 * </ol>
 * Antes era un unico intento asincrono sin reintentos. Ahora:
 * <ul>
 *   <li><b>Reintentos controlados:</b> un intento inmediato y dos reintentos (a los 2 s y 10 s) solo ante fallos
 *       transitorios (red, 5xx, 429). Cada paso es idempotente: un 404 cuenta como hecho.</li>
 *   <li><b>Pendientes:</b> lo que siga fallando queda en memoria para la conciliacion.</li>
 *   <li><b>Conciliacion periodica</b> ({@link #reconcile()}): para las llamadas terminadas en la ventana configurada
 *       (24 h por defecto) y las pendientes, consulta en Cloudflare los participantes que siguen registrados, borra los
 *       que queden y cierra la reunion. Cada reunion se comprueba una vez; tras un reinicio, se vuelve a comprobar la
 *       ventana. No hay cambios de esquema.</li>
 * </ul>
 */
@Slf4j
@Component
public class RealtimeKitResourceCleaner {

    /** Inmediato y dos reintentos (2 s, 10 s). Campo de instancia para poder acortarlo en pruebas. */
    private long[] retryDelaysMs = {0, 2_000, 10_000};
    private static final int MAX_VERIFIED = 20_000;

    private final CloudflareRealtimeKitClient client;
    private final IntercomCallRepository calls;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "intercom-rtk-cleanup");
        t.setDaemon(true);
        return t;
    });

    /** Reuniones con limpieza fallida tras los reintentos: meetingId -> participantes conocidos. */
    private final Map<String, List<String>> pending = new ConcurrentHashMap<>();
    /** Reuniones ya verificadas sin participantes (acotado). */
    private final Set<String> verified = Collections.newSetFromMap(Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_VERIFIED;
                }
            }));

    @Value("${havita.intercom.reconciliation.window-hours:24}")
    private long reconciliationWindowHours = 24;

    @Value("${havita.intercom.reconciliation.enabled:true}")
    private boolean reconciliationEnabled = true;

    public RealtimeKitResourceCleaner(CloudflareRealtimeKitClient client, IntercomCallRepository calls) {
        this.client = client;
        this.calls = calls;
    }

    /** Programa la limpieza (asincrona) de la reunion y de sus participantes. */
    public void cleanupAsync(String meetingId, List<String> participantIds) {
        if (meetingId == null || meetingId.isBlank()) return;
        List<String> ids = participantIds == null ? List.of()
                : participantIds.stream().filter(Objects::nonNull).distinct().toList();
        schedule(meetingId, ids, 0);
    }

    private void schedule(String meetingId, List<String> ids, int attempt) {
        scheduler.schedule(() -> runAttempt(meetingId, ids, attempt), retryDelaysMs[attempt], TimeUnit.MILLISECONDS);
    }

    private void runAttempt(String meetingId, List<String> ids, int attempt) {
        boolean ok;
        try {
            ok = cleanupNow(meetingId, ids);
        } catch (RuntimeException e) {
            log.warn("Cleanup attempt {} for meeting {} threw {}", attempt + 1, meetingId, e.getClass().getSimpleName());
            ok = false;
        }
        if (ok) {
            pending.remove(meetingId);
            return;
        }
        if (attempt + 1 < retryDelaysMs.length) {
            schedule(meetingId, ids, attempt + 1);
        } else {
            pending.put(meetingId, ids);
            log.warn("Cleanup of meeting {} still incomplete after {} attempts; left for reconciliation",
                    meetingId, retryDelaysMs.length);
        }
    }

    /** Un intento completo. Devuelve true si no queda nada por reintentar. */
    boolean cleanupNow(String meetingId, List<String> ids) {
        boolean ok = client.kickAllParticipants(meetingId);
        for (String id : ids) {
            ok &= client.deleteParticipant(meetingId, id);
        }
        ok &= client.closeMeeting(meetingId);
        return ok;
    }

    /** Reuniones pendientes (observabilidad y pruebas). */
    public Set<String> pendingMeetings() {
        return Set.copyOf(pending.keySet());
    }

    /**
     * Conciliacion: borra en Cloudflare los participantes que sigan registrados en reuniones de llamadas terminadas.
     * Solo lee de la base de datos; no modifica llamadas.
     */
    @Scheduled(fixedDelayString = "${havita.intercom.reconciliation.interval-ms:300000}",
            initialDelayString = "${havita.intercom.reconciliation.initial-delay-ms:120000}")
    @Transactional(readOnly = true)
    public void reconcile() {
        if (!reconciliationEnabled || !client.isConfigured()) return;
        Set<String> meetings = new java.util.LinkedHashSet<>(pending.keySet());
        OffsetDateTime since = OffsetDateTime.now().minusHours(reconciliationWindowHours);
        for (IntercomCall call : calls.findEndedSince(List.of(IntercomCallStatus.ENDED, IntercomCallStatus.REJECTED,
                IntercomCallStatus.CANCELLED, IntercomCallStatus.MISSED, IntercomCallStatus.FAILED), since)) {
            String meetingId = call.getRealtimeMeetingId();
            if (meetingId != null && !meetingId.isBlank() && !verified.contains(meetingId)) {
                meetings.add(meetingId);
            }
        }
        int cleaned = 0;
        for (String meetingId : meetings) {
            List<String> remaining = client.listParticipantIds(meetingId);
            if (remaining == null) continue; // no se pudo consultar: se reintenta en la proxima pasada
            boolean ok = true;
            if (!remaining.isEmpty()) {
                ok = cleanupNow(meetingId, new ArrayList<>(remaining));
                cleaned += remaining.size();
                log.warn("Reconciliation removed {} leftover participant(s) from meeting {}", remaining.size(), meetingId);
            }
            if (ok) {
                pending.remove(meetingId);
                verified.add(meetingId);
            }
        }
        if (!meetings.isEmpty()) {
            log.info("Intercom reconciliation checked {} meeting(s); leftover participants removed: {}", meetings.size(), cleaned);
        }
    }
}
