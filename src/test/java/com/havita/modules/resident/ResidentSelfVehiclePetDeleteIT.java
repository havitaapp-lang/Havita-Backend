package com.havita.modules.resident;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.identity.domain.User;
import com.havita.modules.resident.infrastructure.PetRepository;
import com.havita.modules.resident.infrastructure.VehicleRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-011 #1/#2: Mi Hogar elimina vehiculos y mascotas. La eliminacion persiste (la siguiente lectura ya no los
 * devuelve) y solo la puede hacer el residente de la unidad; ni un vecino ni otra comunidad pueden eliminarlos.
 */
class ResidentSelfVehiclePetDeleteIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private PetRepository petRepository;

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

    private String create(String path, String body) throws Exception {
        return data(as(a.owner(), a, post("/api/v1/resident/me/" + path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())).get("id").asText();
    }

    private boolean listed(String path, String id) throws Exception {
        JsonNode list = data(as(a.owner(), a, get("/api/v1/resident/me/" + path)).andExpect(status().isOk()));
        for (JsonNode item : list) if (id.equals(item.get("id").asText())) return true;
        return false;
    }

    private void assertNotDeletedBy(User user, Side side, String path, String id) throws Exception {
        int code = as(user, side, delete("/api/v1/resident/me/{path}/{id}", path, id)).andReturn().getResponse().getStatus();
        assertThat(code).as("DELETE ajeno de %s", path).isGreaterThanOrEqualTo(400);
    }

    @Test
    @DisplayName("vehiculo: el dueno lo elimina y la siguiente lectura ya no lo devuelve; vecino y otra comunidad no pueden")
    void vehicleDeletePersistsAndIsOwnerOnly() throws Exception {
        String id = create("vehicles", "{\"plate\":\"TST%s\",\"type\":\"Automóvil\",\"brand\":\"Marca\",\"model\":\"Modelo\",\"color\":\"Gris\"}"
                .formatted(UUID.randomUUID().toString().substring(0, 4).toUpperCase()));
        assertThat(listed("vehicles", id)).isTrue();

        assertNotDeletedBy(a.neighbour(), a, "vehicles", id);
        assertNotDeletedBy(b.owner(), b, "vehicles", id);
        assertThat(vehicleRepository.existsById(UUID.fromString(id))).isTrue();

        as(a.owner(), a, delete("/api/v1/resident/me/vehicles/{id}", id)).andExpect(status().isOk());
        assertThat(listed("vehicles", id)).isFalse();
        assertThat(vehicleRepository.existsById(UUID.fromString(id))).isFalse();
    }

    @Test
    @DisplayName("mascota: el dueno la elimina y la siguiente lectura ya no la devuelve; vecino y otra comunidad no pueden")
    void petDeletePersistsAndIsOwnerOnly() throws Exception {
        String id = create("pets", "{\"name\":\"Mascota Sintetica\",\"type\":\"Perro\",\"breed\":\"Criollo\",\"color\":\"Cafe\",\"isDangerousBreed\":false}");
        assertThat(listed("pets", id)).isTrue();

        assertNotDeletedBy(a.neighbour(), a, "pets", id);
        assertNotDeletedBy(b.owner(), b, "pets", id);
        assertThat(petRepository.existsById(UUID.fromString(id))).isTrue();

        as(a.owner(), a, delete("/api/v1/resident/me/pets/{id}", id)).andExpect(status().isOk());
        assertThat(listed("pets", id)).isFalse();
        assertThat(petRepository.existsById(UUID.fromString(id))).isFalse();
    }
}
