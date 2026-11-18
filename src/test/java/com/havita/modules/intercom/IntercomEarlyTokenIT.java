package com.havita.modules.intercom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.intercom.infrastructure.IntercomCallRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P8-A3 — preparación anticipada con barrera de Cloudflare ({@code havita.intercom.early-token.enabled=true}), sobre
 * PostgreSQL aislado y RealtimeKit SIMULADO.
 *
 * <ul>
 *   <li>Hereda las 10 pruebas de {@link IntercomLifecycleIT}, que deben seguir pasando con la bandera.</li>
 *   <li>Añade el orden de la barrera: la reunión pasa a INACTIVE antes de emitir tokens y a ACTIVE solo al aceptar.</li>
 *   <li>Añade la autorización de {@code /prepare}.</li>
 * </ul>
 */
class IntercomEarlyTokenIT extends IntercomLifecycleIT {

    @DynamicPropertySource
    static void earlyToken(DynamicPropertyRegistry registry) {
        registry.add("havita.intercom.early-token.enabled", () -> "true");
        registry.add("havita.intercom.parallel-participant-creation", () -> "true");
    }

    @Autowired private ObjectMapper mapper;
    @Autowired private IntercomCallRepository callRepository;

    private List<String> since(int before) {
        return List.copyOf(REQUESTS.subList(before, REQUESTS.size()));
    }

    @Test
    @DisplayName("Barrera: INACTIVE antes de emitir tokens; ACTIVE solo cuando acepta el residente autorizado")
    void gateOrder() throws Exception {
        int before = REQUESTS.size();
        String body = "{\"propertyId\":\"%s\",\"recipientResidentId\":\"%s\"}"
                .formatted(neighbourHomeId(), neighbourResidentId());
        String created = mvcAs(guard(), post("/api/v1/intercom/calls").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(mapper.readTree(created).path("data").path("callId").asText());
        String meetingId = callRepository.findById(id).orElseThrow().getRealtimeMeetingId();

        List<String> init = since(before).stream().filter(r -> r.contains(meetingId) || r.endsWith("/meetings")).toList();
        int inactive = indexOf(init, r -> r.startsWith("PATCH ") && r.contains("INACTIVE"));
        int firstParticipant = indexOf(init, r -> r.startsWith("POST ") && r.contains("/participants"));
        assertThat(inactive).as("PATCH INACTIVE al iniciar").isGreaterThanOrEqualTo(0);
        assertThat(firstParticipant).as("participantes tras cerrar la barrera").isGreaterThan(inactive);
        assertThat(init).noneMatch(r -> r.startsWith("PATCH ") && r.contains("\"ACTIVE\""));

        // Mientras timbra: SOLO el residente llamado obtiene su token por /prepare.
        JsonNode prep = mapper.readTree(mvcAs(neighbour(), post("/api/v1/intercom/calls/{id}/prepare", id))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(prep.path("participantToken").asText()).startsWith(PARTICIPANT_TOKEN_PREFIX);
        assertThat(prep.path("status").asText()).isEqualTo("RINGING");
        mvcAs(owner(), post("/api/v1/intercom/calls/{id}/prepare", id)).andExpect(status().isForbidden());
        mvcAs(guard(), post("/api/v1/intercom/calls/{id}/prepare", id)).andExpect(status().isForbidden());
        mvcOtherTenant(post("/api/v1/intercom/calls/{id}/prepare", id)).andExpect(status().isNotFound());
        assertThat(since(before)).noneMatch(r -> r.startsWith("PATCH ") && r.contains(meetingId) && r.contains("\"ACTIVE\""));

        // Aceptar abre la barrera.
        int beforeAccept = REQUESTS.size();
        mvcAs(neighbour(), post("/api/v1/intercom/calls/{id}/accept", id)).andExpect(status().isOk());
        assertThat(since(beforeAccept)).anyMatch(r -> r.startsWith("PATCH ") && r.contains(meetingId) && r.contains("\"ACTIVE\""));

        // Ya no timbra: /prepare se niega.
        mvcAs(neighbour(), post("/api/v1/intercom/calls/{id}/prepare", id)).andExpect(status().isConflict());
        mvcAs(guard(), post("/api/v1/intercom/calls/{id}/end", id)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Rechazo con la barrera: la reunión nunca pasa a ACTIVE y se borran los participantes (revocación)")
    void rejectNeverActivates() throws Exception {
        int before = REQUESTS.size();
        String body = "{\"propertyId\":\"%s\",\"recipientResidentId\":\"%s\"}"
                .formatted(neighbourHomeId(), neighbourResidentId());
        String created = mvcAs(guard(), post("/api/v1/intercom/calls").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(mapper.readTree(created).path("data").path("callId").asText());
        String meetingId = callRepository.findById(id).orElseThrow().getRealtimeMeetingId();
        mvcAs(neighbour(), post("/api/v1/intercom/calls/{id}/prepare", id)).andExpect(status().isOk());
        mvcAs(neighbour(), post("/api/v1/intercom/calls/{id}/reject", id)).andExpect(status().isOk());
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && since(before).stream().filter(r -> r.startsWith("DELETE ") && r.contains(meetingId)).count() < 2) {
            Thread.sleep(50);
        }
        assertThat(since(before).stream().filter(r -> r.startsWith("DELETE ") && r.contains(meetingId)).count()).isEqualTo(2);
        assertThat(since(before)).noneMatch(r -> r.startsWith("PATCH ") && r.contains(meetingId) && r.contains("\"ACTIVE\""));
        mvcAs(neighbour(), post("/api/v1/intercom/calls/{id}/prepare", id)).andExpect(status().isConflict());
    }

    private static int indexOf(List<String> list, java.util.function.Predicate<String> p) {
        for (int i = 0; i < list.size(); i++) if (p.test(list.get(i))) return i;
        return -1;
    }
}
