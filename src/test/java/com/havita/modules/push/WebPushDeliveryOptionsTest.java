package com.havita.modules.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.push.application.WebPushService;
import com.havita.modules.push.domain.PushSubscription;
import com.havita.modules.push.infrastructure.PushSubscriptionRepository;
import com.sun.net.httpserver.HttpServer;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Urgency;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P8-B — {@link WebPushService#send} contra un servidor HTTP LOCAL que hace de servicio de push. Las claves (de servidor
 * y de suscriptor) son EFIMERAS, generadas en memoria solo para esta prueba: no son claves VAPID de ningun entorno.
 * Comprueba las cabeceras TTL, Urgency y Topic, el cifrado (aes128gcm + VAPID) y la gestion de 2xx/404/410/429/5xx.
 */
class WebPushDeliveryOptionsTest {

    record Received(String ttl, String urgency, String topic, String encoding, String authorization, int bodyLength) {}

    static KeyPair serverKeys;
    HttpServer server;
    final List<Received> received = new CopyOnWriteArrayList<>();
    final AtomicInteger status = new AtomicInteger(201);
    PushSubscriptionRepository repo;
    WebPushService service;

    @BeforeAll
    static void keys() throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) Security.addProvider(new BouncyCastleProvider());
        serverKeys = ecKeyPair();
    }

    private static KeyPair ecKeyPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
        g.initialize(ECNamedCurveTable.getParameterSpec("prime256v1"));
        return g.generateKeyPair();
    }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            var h = ex.getRequestHeaders();
            received.add(new Received(h.getFirst("TTL"), h.getFirst("Urgency"), h.getFirst("Topic"),
                    h.getFirst("Content-Encoding"), h.getFirst("Authorization"), body.length));
            ex.sendResponseHeaders(status.get(), -1);
            ex.close();
        });
        server.start();
        repo = mock(PushSubscriptionRepository.class);
        service = new WebPushService(repo, new ObjectMapper());
        ReflectionTestUtils.setField(service, "pushService", new PushService(serverKeys, "mailto:pruebas@example.test"));
        ReflectionTestUtils.setField(service, "configured", true);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private PushSubscription subscription() throws Exception {
        KeyPair user = ecKeyPair();
        byte[] point = ((ECPublicKey) user.getPublic()).getQ().getEncoded(false);
        byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);
        PushSubscription s = PushSubscription.builder()
                .userId(UUID.randomUUID())
                .endpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/push/synthetic")
                .p256dh(Base64.getUrlEncoder().withoutPadding().encodeToString(point))
                .auth(Base64.getUrlEncoder().withoutPadding().encodeToString(auth))
                .active(true)
                .build();
        s.setTenantId(UUID.randomUUID());
        return s;
    }

    @Test
    @DisplayName("Aviso de citofonia: TTL, Urgency high y Topic en las cabeceras; cuerpo cifrado aes128gcm con VAPID")
    void sendsHeaders() throws Exception {
        String plain = "{\"type\":\"INTERCOM_CALL\",\"body\":\"Llamada de portería\"}";
        var r = service.send(subscription(), Map.of("type", "INTERCOM_CALL", "body", "Llamada de portería"),
                new WebPushService.PushOptions(37, Urgency.HIGH, "abcdefghijklmnopqrstuvwxyz012345"));
        assertThat(r.outcome()).isEqualTo(WebPushService.Outcome.SENT);
        Received got = received.get(0);
        assertThat(got.ttl()).isEqualTo("37");
        assertThat(got.urgency()).isEqualTo("high");
        assertThat(got.topic()).isEqualTo("abcdefghijklmnopqrstuvwxyz012345");
        assertThat(got.encoding()).isEqualTo("aes128gcm");
        assertThat(got.authorization()).startsWith("vapid t=");
        // Cifrado RFC 8291: cabecera de 86 bytes + texto cifrado + 16 de etiqueta (+ relleno), nunca el JSON en claro.
        assertThat(got.bodyLength()).isGreaterThan(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 86);
    }

    @Test
    @DisplayName("Notificaciones generales: sin Urgency ni Topic y TTL por defecto (como antes), pero cifradas con aes128gcm (iOS)")
    void generalPushUnchanged() throws Exception {
        assertThat(service.sendPush(subscription(), Map.of("title", "x"))).isTrue();
        Received got = received.get(0);
        assertThat(got.urgency()).isNull();
        assertThat(got.topic()).isNull();
        assertThat(got.ttl()).isEqualTo("2419200");
        assertThat(got.encoding()).isEqualTo("aes128gcm");
    }

    @Test
    @DisplayName("404/410 desactivan la suscripcion; 429 y 5xx son reintentables y no la desactivan; 4xx no lo es")
    void statusHandling() throws Exception {
        PushSubscription s = subscription();
        status.set(410);
        assertThat(service.send(s, Map.of(), null).outcome()).isEqualTo(WebPushService.Outcome.GONE);
        assertThat(s.getActive()).isFalse();
        verify(repo).save(s);

        PushSubscription gone404 = subscription();
        status.set(404);
        assertThat(service.send(gone404, Map.of(), null).outcome()).isEqualTo(WebPushService.Outcome.GONE);
        assertThat(gone404.getActive()).isFalse();

        PushSubscription t = subscription();
        status.set(429);
        var limited = service.send(t, Map.of(), null);
        assertThat(limited.outcome()).isEqualTo(WebPushService.Outcome.RATE_LIMITED);
        assertThat(limited.retryable()).isTrue();
        status.set(503);
        var failed = service.send(t, Map.of(), null);
        assertThat(failed.outcome()).isEqualTo(WebPushService.Outcome.FAILED);
        assertThat(failed.retryable()).isTrue();
        status.set(400);
        assertThat(service.send(t, Map.of(), null).retryable()).isFalse();
        assertThat(t.getActive()).isTrue();
    }

    @Test
    @DisplayName("Sin configurar (sin claves VAPID): no envia nada")
    void notConfigured() throws Exception {
        ReflectionTestUtils.setField(service, "configured", false);
        assertThat(service.send(subscription(), Map.of(), null).outcome()).isEqualTo(WebPushService.Outcome.NOT_CONFIGURED);
        assertThat(received).isEmpty();
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("Topic invalido (RFC 8030: ≤32 caracteres base64url) o TTL negativo: rechazados")
    void invalidOptions() {
        assertThatThrownBy(() -> new WebPushService.PushOptions(10, Urgency.HIGH, "con espacios")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebPushService.PushOptions(10, Urgency.HIGH, "x".repeat(33))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebPushService.PushOptions(-1, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
