package com.havita.modules.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quién autorizó el ingreso y quién registró la salida de una visita.
 * <ul>
 *   <li>Portería y administración lo ven en las respuestas y en tiempo real.</li>
 *   <li>El residente NO recibe el nombre del personal (ni en su lista ni por SSE).</li>
 *   <li>El nombre sale del usuario autenticado que registró la acción, nunca del cliente.</li>
 * </ul>
 * Datos sintéticos (TwoTenantFixture).
 */
class VisitorStaffNamesIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private ObjectMapper objectMapper;

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

    private JsonNode data(ResultActions r) throws Exception {
        return objectMapper.readTree(r.andReturn().getResponse().getContentAsString()).get("data");
    }

    private MvcResult open(User user, Side side) throws Exception {
        String body = mvc.perform(post("/api/v1/sync/stream-ticket")
                        .header("Authorization", fixture.bearer(user))
                        .header("X-Tenant-ID", side.tenantId().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String ticket = objectMapper.readTree(body).get("ticket").asText();
        return mvc.perform(get("/api/v1/sync/stream").param("ticket", ticket))
                .andExpect(request().asyncStarted()).andReturn();
    }

    private List<JsonNode> statusEvents(MvcResult stream) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (String line : stream.getResponse().getContentAsString().split("\n")) {
            if (line.startsWith("data:") && line.contains("\"type\":\"VISITOR_STATUS_CHANGED\"")) {
                out.add(objectMapper.readTree(line.substring("data:".length())));
            }
        }
        return out;
    }

    private String createVisitorForOwnerHome() throws Exception {
        JsonNode created = data(as(a.guard(), a, post("/api/v1/access/visitors")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"%s\",\"name\":\"Visita Sintetica\",\"documentId\":\"CC-5566\"}"
                        .formatted(a.home().getId())))
                .andExpect(status().isCreated()));
        return created.get("id").asText();
    }

    private static JsonNode find(JsonNode list, String id) {
        for (JsonNode n : list) if (id.equals(n.get("id").asText())) return n;
        throw new AssertionError("visita " + id + " no encontrada");
    }

    @Test
    @DisplayName("Portería registra el ingreso y la administración la salida: ambos nombres en la vista del personal")
    void staffSeesWhoRegisteredEntryAndExit() throws Exception {
        String id = createVisitorForOwnerHome();

        JsonNode entry = data(as(a.guard(), a, post("/api/v1/access/visitors/" + id + "/entry")).andExpect(status().isOk()));
        assertThat(entry.get("enteredByName").asText()).isEqualTo(a.guard().getFullName());
        assertThat(entry.get("exitedByName").isNull()).isTrue();

        JsonNode exit = data(as(a.admin(), a, post("/api/v1/access/visitors/" + id + "/exit")).andExpect(status().isOk()));
        assertThat(exit.get("enteredByName").asText()).isEqualTo(a.guard().getFullName());
        assertThat(exit.get("exitedByName").asText()).isEqualTo(a.admin().getFullName());

        // Listado de portería/administración, detalle y por unidad.
        JsonNode page = data(as(a.admin(), a, get("/api/v1/access/visitors").param("size", "100")).andExpect(status().isOk()));
        JsonNode listed = find(page.get("content"), id);
        assertThat(listed.get("enteredByName").asText()).isEqualTo(a.guard().getFullName());
        assertThat(listed.get("exitedByName").asText()).isEqualTo(a.admin().getFullName());

        JsonNode byId = data(as(a.guard(), a, get("/api/v1/access/visitors/" + id)).andExpect(status().isOk()));
        assertThat(byId.get("exitedByName").asText()).isEqualTo(a.admin().getFullName());

        JsonNode byProperty = data(as(a.guard(), a, get("/api/v1/access/visitors/by-property/" + a.home().getId()))
                .andExpect(status().isOk()));
        assertThat(find(byProperty, id).get("enteredByName").asText()).isEqualTo(a.guard().getFullName());
    }

    @Test
    @DisplayName("El residente no recibe el nombre del personal en su lista")
    void residentListHasNoStaffNames() throws Exception {
        // La visita la pre-autoriza el propio residente (su lista solo muestra las que él creó).
        JsonNode created = data(as(a.owner(), a, post("/api/v1/resident/me/visitors")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Visita Del Residente\",\"documentId\":\"CC-9911\"}"))
                .andExpect(status().is2xxSuccessful()));
        String id = created.get("id").asText();
        as(a.guard(), a, post("/api/v1/access/visitors/" + id + "/entry")).andExpect(status().isOk());
        as(a.guard(), a, post("/api/v1/access/visitors/" + id + "/exit")).andExpect(status().isOk());

        String raw = as(a.owner(), a, get("/api/v1/resident/me/visitors")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain(a.guard().getFullName());
        JsonNode mine = find(objectMapper.readTree(raw).get("data"), id);
        assertThat(mine.get("status").asText()).isEqualTo("EXITED");
        assertThat(mine.get("enteredByName").isNull()).isTrue();
        assertThat(mine.get("exitedByName").isNull()).isTrue();
    }

    @Test
    @DisplayName("Tiempo real: la gestión recibe quién registró el ingreso; el residente de la unidad y otra comunidad no")
    void realtimeStaffNamesOnlyForStaff() throws Exception {
        String id = createVisitorForOwnerHome();
        MvcResult owner = open(a.owner(), a);
        MvcResult admin = open(a.admin(), a);
        MvcResult otherTenant = open(b.admin(), b);

        as(a.guard(), a, post("/api/v1/access/visitors/" + id + "/entry")).andExpect(status().isOk());

        String guardName = a.guard().getFullName();
        // El residente sigue enterándose del ingreso, pero sin quién lo registró.
        List<JsonNode> ownerEvents = statusEvents(owner);
        assertThat(ownerEvents).hasSize(1);
        JsonNode ownerVisitor = ownerEvents.get(0).get("payload").get("visitor");
        assertThat(ownerVisitor.get("status").asText()).isEqualTo("ENTERED");
        assertThat(ownerVisitor.get("enteredByName").isNull()).isTrue();

        // La gestión recibe además la copia con el nombre de quien registró el ingreso.
        assertThat(statusEvents(admin))
                .anySatisfy(e -> assertThat(e.get("payload").get("visitor").get("enteredByName").asText()).isEqualTo(guardName));
        assertThat(otherTenant.getResponse().getContentAsString()).doesNotContain("VISITOR_STATUS_CHANGED");
    }

    @Test
    @DisplayName("Otra comunidad no puede registrar el ingreso ni ver la visita")
    void crossTenantCannotRegisterOrRead() throws Exception {
        String id = createVisitorForOwnerHome();
        int entry = as(b.guard(), b, post("/api/v1/access/visitors/" + id + "/entry")).andReturn().getResponse().getStatus();
        assertThat(entry).isIn(403, 404);
        int read = as(b.admin(), b, get("/api/v1/access/visitors/" + id)).andReturn().getResponse().getStatus();
        assertThat(read).isIn(403, 404);
    }
}
