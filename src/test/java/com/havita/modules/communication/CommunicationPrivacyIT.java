package com.havita.modules.communication;

import com.havita.modules.communication.domain.Communication;
import com.havita.modules.communication.domain.CommunicationStatus;
import com.havita.modules.communication.infrastructure.CommunicationRepository;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-6 (comunicaciones): la lectura por id aplica las mismas reglas que el listado. Solo la gestion
 * (SUPER_ADMIN, COMMUNITY_ADMIN) ve borradores y archivadas; un residente solo las publicadas para su audiencia.
 */
class CommunicationPrivacyIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private CommunicationRepository communicationRepository;
    @Autowired private MembershipRepository membershipRepository;

    private Side a;
    private Side b;
    private Communication published;
    private Communication draft;
    private Communication archived;
    private Communication forTenants;
    private Communication forOtherTower;
    private Communication otherCommunity;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        published = comm(a, "Corte de agua", "Todos", CommunicationStatus.PUBLISHED);
        draft = comm(a, "Borrador: sancion a un residente", "Todos", CommunicationStatus.DRAFT);
        archived = comm(a, "Archivado", "Todos", CommunicationStatus.ARCHIVED);
        forTenants = comm(a, "Solo arrendatarios", "Arrendatarios", CommunicationStatus.PUBLISHED);
        forOtherTower = comm(a, "Solo Torre 9", "Torre 9", CommunicationStatus.PUBLISHED);
        otherCommunity = comm(b, "Comunicado de otra comunidad", "Todos", CommunicationStatus.PUBLISHED);
    }

    private Communication comm(Side side, String title, String audience, CommunicationStatus status) {
        Communication c = Communication.builder().authorName("Administracion").title(title).content(title + " (texto)")
                .audience(audience).status(status).build();
        c.setTenantId(side.tenantId());
        return communicationRepository.save(c);
    }

    private ResultActions getAs(User user, Side side, String path, Object... vars) throws Exception {
        return mvc.perform(get(path, vars)
                .header("Authorization", fixture.bearer(user))
                .header("X-Tenant-ID", side.tenantId().toString()));
    }

    @Test
    @DisplayName("RESIDENT lee por id una comunicacion publicada para todos")
    void residentReadsPublished() throws Exception {
        getAs(a.owner(), a, "/api/v1/communications/{id}", published.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Corte de agua"));
    }

    @Test
    @DisplayName("RESIDENT no lee por id borradores, archivadas ni comunicaciones de otra audiencia (404)")
    void residentCannotReadHidden() throws Exception {
        getAs(a.owner(), a, "/api/v1/communications/{id}", draft.getId()).andExpect(status().isNotFound());
        getAs(a.owner(), a, "/api/v1/communications/{id}", archived.getId()).andExpect(status().isNotFound());
        getAs(a.owner(), a, "/api/v1/communications/{id}", forTenants.getId()).andExpect(status().isNotFound());
        getAs(a.owner(), a, "/api/v1/communications/{id}", forOtherTower.getId()).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Comunicacion de otra comunidad o inventada: 404")
    void otherTenantOrUnknown() throws Exception {
        getAs(a.owner(), a, "/api/v1/communications/{id}", otherCommunity.getId()).andExpect(status().isNotFound());
        getAs(a.admin(), a, "/api/v1/communications/{id}", otherCommunity.getId()).andExpect(status().isNotFound());
        getAs(a.owner(), a, "/api/v1/communications/{id}", UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("El listado del residente solo contiene lo publicado para su audiencia")
    void residentListIsFiltered() throws Exception {
        getAs(a.owner(), a, "/api/v1/communications")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id").value(containsInAnyOrder(published.getId().toString())));
    }

    @Test
    @DisplayName("SECURITY solo ve comunicaciones publicadas (no gestiona borradores)")
    void securitySeesOnlyPublished() throws Exception {
        getAs(a.guard(), a, "/api/v1/communications/{id}", draft.getId()).andExpect(status().isNotFound());
        getAs(a.guard(), a, "/api/v1/communications/{id}", forTenants.getId()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("COMMUNITY_ADMIN gestiona borradores y archivadas")
    void adminSeesEverything() throws Exception {
        getAs(a.admin(), a, "/api/v1/communications/{id}", draft.getId()).andExpect(status().isOk());
        getAs(a.admin(), a, "/api/v1/communications/{id}", archived.getId()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Con la membresia INACTIVE el residente deja de leer comunicaciones aunque su JWT siga vigente")
    void revokedMembership() throws Exception {
        // A-8: con la unica relacion o la membresia revocadas el JWT ya no autentica en esta comunidad
        // (ResidentAccessGate): la denegacion llega como 401 antes que el 403/404 del endpoint.
        String token = fixture.bearer(a.owner());
        var m = membershipRepository.findByUserIdAndTenantId(a.owner().getId(), a.tenantId()).orElseThrow();
        m.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(m);
        mvc.perform(get("/api/v1/communications/{id}", published.getId())
                        .header("Authorization", token).header("X-Tenant-ID", a.tenantId().toString()))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(404))));
    }

    @Test
    @DisplayName("X-Tenant-ID de otra comunidad no autentica la peticion")
    void forgedTenantHeader() throws Exception {
        int s = getAs(a.owner(), b, "/api/v1/communications/{id}", otherCommunity.getId()).andReturn().getResponse().getStatus();
        assertThat(s).isIn(401, 403);
    }

    @org.springframework.beans.factory.annotation.Autowired private com.havita.modules.property.infrastructure.StructureRepository structureRepository;
    @org.springframework.beans.factory.annotation.Autowired private com.havita.modules.property.infrastructure.PropertyRepository propertyRepository;

    @Test
    @DisplayName("Fase 2 / E3 · audiencia por torre: comparación exacta ('Torre 1' no alcanza a 'Torre 10')")
    void towerAudienceIsExact() throws Exception {

        com.havita.modules.property.domain.Structure t10 = com.havita.modules.property.domain.Structure.builder()
                .name("Torre 10").type("Torre").build();
        t10.setTenantId(a.tenantId());
        t10 = structureRepository.save(t10);
        com.havita.modules.property.domain.Property home = propertyRepository.findById(a.home().getId()).orElseThrow();
        home.setStructure(t10);
        home.setPath("Torre 10 -> Piso 1 -> Apto 101");
        propertyRepository.save(home);

        Communication torre1 = comm(a, "Solo Torre 1", "Torre 1", CommunicationStatus.PUBLISHED);
        Communication torre10 = comm(a, "Solo Torre 10", "  torre 10 ", CommunicationStatus.PUBLISHED);
        Communication unit = comm(a, "Solo su unidad", "Torre 10 -> Piso 1 -> Apto 101", CommunicationStatus.PUBLISHED);
        Communication otherUnit = comm(a, "Otra unidad", "Torre 10 -> Piso 1 -> Apto 1010", CommunicationStatus.PUBLISHED);

        getAs(a.owner(), a, "/api/v1/communications/{id}", torre1.getId()).andExpect(status().isNotFound());
        getAs(a.owner(), a, "/api/v1/communications/{id}", torre10.getId()).andExpect(status().isOk());
        getAs(a.owner(), a, "/api/v1/communications/{id}", unit.getId()).andExpect(status().isOk());
        getAs(a.owner(), a, "/api/v1/communications/{id}", otherUnit.getId()).andExpect(status().isNotFound());
    }
}
