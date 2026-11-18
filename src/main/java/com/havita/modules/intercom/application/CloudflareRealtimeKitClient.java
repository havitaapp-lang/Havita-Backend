package com.havita.modules.intercom.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.common.exception.BusinessRuleConflictException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class CloudflareRealtimeKitClient {

    private final String accountId;
    private final String apiToken;
    private final String appId;
    private final String baseUrl;
    private final String defaultPresetName;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public record ParticipantToken(String participantId, String token) {}

    public CloudflareRealtimeKitClient(
            @Value("${app.cloudflare.account-id}") String accountId,
            @Value("${app.cloudflare.api-token}") String apiToken,
            @Value("${app.realtimekit.app-id}") String appId,
            @Value("${app.realtimekit.base-url:https://api.cloudflare.com/client/v4}") String baseUrl,
            @Value("${app.realtimekit.preset-name:group_call_participant}") String defaultPresetName,
            ObjectMapper objectMapper) {
        this.accountId = accountId;
        this.apiToken = apiToken;
        this.appId = appId;
        this.baseUrl = baseUrl;
        this.defaultPresetName = defaultPresetName;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * P8-A8: sin token configurado (CLOUDFLARE_API_TOKEN) no se llama a Cloudflare con una cabecera vacia; se informa
     * un error de configuracion sin revelar valores.
     */
    private void requireConfigured() {
        if (apiToken == null || apiToken.isBlank() || accountId == null || accountId.isBlank() || appId == null || appId.isBlank()) {
            log.error("RealtimeKit is not configured: CLOUDFLARE_API_TOKEN, CLOUDFLARE_ACCOUNT_ID and REALTIMEKIT_APP_ID are required");
            throw new BusinessRuleConflictException("REALTIMEKIT_NOT_CONFIGURED",
                    "La citofonía no está configurada en este entorno.");
        }
    }


    public String createMeeting(String title) {
        requireConfigured();
        long start = System.nanoTime();
        String endpoint = String.format("%s/accounts/%s/realtime/kit/%s/meetings",
                baseUrl, accountId, appId);

        Map<String, Object> body = Map.of(
                "title", title != null ? title : "Havita Intercom Call"
        );

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long durationMs = (System.nanoTime() - start) / 1_000_000;

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("Failed to create RealtimeKit meeting. Status: {}, Response: {}",
                        response.statusCode(), response.body());
                throw new BusinessRuleConflictException("REALTIMEKIT_MEETING_FAILED",
                        "No se pudo crear la sala de audio en Cloudflare RealtimeKit.");
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (!root.path("success").asBoolean(false)) {
                log.error("Cloudflare RealtimeKit reported unsuccessful meeting creation: {}", response.body());
                throw new BusinessRuleConflictException("REALTIMEKIT_MEETING_FAILED",
                        "Cloudflare RealtimeKit rechazó la creación de la sala.");
            }

            String meetingId = root.path("data").path("id").asText();
            if (meetingId == null || meetingId.isBlank()) {
                throw new BusinessRuleConflictException("REALTIMEKIT_INVALID_RESPONSE",
                        "Respuesta inválida de Cloudflare RealtimeKit al crear la reunión.");
            }

            log.info("Successfully created RealtimeKit meeting with ID: {} in {}ms", meetingId, durationMs);
            return meetingId;
        } catch (BusinessRuleConflictException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error communicating with Cloudflare RealtimeKit API", e);
            throw new BusinessRuleConflictException("REALTIMEKIT_COMMUNICATION_ERROR",
                    "Error de conexión con el servicio de audio en tiempo real.");
        }
    }

    /**
     * P8-A3: vigencia de los tokens de participante. Por defecto RealtimeKit emite tokens de 100 dias; Havita los
     * limita con {@code token.expires_on} (campo documentado de "Add a participant"). Con 0 o menos se usa el valor de
     * Cloudflare. La revocacion normal sigue siendo borrar el participante al terminar.
     */
    @Value("${havita.intercom.participant-token-ttl-minutes:120}")
    private long participantTokenTtlMinutes = 120;

    public boolean isConfigured() {
        return apiToken != null && !apiToken.isBlank() && accountId != null && !accountId.isBlank()
                && appId != null && !appId.isBlank();
    }

    private String meetingsUrl(String suffix) {
        return String.format("%s/accounts/%s/realtime/kit/%s/meetings%s", baseUrl, accountId, appId, suffix);
    }

    public ParticipantToken addParticipant(String meetingId, String displayName, String customParticipantId) {
        requireConfigured();
        long start = System.nanoTime();
        String endpoint = meetingsUrl("/" + meetingId + "/participants");

        Map<String, Object> body = new HashMap<>();
        body.put("name", displayName != null ? displayName : "Usuario");
        body.put("preset_name", defaultPresetName);
        if (customParticipantId != null && !customParticipantId.isBlank()) {
            body.put("custom_participant_id", customParticipantId);
        }
        if (participantTokenTtlMinutes > 0) {
            body.put("token", Map.of("expires_on", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                    .plusMinutes(participantTokenTtlMinutes).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()));
        }

        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long durationMs = (System.nanoTime() - start) / 1_000_000;

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // P8-A8: sin el cuerpo de la respuesta (puede incluir datos del participante).
                log.error("Failed to add participant to RealtimeKit meeting. Status: {}", response.statusCode());
                throw new BusinessRuleConflictException("REALTIMEKIT_PARTICIPANT_FAILED",
                        "No se pudo registrar al participante en la sala de audio.");
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (!root.path("success").asBoolean(false)) {
                log.error("Cloudflare RealtimeKit reported unsuccessful add participant (status {})", response.statusCode());
                throw new BusinessRuleConflictException("REALTIMEKIT_PARTICIPANT_FAILED",
                        "Cloudflare RealtimeKit rechazó el registro del participante.");
            }

            JsonNode data = root.path("data");
            String participantId = data.path("id").asText();
            String token = data.path("token").asText();

            if (participantId == null || participantId.isBlank() || token == null || token.isBlank()) {
                throw new BusinessRuleConflictException("REALTIMEKIT_INVALID_RESPONSE",
                        "Respuesta incompleta de Cloudflare RealtimeKit al autorizar participante.");
            }

            log.info("Participant {} successfully registered in meeting {} in {}ms", participantId, meetingId, durationMs);
            return new ParticipantToken(participantId, token);
        } catch (BusinessRuleConflictException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error adding participant to Cloudflare RealtimeKit meeting: {}", e.getClass().getSimpleName());
            throw new BusinessRuleConflictException("REALTIMEKIT_COMMUNICATION_ERROR",
                    "Error de conexión al autorizar participante en Cloudflare RealtimeKit.");
        }
    }

    /**
     * Resultado de una operacion de limpieza: {@code true} si no hace falta reintentar (hecha, o el recurso ya no
     * existe); {@code false} si fallo de forma transitoria (red, 5xx, 429) y conviene reintentar.
     */
    private boolean doneOrGone(String operation, String target, int status, long startNanos) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        boolean done = (status >= 200 && status < 300) || status == 404 || status == 410;
        boolean retryable = status >= 500 || status == 429 || status == 408;
        if (done) {
            log.info("{} {} -> HTTP {} in {}ms", operation, target, status, durationMs);
            return true;
        }
        if (retryable) {
            log.warn("{} {} -> HTTP {} in {}ms (se reintentara)", operation, target, status, durationMs);
            return false;
        }
        // 400/401/403/409...: reintentar no lo arreglaria; se registra y la conciliacion lo volvera a comprobar.
        log.warn("{} {} -> HTTP {} in {}ms (no reintentable)", operation, target, status, durationMs);
        return true;
    }

    private int send(HttpRequest request) throws java.io.IOException, InterruptedException {
        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** Borra el participante: revoca TODOS sus tokens y lo saca de la sesion activa (documentado por Cloudflare). */
    public boolean deleteParticipant(String meetingId, String participantId) {
        if (meetingId == null || participantId == null || !isConfigured()) return true;
        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(meetingsUrl("/" + meetingId + "/participants/" + participantId)))
                    .header("Authorization", "Bearer " + apiToken)
                    .timeout(Duration.ofSeconds(5))
                    .DELETE()
                    .build();
            return doneOrGone("Delete participant", participantId + "@" + meetingId, send(request), start);
        } catch (Exception e) {
            log.warn("Delete participant {} in meeting {} failed: {}", participantId, meetingId, e.getClass().getSimpleName());
            return false;
        }
    }

    public boolean kickAllParticipants(String meetingId) {
        if (meetingId == null || meetingId.isBlank() || !isConfigured()) return true;
        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(meetingsUrl("/" + meetingId + "/active-session/kick-all")))
                    .header("Authorization", "Bearer " + apiToken)
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            return doneOrGone("Kick all", meetingId, send(request), start);
        } catch (Exception e) {
            log.warn("Kick all in meeting {} failed: {}", meetingId, e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * P8-A3: estado de la reunion. Segun Cloudflare, nadie puede unirse a una reunion INACTIVE: es la barrera del lado
     * del servidor que usa la preparacion anticipada. Devuelve true solo con 2xx.
     */
    public boolean setMeetingActive(String meetingId, boolean active) {
        if (meetingId == null || meetingId.isBlank() || !isConfigured()) return false;
        long start = System.nanoTime();
        try {
            String jsonBody = objectMapper.writeValueAsString(Map.of("status", active ? "ACTIVE" : "INACTIVE"));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(meetingsUrl("/" + meetingId)))
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            int status = send(request);
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.info("Meeting {} -> {}: HTTP {} in {}ms", meetingId, active ? "ACTIVE" : "INACTIVE", status, durationMs);
            return status >= 200 && status < 300;
        } catch (Exception e) {
            log.warn("Set meeting {} {} failed: {}", meetingId, active ? "ACTIVE" : "INACTIVE", e.getClass().getSimpleName());
            return false;
        }
    }

    /** Cierra la reunion (INACTIVE). Devuelve true si no hace falta reintentar. */
    public boolean closeMeeting(String meetingId) {
        if (meetingId == null || meetingId.isBlank() || !isConfigured()) return true;
        return setMeetingActive(meetingId, false);
    }

    /**
     * P8-A3 (conciliacion): ids de los participantes que siguen registrados en la reunion; vacio si ninguno; null si no
     * se pudo consultar.
     */
    public java.util.List<String> listParticipantIds(String meetingId) {
        if (meetingId == null || meetingId.isBlank() || !isConfigured()) return null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(meetingsUrl("/" + meetingId + "/participants?per_page=100")))
                    .header("Authorization", "Bearer " + apiToken)
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // 400/404: la reunion no existe o el id no es valido (p. ej. ids de un simulador): no queda nada que limpiar.
            // Antes se trataba como fallo y se reintentaba en cada pasada de la conciliacion.
            if (response.statusCode() == 404 || response.statusCode() == 400) return java.util.List.of();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("List participants of meeting {} -> HTTP {}", meetingId, response.statusCode());
                return null;
            }
            JsonNode data = objectMapper.readTree(response.body()).path("data");
            JsonNode list = data.isArray() ? data : data.path("participants");
            java.util.List<String> ids = new java.util.ArrayList<>();
            if (list.isArray()) {
                list.forEach(p -> {
                    String id = p.path("id").asText(null);
                    if (id != null && !id.isBlank()) ids.add(id);
                });
            }
            return ids;
        } catch (Exception e) {
            log.warn("List participants of meeting {} failed: {}", meetingId, e.getClass().getSimpleName());
            return null;
        }
    }
}
