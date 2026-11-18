package com.havita.modules.intercom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.modules.intercom.application.CloudflareRealtimeKitClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P8-A8 — sin CLOUDFLARE_API_TOKEN (ya no hay valor por defecto en application.yml) no se llama a Cloudflare. */
class CloudflareRealtimeKitClientTest {

    // Puerto 9 (discard) en loopback: si el cliente intentara conectarse fallaria con otro codigo de error.
    private static CloudflareRealtimeKitClient client(String token) {
        return new CloudflareRealtimeKitClient("0123456789abcdef0123456789abcdef", token,
                "00000000-0000-4000-8000-000000000000", "http://127.0.0.1:9", "group_call_participant", new ObjectMapper());
    }

    @Test
    @DisplayName("token vacio: crear sala y participante fallan con REALTIMEKIT_NOT_CONFIGURED antes de cualquier peticion")
    void notConfigured() {
        for (String token : new String[]{"", "  ", null}) {
            CloudflareRealtimeKitClient c = client(token);
            assertThatThrownBy(() -> c.createMeeting("sintetica"))
                    .isInstanceOf(BusinessRuleConflictException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Bearer"))
                    .extracting("code").isEqualTo("REALTIMEKIT_NOT_CONFIGURED");
            assertThatThrownBy(() -> c.addParticipant("m", "n", "p"))
                    .extracting("code").isEqualTo("REALTIMEKIT_NOT_CONFIGURED");
            // La limpieza es best-effort: sin configuracion no hace nada ni lanza.
            assertThatCode(() -> {
                c.kickAllParticipants("m");
                c.deleteParticipant("m", "p");
                c.closeMeeting("m");
            }).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("con token, un fallo de red se informa como error de comunicacion (sin el token en el mensaje)")
    void configuredNetworkFailure() {
        CloudflareRealtimeKitClient c = client("synthetic-token-not-real");
        assertThatThrownBy(() -> c.createMeeting("sintetica"))
                .isInstanceOf(BusinessRuleConflictException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("synthetic-token-not-real"))
                .extracting("code").isEqualTo("REALTIMEKIT_COMMUNICATION_ERROR");
    }

    // ---- P8-A3: vigencia del token, semántica de limpieza y listado (servidor HTTP local, sintético) ----

    private static com.sun.net.httpserver.HttpServer server(java.util.function.Function<String, int[]> statusFor,
                                                            java.util.List<String> bodies, String participantsJson) throws Exception {
        var srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            bodies.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " " + body);
            int code = statusFor.apply(ex.getRequestMethod() + " " + ex.getRequestURI().getPath())[0];
            String resp = ex.getRequestURI().getPath().endsWith("/participants") && "POST".equals(ex.getRequestMethod())
                    ? "{\"success\":true,\"data\":{\"id\":\"p-1\",\"token\":\"t-sintetico\"}}"
                    : ("GET".equals(ex.getRequestMethod()) ? participantsJson : "{\"success\":true}");
            byte[] b = resp.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length);
            try (var os = ex.getResponseBody()) { os.write(b); }
        });
        srv.start();
        return srv;
    }

    private static CloudflareRealtimeKitClient clientAt(com.sun.net.httpserver.HttpServer srv) {
        return new CloudflareRealtimeKitClient("0123456789abcdef0123456789abcdef", "synthetic-token-not-real",
                "00000000-0000-4000-8000-000000000000", "http://127.0.0.1:" + srv.getAddress().getPort(),
                "AUDIO_ROOM", new ObjectMapper());
    }

    @Test
    @DisplayName("el alta de participante limita la vigencia del token (token.expires_on ≈ ahora + 120 min)")
    void tokenExpiry() throws Exception {
        var bodies = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var srv = server(k -> new int[]{200}, bodies, "{}");
        try {
            var c = clientAt(srv);
            org.springframework.test.util.ReflectionTestUtils.setField(c, "participantTokenTtlMinutes", 120L);
            c.addParticipant("m", "Sintetico", "u-1");
            String body = bodies.get(0);
            assertThat(body).contains("\"preset_name\":\"AUDIO_ROOM\"").contains("\"expires_on\"");
            String iso = body.replaceAll(".*\"expires_on\":\"([^\"]+)\".*", "$1");
            long minutes = java.time.Duration.between(java.time.OffsetDateTime.now(), java.time.OffsetDateTime.parse(iso)).toMinutes();
            assertThat(minutes).isBetween(118L, 120L);
            // Con 0 se deja el valor de Cloudflare (sin campo token).
            bodies.clear();
            org.springframework.test.util.ReflectionTestUtils.setField(c, "participantTokenTtlMinutes", 0L);
            c.addParticipant("m", "Sintetico", "u-1");
            assertThat(bodies.get(0)).doesNotContain("expires_on");
        } finally {
            srv.stop(0);
        }
    }

    @Test
    @DisplayName("limpieza idempotente: 2xx/404 = hecho; 5xx/429 = reintentar; estado de la reunión solo con 2xx")
    void cleanupSemantics() throws Exception {
        var bodies = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var srv = server(k -> k.contains("/participants/gone") ? new int[]{404}
                : k.contains("/participants/busy") ? new int[]{503}
                : k.contains("/participants/slow") ? new int[]{429}
                : k.startsWith("PATCH") && k.contains("/fail") ? new int[]{500} : new int[]{200}, bodies, "{}");
        try {
            var c = clientAt(srv);
            assertThat(c.deleteParticipant("m", "ok")).isTrue();
            assertThat(c.deleteParticipant("m", "gone")).isTrue();
            assertThat(c.deleteParticipant("m", "busy")).isFalse();
            assertThat(c.deleteParticipant("m", "slow")).isFalse();
            assertThat(c.setMeetingActive("m", false)).isTrue();
            assertThat(c.setMeetingActive("fail", true)).isFalse();
            assertThat(bodies).anyMatch(b -> b.startsWith("PATCH") && b.contains("\"INACTIVE\""));
        } finally {
            srv.stop(0);
        }
    }

    @Test
    @DisplayName("listado de participantes para la conciliación: ids, lista vacía o null si falla")
    void listParticipants() throws Exception {
        var srv = server(k -> new int[]{200}, new java.util.ArrayList<>(), "{\"success\":true,\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}]}");
        var down = server(k -> new int[]{502}, new java.util.ArrayList<>(), "{}");
        var unknown = server(k -> new int[]{400}, new java.util.ArrayList<>(), "{\"success\":false}");
        try {
            assertThat(clientAt(srv).listParticipantIds("m")).containsExactly("a", "b");
            assertThat(clientAt(down).listParticipantIds("m")).isNull();
            // Reunion inexistente o id invalido (observado con Cloudflare real): nada que limpiar, sin reintentos.
            assertThat(clientAt(unknown).listParticipantIds("m")).isEmpty();
        } finally {
            srv.stop(0);
            down.stop(0);
            unknown.stop(0);
        }
    }
}
