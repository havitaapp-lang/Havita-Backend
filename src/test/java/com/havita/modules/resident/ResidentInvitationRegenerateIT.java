package com.havita.modules.resident;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.identity.domain.User;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.domain.PropertyStatus;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.InvitationStatus;
import com.havita.modules.resident.domain.ResidentInvitation;
import com.havita.modules.resident.infrastructure.ResidentInvitationRepository;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-011 #3: una invitacion pendiente reabierta no trae enlace (solo se devuelve al emitirla). La administracion
 * puede regenerarla: se revoca la actual y se emite otra para la misma unidad, en una sola transaccion, sin saltarse
 * las reglas de emision ni el aislamiento entre comunidades.
 */
class ResidentInvitationRegenerateIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private ResidentInvitationRepository invitationRepository;
    @Autowired private PropertyRepository propertyRepository;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
    }

    private ResultActions as(User user, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(user)).header("X-Tenant-ID", side.tenantId().toString()));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static String token(JsonNode invitation) {
        String link = invitation.get("link").asText();
        return link.substring(link.lastIndexOf('/') + 1);
    }

    private JsonNode generate(Property property, String body) throws Exception {
        return data(as(a.admin(), a, post("/api/v1/properties/{id}/resident-invitations", property.getId())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()));
    }

    private ResultActions regenerate(User user, Side side, String invitationId) throws Exception {
        return as(user, side, post("/api/v1/resident-invitations/{id}/regenerate", invitationId));
    }

    private ResultActions validate(String token) throws Exception {
        return mvc.perform(get("/api/v1/public/resident-invitations/{token}", token));
    }

    private ResidentInvitation stored(String id) {
        return invitationRepository.findById(UUID.fromString(id)).orElseThrow();
    }

    @Test
    @DisplayName("regenerar revoca la invitacion pendiente y emite otra valida para la misma unidad, con enlace nuevo")
    void regenerateRotatesTheLink() throws Exception {
        JsonNode original = generate(a.vacant(), "{\"additionalPropertyIds\":[]}");
        String originalId = original.get("id").asText();

        JsonNode renewed = data(regenerate(a.admin(), a, originalId).andExpect(status().isOk()));

        assertThat(renewed.get("id").asText()).isNotEqualTo(originalId);
        assertThat(renewed.get("propertyId").asText()).isEqualTo(a.vacant().getId().toString());
        assertThat(renewed.get("status").asText()).isEqualTo("PENDING");
        assertThat(renewed.get("invitationType").asText()).isEqualTo("OWNER");
        assertThat(token(renewed)).isNotEqualTo(token(original));

        // El QR anterior deja de servir y el nuevo corresponde a la misma unidad.
        validate(token(original)).andExpect(status().isConflict());
        validate(token(renewed)).andExpect(status().isOk());
        assertThat(stored(originalId).getStatus()).isEqualTo(InvitationStatus.REVOKED);
        assertThat(stored(originalId).getRevokedAt()).isNotNull();

        // Queda exactamente una pendiente, la nueva; el listado sigue sin exponer enlaces.
        JsonNode list = data(as(a.admin(), a, get("/api/v1/properties/{id}/resident-invitations", a.vacant().getId()))
                .andExpect(status().isOk()));
        List<JsonNode> pending = new java.util.ArrayList<>();
        list.forEach(i -> { if ("PENDING".equals(i.get("status").asText())) pending.add(i); });
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).get("id").asText()).isEqualTo(renewed.get("id").asText());
        list.forEach(i -> assertThat(i.get("link").isNull()).isTrue());
    }

    @Test
    @DisplayName("la invitacion regenerada conserva las unidades adicionales de la original")
    void regenerateKeepsAdditionalProperties() throws Exception {
        JsonNode original = generate(a.vacant(),
                "{\"additionalPropertyIds\":[\"%s\"]}".formatted(a.neighbourHome().getId()));
        JsonNode renewed = data(regenerate(a.admin(), a, original.get("id").asText()).andExpect(status().isOk()));
        assertThat(stored(renewed.get("id").asText()).getAdditionalPropertyIds())
                .isEqualTo(a.neighbourHome().getId().toString());
    }

    @Test
    @DisplayName("otra comunidad no puede regenerar la invitacion (ni con su tenant ni falsificando el X-Tenant-ID)")
    void crossTenantCannotRegenerate() throws Exception {
        JsonNode original = generate(a.vacant(), "{}");
        String id = original.get("id").asText();

        regenerate(b.admin(), b, id).andExpect(status().isNotFound());
        int forged = regenerate(b.admin(), a, id).andReturn().getResponse().getStatus();
        assertThat(forged).isIn(401, 403);

        assertThat(stored(id).getStatus()).isEqualTo(InvitationStatus.PENDING);
        validate(token(original)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("residentes y porteria no pueden regenerar invitaciones")
    void onlyAdministrationCanRegenerate() throws Exception {
        JsonNode original = generate(a.vacant(), "{}");
        String id = original.get("id").asText();

        regenerate(a.owner(), a, id).andExpect(status().isForbidden());
        regenerate(a.guard(), a, id).andExpect(status().isForbidden());

        assertThat(stored(id).getStatus()).isEqualTo(InvitationStatus.PENDING);
        validate(token(original)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("una invitacion revocada no se puede reactivar regenerandola")
    void revokedInvitationCannotBeRegenerated() throws Exception {
        String id = generate(a.vacant(), "{}").get("id").asText();
        as(a.admin(), a, post("/api/v1/resident-invitations/{id}/revoke", id)).andExpect(status().isOk());

        regenerate(a.admin(), a, id).andExpect(status().isConflict());
        assertThat(invitationRepository.findByTenantIdAndPropertyId(a.tenantId(), a.vacant().getId()))
                .noneMatch(i -> i.getStatus() == InvitationStatus.PENDING);
    }

    @Test
    @DisplayName("las invitaciones de hogar emitidas por residentes no se regeneran desde la administracion")
    void householdInvitationIsNotRegenerable() throws Exception {
        String body = as(a.owner(), a, post("/api/v1/resident/me/invitations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"Residente\",\"email\":\"familiar-%s@example.test\",\"name\":\"Familiar Sintetico\",\"phone\":\"3001112233\",\"propertyId\":\"%s\"}"
                        .formatted(UUID.randomUUID().toString().substring(0, 8), a.home().getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).at("/data/id").asText();

        regenerate(a.admin(), a, id).andExpect(status().isConflict());
        assertThat(stored(id).getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    @DisplayName("si la unidad ya esta ocupada no se emite otra y la original queda intacta (rollback)")
    void occupiedPropertyRollsBack() throws Exception {
        JsonNode original = generate(a.vacant(), "{}");
        String id = original.get("id").asText();
        Property vacant = propertyRepository.findById(a.vacant().getId()).orElseThrow();
        vacant.setStatus(PropertyStatus.OCCUPIED);
        propertyRepository.save(vacant);

        regenerate(a.admin(), a, id).andExpect(status().isConflict());

        assertThat(stored(id).getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(invitationRepository.findByTenantIdAndPropertyId(a.tenantId(), a.vacant().getId())).hasSize(1);
    }
}
