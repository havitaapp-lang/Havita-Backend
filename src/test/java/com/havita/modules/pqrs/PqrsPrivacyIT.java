package com.havita.modules.pqrs;

import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.pqrs.domain.PqrsRequest;
import com.havita.modules.pqrs.domain.PqrsRequestStatus;
import com.havita.modules.pqrs.infrastructure.PqrsRequestRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-4: PQRS. Un RESIDENT solo ve y comenta las PQRS de unidades en las que hoy tiene relacion y membresia
 * ACTIVE, no puede cambiar su estado (gestion) ni atribuirlas a residentes de otra unidad. Una PQRS ajena se
 * responde igual que una inexistente. Los roles de gestion conservan su acceso dentro de su comunidad.
 */
class PqrsPrivacyIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private PqrsRequestRepository pqrsRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ResidentRepository residentRepository;

    private Side a;
    private Side b;
    private PqrsRequest ownPqrs;
    private PqrsRequest neighbourPqrs;
    private PqrsRequest foreignPqrs;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        ownPqrs = pqrs(a, a.home(), a.owner(), "Filtracion en el bano");
        neighbourPqrs = pqrs(a, a.neighbourHome(), a.neighbour(), "Queja privada del vecino");
        foreignPqrs = pqrs(b, b.home(), b.owner(), "PQRS de otra comunidad");
    }

    private PqrsRequest pqrs(Side side, Property property, User creator, String title) {
        PqrsRequest p = PqrsRequest.builder()
                .property(property).createdByUser(creator).title(title).description(title + " (detalle)")
                .category("Mantenimiento").priority("Media").status(PqrsRequestStatus.NEW)
                .build();
        p.setTenantId(side.tenantId());
        return pqrsRepository.save(p);
    }

    private ResultActions as(User user, Side side, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                .header("Authorization", fixture.bearer(user))
                .header("X-Tenant-ID", side.tenantId().toString()));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String newPqrs(UUID propertyId, UUID residentId) {
        return """
                {"propertyId":"%s","residentId":%s,"category":"Mantenimiento","title":"Nueva","description":"Detalle"}
                """.formatted(propertyId, residentId == null ? "null" : "\"" + residentId + "\"");
    }

    // ── Lectura ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("RESIDENT lista y lee las PQRS de su unidad")
    void readsOwnUnit() throws Exception {
        as(a.owner(), a, get("/api/v1/pqrs/by-property/{id}", a.home().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(ownPqrs.getId().toString()));
        as(a.owner(), a, get("/api/v1/pqrs/{id}", ownPqrs.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Filtracion en el bano"));
    }

    @Test
    @DisplayName("Listado por unidad: vecino, otra comunidad e inventada responden 403 por igual")
    void unitListOfOthersIsForbidden() throws Exception {
        as(a.owner(), a, get("/api/v1/pqrs/by-property/{id}", a.neighbourHome().getId())).andExpect(status().isForbidden());
        as(a.owner(), a, get("/api/v1/pqrs/by-property/{id}", b.home().getId())).andExpect(status().isForbidden());
        as(a.owner(), a, get("/api/v1/pqrs/by-property/{id}", UUID.randomUUID())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Detalle: una PQRS ajena responde 404 igual que una inexistente (sin oraculo de existencia)")
    void foreignPqrsLooksMissing() throws Exception {
        as(a.owner(), a, get("/api/v1/pqrs/{id}", neighbourPqrs.getId())).andExpect(status().isNotFound());
        as(a.owner(), a, get("/api/v1/pqrs/{id}", foreignPqrs.getId())).andExpect(status().isNotFound());
        as(a.owner(), a, get("/api/v1/pqrs/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Con la membresia INACTIVE pierde el acceso aunque su JWT siga vigente")
    void revokedMembershipLosesAccess() throws Exception {
        // A-8: con la unica relacion o la membresia revocadas el JWT ya no autentica en esta comunidad
        // (ResidentAccessGate): la denegacion llega como 401 antes que el 403/404 del endpoint.
        String token = fixture.bearer(a.owner());
        var membership = membershipRepository.findByUserIdAndTenantId(a.owner().getId(), a.tenantId()).orElseThrow();
        membership.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(membership);

        mvc.perform(get("/api/v1/pqrs/by-property/{id}", a.home().getId())
                        .header("Authorization", token).header("X-Tenant-ID", a.tenantId().toString()))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
        mvc.perform(get("/api/v1/pqrs/{id}", ownPqrs.getId())
                        .header("Authorization", token).header("X-Tenant-ID", a.tenantId().toString()))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(404))));
        mvc.perform(json(post("/api/v1/pqrs"), newPqrs(a.home().getId(), null))
                        .header("Authorization", token).header("X-Tenant-ID", a.tenantId().toString()))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
    }

    @Test
    @DisplayName("Con la relacion con la unidad INACTIVE pierde el acceso")
    void revokedRelationLosesAccess() throws Exception {
        // A-8: con la unica relacion o la membresia revocadas el JWT ya no autentica en esta comunidad
        // (ResidentAccessGate): la denegacion llega como 401 antes que el 403/404 del endpoint.
        var rel = residentRepository.findById(a.ownerResident().getId()).orElseThrow();
        rel.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(rel);

        as(a.owner(), a, get("/api/v1/pqrs/by-property/{id}", a.home().getId())).andExpect(status().is(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
        as(a.owner(), a, get("/api/v1/pqrs/{id}", ownPqrs.getId())).andExpect(status().is(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(404))));
    }

    @Test
    @DisplayName("X-Tenant-ID de una comunidad sin membresia no autentica la peticion")
    void forgedTenantHeader() throws Exception {
        int s = as(a.owner(), b, get("/api/v1/pqrs/{id}", foreignPqrs.getId())).andReturn().getResponse().getStatus();
        assertThat(s).isIn(401, 403);
    }

    @Test
    @DisplayName("Gestion (COMMUNITY_ADMIN, SECURITY) lee cualquier PQRS de su comunidad pero no las de otra")
    void staffScope() throws Exception {
        as(a.admin(), a, get("/api/v1/pqrs/{id}", neighbourPqrs.getId())).andExpect(status().isOk());
        as(a.guard(), a, get("/api/v1/pqrs/by-property/{id}", a.neighbourHome().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        as(a.admin(), a, get("/api/v1/pqrs/{id}", foreignPqrs.getId())).andExpect(status().isNotFound());
        as(a.admin(), a, get("/api/v1/pqrs/by-property/{id}", b.home().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ── Escritura ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("RESIDENT radica PQRS para su unidad, a su nombre o de un familiar de la unidad")
    void createsForOwnUnit() throws Exception {
        as(a.owner(), a, json(post("/api/v1/pqrs"), newPqrs(a.home().getId(), a.censusOnly().getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.propertyId").value(a.home().getId().toString()))
                .andExpect(jsonPath("$.data.tenantId").value(a.tenantId().toString()));
    }

    @Test
    @DisplayName("RESIDENT no radica PQRS para otra unidad ni a nombre de un residente de otra unidad (403, nada se guarda)")
    void cannotCreateForOthers() throws Exception {
        long before = pqrsRepository.count();
        as(a.owner(), a, json(post("/api/v1/pqrs"), newPqrs(a.neighbourHome().getId(), null))).andExpect(status().isForbidden());
        as(a.owner(), a, json(post("/api/v1/pqrs"), newPqrs(b.home().getId(), null))).andExpect(status().isForbidden());
        // Antes se aceptaba y la respuesta devolvia el nombre del residente ajeno.
        as(a.owner(), a, json(post("/api/v1/pqrs"), newPqrs(a.home().getId(), a.neighbourResident().getId())))
                .andExpect(status().isForbidden());
        assertThat(pqrsRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("RESIDENT comenta una PQRS de su unidad")
    void commentsOwnPqrs() throws Exception {
        as(a.owner(), a, json(post("/api/v1/pqrs/{id}/comments", ownPqrs.getId()), "{\"comment\":\"Sigue goteando\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timeline[?(@.comment == 'Sigue goteando')]").exists());
    }

    @Test
    @DisplayName("RESIDENT no puede cambiar el estado de una PQRS con un comentario (gestion); el estado no cambia")
    void residentCannotChangeStatus() throws Exception {
        as(a.owner(), a, json(post("/api/v1/pqrs/{id}/comments", ownPqrs.getId()),
                "{\"comment\":\"Ya esta\",\"changeStatusTo\":\"RESOLVED\"}"))
                .andExpect(status().isForbidden());
        assertThat(pqrsRepository.findById(ownPqrs.getId()).orElseThrow().getStatus()).isEqualTo(PqrsRequestStatus.NEW);
    }

    @Test
    @DisplayName("RESIDENT no comenta la PQRS del vecino ni la de otra comunidad (404)")
    void cannotCommentOthers() throws Exception {
        as(a.owner(), a, json(post("/api/v1/pqrs/{id}/comments", neighbourPqrs.getId()), "{\"comment\":\"Hola\"}"))
                .andExpect(status().isNotFound());
        as(a.owner(), a, json(post("/api/v1/pqrs/{id}/comments", foreignPqrs.getId()), "{\"comment\":\"Hola\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("COMMUNITY_ADMIN cambia el estado con un comentario")
    void adminChangesStatus() throws Exception {
        as(a.admin(), a, json(post("/api/v1/pqrs/{id}/comments", neighbourPqrs.getId()),
                "{\"comment\":\"En revision\",\"changeStatusTo\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
    }

    @Test
    @DisplayName("assignTechnician: un usuario de otra comunidad responde 404 y no se revela su nombre")
    void technicianMustBelongToTenant() throws Exception {
        as(a.admin(), a, post("/api/v1/pqrs/{id}/assign/{tech}", neighbourPqrs.getId(), b.guard().getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").doesNotExist());
        as(a.admin(), a, post("/api/v1/pqrs/{id}/assign/{tech}", neighbourPqrs.getId(), a.guard().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignedToUserId").value(a.guard().getId().toString()));
    }

    @Test
    @DisplayName("RESIDENT no puede usar las operaciones de gestion (PUT, DELETE, assign, resolve, listado global)")
    void residentCannotManage() throws Exception {
        as(a.owner(), a, get("/api/v1/pqrs")).andExpect(status().isForbidden());
        as(a.owner(), a, json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/pqrs/{id}", ownPqrs.getId()),
                "{\"status\":\"CLOSED\"}")).andExpect(status().isForbidden());
        as(a.owner(), a, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/pqrs/{id}", ownPqrs.getId()))
                .andExpect(status().isForbidden());
        as(a.owner(), a, post("/api/v1/pqrs/{id}/resolve", ownPqrs.getId())).andExpect(status().isForbidden());
        as(a.owner(), a, post("/api/v1/pqrs/{id}/assign/{tech}", ownPqrs.getId(), a.owner().getId())).andExpect(status().isForbidden());
        assertThat(pqrsRepository.findById(ownPqrs.getId())).isPresent();
    }
}
