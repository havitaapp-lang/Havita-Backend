package com.havita.modules.resident;

import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Community;
import com.havita.support.TwoTenantFixture.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-1 de extremo a extremo (HTTP → filtro JWT → servicio → PostgreSQL) con dos comunidades reales.
 * Un COMMUNITY_ADMIN de A no puede leer, modificar ni vincular residentes o cuentas de B.
 */
class ResidentTenantIsolationIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private UserRepository userRepository;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        Community c = fixture.create();
        a = c.a();
        b = c.b();
    }

    private MockHttpServletRequestBuilder asAdminA(MockHttpServletRequestBuilder req) {
        return req.header("Authorization", fixture.bearer(a.admin()))
                .header("X-Tenant-ID", a.tenantId().toString())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private int residentsInA() {
        return residentRepository.findByTenantId(a.tenantId()).size();
    }

    @Test
    @DisplayName("assignProperties con el id de un residente de B responde 404 y no copia sus datos en A")
    void assignWithForeignResidentId() throws Exception {
        int before = residentsInA();
        mvc.perform(asAdminA(post("/api/v1/residents/users/{id}/properties", b.ownerResident().getId()))
                        .content("{\"propertyIds\":[\"" + a.vacant().getId() + "\"],\"type\":\"Propietario\"}"))
                .andExpect(status().isNotFound());

        assertEquals(before, residentsInA());
        assertTrue(membershipRepository.findByUserIdAndTenantId(b.owner().getId(), a.tenantId()).isEmpty(),
                "no se debe conceder membresia en A a una cuenta de B");
    }

    @Test
    @DisplayName("assignProperties con el id de una cuenta de B responde 404 y no crea membresia")
    void assignWithForeignUserId() throws Exception {
        mvc.perform(asAdminA(post("/api/v1/residents/users/{id}/properties", b.owner().getId()))
                        .content("{\"propertyIds\":[\"" + a.vacant().getId() + "\"]}"))
                .andExpect(status().isNotFound());

        assertTrue(membershipRepository.findByUserIdAndTenantId(b.owner().getId(), a.tenantId()).isEmpty());
    }

    @Test
    @DisplayName("assignProperties dentro de A funciona (control positivo)")
    void assignWithinTenantWorks() throws Exception {
        mvc.perform(asAdminA(post("/api/v1/residents/users/{id}/properties", a.ownerResident().getId()))
                        .content("{\"propertyIds\":[\"" + a.vacant().getId() + "\"],\"type\":\"Propietario\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data[0].propertyId").value(a.vacant().getId().toString()))
                .andExpect(jsonPath("$.data[0].tenantId").value(a.tenantId().toString()));
    }

    @Test
    @DisplayName("POST /residents con userId de B responde 404 y no crea membresia ni censo")
    void createWithForeignUser() throws Exception {
        int before = residentsInA();
        mvc.perform(asAdminA(post("/api/v1/residents"))
                        .content("""
                                {"propertyId":"%s","userId":"%s","name":"Intruso","email":"intruso-%s@example.test","phone":"3001112233"}
                                """.formatted(a.vacant().getId(), b.owner().getId(), UUID.randomUUID())))
                .andExpect(status().isNotFound());

        assertEquals(before, residentsInA());
        assertTrue(membershipRepository.findByUserIdAndTenantId(b.owner().getId(), a.tenantId()).isEmpty());
    }

    @Test
    @DisplayName("PUT /residents/{id} con userId de B responde 404 y el residente no queda vinculado")
    void updateLinkingForeignUser() throws Exception {
        mvc.perform(asAdminA(put("/api/v1/residents/{id}", a.censusOnly().getId()))
                        .content("{\"userId\":\"" + b.owner().getId() + "\"}"))
                .andExpect(status().isNotFound());

        assertNull(residentRepository.findById(a.censusOnly().getId()).orElseThrow().getUser());
        assertTrue(membershipRepository.findByUserIdAndTenantId(b.owner().getId(), a.tenantId()).isEmpty());
    }

    @Test
    @DisplayName("PUT, GET y DELETE sobre un residente de B responden 404 y B queda intacto")
    void crudOnForeignResident() throws Exception {
        UUID foreign = b.ownerResident().getId();
        mvc.perform(asAdminA(get("/api/v1/residents/{id}", foreign))).andExpect(status().isNotFound());
        mvc.perform(asAdminA(put("/api/v1/residents/{id}", foreign)).content("{\"name\":\"Hackeado\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(asAdminA(delete("/api/v1/residents/{id}", foreign))).andExpect(status().isNotFound());

        var untouched = residentRepository.findById(foreign).orElseThrow();
        assertEquals(b.ownerResident().getName(), untouched.getName());
        assertEquals(ResidentStatus.ACTIVE, untouched.getStatus());
    }

    @Test
    @DisplayName("El listado de A no contiene residentes de B")
    void listOnlyOwnTenant() throws Exception {
        String body = mvc.perform(asAdminA(get("/api/v1/residents").param("size", "100")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(b.tenantId().toString()));
        assertFalse(body.contains(b.ownerResident().getId().toString()));
        assertTrue(body.contains(a.ownerResident().getId().toString()));
    }

    @Test
    @DisplayName("Un X-Tenant-ID de B con token de A no da acceso a B")
    void forgedTenantHeader() throws Exception {
        mvc.perform(get("/api/v1/residents")
                        .header("Authorization", fixture.bearer(a.admin()))
                        .header("X-Tenant-ID", b.tenantId().toString()))
                .andExpect(result -> {
                    int code = result.getResponse().getStatus();
                    assertTrue(code == 401 || code == 403, "esperado 401/403, recibido " + code);
                });
    }

    @Test
    @DisplayName("Editar el censo no modifica el nombre ni el telefono de la cuenta global")
    void updateDoesNotOverwriteGlobalAccount() throws Exception {
        String originalName = a.owner().getFullName();
        String originalPhone = a.owner().getPhone();

        mvc.perform(asAdminA(put("/api/v1/residents/{id}", a.ownerResident().getId()))
                        .content("{\"name\":\"Nombre Solo En Censo\",\"phone\":\"3209998877\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Nombre Solo En Censo"));

        var account = userRepository.findById(a.owner().getId()).orElseThrow();
        assertEquals(originalName, account.getFullName());
        assertEquals(originalPhone, account.getPhone());
    }

    @Test
    @DisplayName("Transferir una unidad de A a una cuenta de B responde 404 y el propietario actual sigue activo")
    void transferToForeignUser() throws Exception {
        mvc.perform(asAdminA(post("/api/v1/properties/{id}/transfer-owner", a.home().getId()))
                        .content("""
                                {"newOwnerName":"Comprador","newOwnerUserId":"%s","effectiveDate":"2026-11-01T00:00:00Z"}
                                """.formatted(b.owner().getId())))
                .andExpect(status().isNotFound());

        assertEquals(ResidentStatus.ACTIVE,
                residentRepository.findById(a.ownerResident().getId()).orElseThrow().getStatus());
        assertTrue(membershipRepository.findByUserIdAndTenantId(b.owner().getId(), a.tenantId()).isEmpty());
    }
}
