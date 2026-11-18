package com.havita.modules.intercom.application;

import com.havita.modules.push.application.WebPushService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** P8-B — registro en memoria de entregas push: deduplicacion, recibos de un solo uso y caducidad. */
class IntercomPushDeliveryRegistryTest {

    private final UUID call = UUID.randomUUID();
    private final UUID tenant = UUID.randomUUID();
    private final UUID sub = UUID.randomUUID();

    @Test
    @DisplayName("Una sola entrega por (llamada, suscripcion, tipo); el nonce es de 128 bits y no se guarda en claro")
    void dedupAndNonce() {
        var r = new IntercomPushDeliveryRegistry();
        var d = r.reserve(call, tenant, sub, IntercomPushEvent.Kind.RINGING).orElseThrow();
        assertThat(d.nonce()).matches("[A-Za-z0-9_-]{22}");
        assertThat(r.reserve(call, tenant, sub, IntercomPushEvent.Kind.RINGING)).isEmpty();
        assertThat(r.reserve(call, tenant, sub, IntercomPushEvent.Kind.CANCELLED)).isPresent();
        assertThat(IntercomPushDeliveryRegistry.hash(d.nonce())).isNotEqualTo(d.nonce());
    }

    @Test
    @DisplayName("Solo las suscripciones con el timbre ENVIADO (2xx) reciben el aviso de fin")
    void endTargetsOnlySentRinging() {
        var r = new IntercomPushDeliveryRegistry();
        UUID failedSub = UUID.randomUUID();
        r.markSent(r.reserve(call, tenant, sub, IntercomPushEvent.Kind.RINGING).orElseThrow(),
                new WebPushService.PushResult(WebPushService.Outcome.SENT, 201));
        r.markSent(r.reserve(call, tenant, failedSub, IntercomPushEvent.Kind.RINGING).orElseThrow(),
                new WebPushService.PushResult(WebPushService.Outcome.GONE, 410));
        assertThat(r.subscriptionsNotifiedOfRinging(call)).containsExactly(sub);
        assertThat(r.subscriptionsNotifiedOfRinging(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("Recibos: primera marca por etapa; nonce desconocido, mal formado, sin etapa o caducado no hacen nada")
    void receipts() {
        var r = new IntercomPushDeliveryRegistry();
        Instant t0 = Instant.parse("2026-11-13T10:00:00Z");
        r.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        var d = r.reserve(call, tenant, sub, IntercomPushEvent.Kind.RINGING).orElseThrow();
        r.markSent(d, new WebPushService.PushResult(WebPushService.Outcome.SENT, 201));

        assertThat(r.recordReceipt(d.nonce(), IntercomPushDeliveryRegistry.Stage.RECEIVED)).isTrue();
        assertThat(r.recordReceipt(d.nonce(), IntercomPushDeliveryRegistry.Stage.RECEIVED)).isFalse();
        assertThat(r.recordReceipt(d.nonce(), IntercomPushDeliveryRegistry.Stage.DISPLAYED)).isTrue();
        assertThat(r.recordReceipt("AAAAAAAAAAAAAAAAAAAAAA", IntercomPushDeliveryRegistry.Stage.CLICKED)).isFalse();
        assertThat(r.recordReceipt("corto", IntercomPushDeliveryRegistry.Stage.CLICKED)).isFalse();
        assertThat(r.recordReceipt(d.nonce(), null)).isFalse();

        r.setClock(Clock.fixed(t0.plus(IntercomPushDeliveryRegistry.RETENTION).plus(Duration.ofSeconds(1)), ZoneOffset.UTC));
        assertThat(r.recordReceipt(d.nonce(), IntercomPushDeliveryRegistry.Stage.CLICKED)).isFalse();
        r.purge();
        assertThat(r.deliveriesFor(call)).isEmpty();
    }

    @Test
    @DisplayName("Topic por llamada: 32 caracteres base64url, estable y distinto entre llamadas")
    void topic() {
        String t = IntercomPushNotifier.topic(call);
        assertThat(t).matches("[A-Za-z0-9_-]{32}");
        assertThat(IntercomPushNotifier.topic(call)).isEqualTo(t);
        assertThat(IntercomPushNotifier.topic(UUID.randomUUID())).isNotEqualTo(t);
    }

    @Test
    @DisplayName("Carga: texto fijo sin datos personales; timbre con enlace y caducidad; fin sin «Toca para contestar»")
    void payload() {
        var s = new IntercomPushNotifier.CallSnapshot(call, tenant, null, UUID.randomUUID(), true,
                java.time.OffsetDateTime.parse("2026-11-13T10:00:00Z"));
        var ringing = IntercomPushNotifier.payload(s, IntercomPushEvent.Kind.RINGING, "n");
        assertThat(ringing).containsEntry("title", "Havita").containsEntry("body", "Llamada de portería")
                .containsEntry("detail", "Toca para abrir y contestar")
                .containsEntry("url", "/resident?intercomCall=" + call)
                .containsEntry("expiresAt", Instant.parse("2026-11-13T10:00:45Z").toEpochMilli());
        assertThat(ringing.keySet()).containsExactlyInAnyOrder("v", "type", "kind", "callId", "title", "body", "detail",
                "url", "expiresAt", "tag", "receipt");
        var missed = IntercomPushNotifier.payload(s, IntercomPushEvent.Kind.MISSED, "n");
        assertThat(missed).containsEntry("body", "Llamada de portería perdida").containsEntry("url", "/resident")
                .doesNotContainKey("detail").doesNotContainKey("expiresAt");
    }
}
