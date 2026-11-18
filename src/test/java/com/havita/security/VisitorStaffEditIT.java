package com.havita.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.access.infrastructure.VisitorRepository;
import com.havita.modules.identity.domain.User;
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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /api/v1/access/visitors/{id}}: administracion y porteria editan los datos de un visitante de SU comunidad
 * (antes solo cambiaba en el navegador). Otra comunidad 404, residentes 403 (tienen su propio endpoint), visita que ya
 * salio 409. Datos sinteticos.
 */
class VisitorStaffEditIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VisitorRepository visitorRepository;

    private Side a;
    private Side b;
    private UUID visitorId;

    @BeforeEach
    void seed() throws Exception {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        String body = as(a.guard(), a, post("/api/v1/access/visitors").contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"%s\",\"name\":\"Visitante Sintetico\",\"documentId\":\"CC-1\"}".formatted(a.home().getId())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        visitorId = UUID.fromString(objectMapper.readTree(body).get("data").get("id").asText());
    }

    private ResultActions as(User user, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(user)).header("X-Tenant-ID", side.tenantId().toString()));
    }

    private MockHttpServletRequestBuilder edit(String json) {
        return put("/api/v1/access/visitors/{id}", visitorId).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test
    @DisplayName("Administración y portería editan los datos; se guardan en el servidor")
    void staffEdits() throws Exception {
        as(a.admin(), a, edit("{\"name\":\"  Nombre Corregido \",\"documentId\":\"CC-2\",\"vehiclePlate\":\"abc123\",\"observations\":\"Trae herramientas\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Nombre Corregido"))
                .andExpect(jsonPath("$.data.vehiclePlate").value("ABC123"));
        as(a.guard(), a, edit("{\"name\":\"Nombre Corregido\",\"documentId\":\"CC-3\"}")).andExpect(status().isOk());
        var v = visitorRepository.findById(visitorId).orElseThrow();
        assertThat(v.getDocumentId()).isEqualTo("CC-3");
        assertThat(v.getVehiclePlate()).isNull();
    }

    @Test
    @DisplayName("Otra comunidad 404 y residente 403, sin cambios")
    void isolation() throws Exception {
        as(b.admin(), b, edit("{\"name\":\"Intruso\",\"documentId\":\"X\"}")).andExpect(status().isNotFound());
        as(b.guard(), b, edit("{\"name\":\"Intruso\",\"documentId\":\"X\"}")).andExpect(status().isNotFound());
        as(a.owner(), a, edit("{\"name\":\"Intruso\",\"documentId\":\"X\"}")).andExpect(status().isForbidden());
        assertThat(visitorRepository.findById(visitorId).orElseThrow().getName()).isEqualTo("Visitante Sintetico");
    }

    @Test
    @DisplayName("Una visita que ya salió no se edita (409); datos obligatorios vacíos 400")
    void closedAndValidation() throws Exception {
        as(a.admin(), a, edit("{\"name\":\" \",\"documentId\":\"CC-1\"}")).andExpect(status().isBadRequest());
        as(a.guard(), a, post("/api/v1/access/visitors/{id}/entry", visitorId)).andExpect(status().isOk());
        as(a.guard(), a, post("/api/v1/access/visitors/{id}/exit", visitorId)).andExpect(status().isOk());
        as(a.admin(), a, edit("{\"name\":\"Otro\",\"documentId\":\"CC-9\"}")).andExpect(status().isConflict());
        assertThat(visitorRepository.findById(visitorId).orElseThrow().getName()).isEqualTo("Visitante Sintetico");
    }
}
