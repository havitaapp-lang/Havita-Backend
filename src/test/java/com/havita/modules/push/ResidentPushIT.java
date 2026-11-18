package com.havita.modules.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.assembly.domain.Assembly;
import com.havita.modules.assembly.infrastructure.AssemblyRepository;
import com.havita.modules.identity.domain.User;
import com.havita.modules.push.application.ResidentPushRequested;
import com.havita.modules.push.domain.PushSubscription;
import com.havita.modules.push.infrastructure.PushSubscriptionRepository;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Avisos push a residentes: paquete recibido o recordado, ingreso de una visita, comunicado publicado y asamblea
 * iniciada. Se comprueba que cada operacion pide el aviso con el alcance correcto (unidad o comunidad) y texto fijo
 * sin datos personales, y que los destinatarios son solo residentes activos (ni porteria, ni administracion, ni otra
 * comunidad). Datos sinteticos.
 */
@RecordApplicationEvents
class ResidentPushIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PushSubscriptionRepository subscriptions;
    @Autowired private AssemblyRepository assemblies;
    @Autowired private ApplicationEvents events;

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

    private String idOf(ResultActions r) throws Exception {
        return objectMapper.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data").get("id").asText();
    }

    private List<ResidentPushRequested> pushes() {
        return events.stream(ResidentPushRequested.class).toList();
    }

    @Test
    @DisplayName("Paquete recibido y recordado: aviso a los residentes de ESA unidad, sin datos del paquete")
    void packagePush() throws Exception {
        String id = idOf(as(a.guard(), a, post("/api/v1/access/packages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"%s\",\"recipientName\":\"Destinatario Sintetico\",\"company\":\"Servientrega\"}"
                        .formatted(a.home().getId()))).andExpect(status().isCreated()));
        assertThat(pushes()).singleElement().satisfies(p -> {
            assertThat(p.scope()).isEqualTo(ResidentPushRequested.Scope.UNIT);
            assertThat(p.propertyId()).isEqualTo(a.home().getId());
            assertThat(p.tenantId()).isEqualTo(a.tenantId());
            assertThat(p.body()).isEqualTo("Tienes un paquete en portería").doesNotContain("Destinatario").doesNotContain("Servientrega");
            assertThat(p.url()).isEqualTo("/resident/packages");
        });

        events.clear();
        as(a.guard(), a, post("/api/v1/access/packages/%s/notify".formatted(id))).andExpect(status().isOk());
        assertThat(pushes()).singleElement().satisfies(p -> assertThat(p.propertyId()).isEqualTo(a.home().getId()));
    }

    @Test
    @DisplayName("Ingreso de una visita: aviso a los residentes de ESA unidad, sin el nombre del visitante")
    void visitorEntryPush() throws Exception {
        String id = idOf(as(a.guard(), a, post("/api/v1/access/visitors").contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"%s\",\"name\":\"Visitante Sintetico\",\"documentId\":\"CC-1234\"}"
                        .formatted(a.neighbourHome().getId()))).andExpect(status().isCreated()));
        events.clear();
        as(a.guard(), a, post("/api/v1/access/visitors/%s/entry".formatted(id))).andExpect(status().isOk());
        assertThat(pushes()).singleElement().satisfies(p -> {
            assertThat(p.scope()).isEqualTo(ResidentPushRequested.Scope.UNIT);
            assertThat(p.propertyId()).isEqualTo(a.neighbourHome().getId());
            assertThat(p.body()).isEqualTo("Tu visita ingresó al conjunto").doesNotContain("Visitante");
        });
    }

    @Test
    @DisplayName("Comunicado: aviso a los residentes de la comunidad al publicarlo (no los borradores); audiencia respetada")
    void communicationPush() throws Exception {
        String draft = idOf(as(a.admin(), a, post("/api/v1/communications").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Corte de agua\",\"content\":\"Mañana\",\"audience\":\"Propietarios\",\"status\":\"DRAFT\"}"))
                .andExpect(status().isCreated()));
        assertThat(pushes()).isEmpty();

        as(a.admin(), a, post("/api/v1/communications/%s/publish".formatted(draft))).andExpect(status().isOk());
        assertThat(pushes()).singleElement().satisfies(p -> {
            assertThat(p.scope()).isEqualTo(ResidentPushRequested.Scope.COMMUNITY);
            assertThat(p.tenantId()).isEqualTo(a.tenantId());
            assertThat(p.residentType()).isEqualTo("Propietario");
            assertThat(p.body()).doesNotContain("Corte de agua");
        });

        events.clear();
        as(a.admin(), a, post("/api/v1/communications").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Asamblea\",\"content\":\"Convocatoria\",\"audience\":\"Todos\",\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isCreated());
        assertThat(pushes()).singleElement().satisfies(p -> assertThat(p.residentType()).isNull());
    }

    @Test
    @DisplayName("Asamblea iniciada: aviso a los residentes de la comunidad")
    void assemblyStartPush() throws Exception {
        Assembly assembly = Assembly.builder().title("Asamblea sintetica").assemblyDate(LocalDate.now())
                .startTime(LocalTime.of(10, 0)).build();
        assembly.setTenantId(a.tenantId());
        assembly = assemblies.save(assembly);
        as(a.admin(), a, post("/api/v1/assemblies/%s/start".formatted(assembly.getId()))).andExpect(status().isOk());
        assertThat(pushes()).singleElement().satisfies(p -> {
            assertThat(p.scope()).isEqualTo(ResidentPushRequested.Scope.COMMUNITY);
            assertThat(p.residentType()).isNull();
            assertThat(p.type()).isEqualTo("ASSEMBLY_STARTED");
        });
    }

    @Test
    @DisplayName("Destinatarios de la comunidad: solo residentes activos de ESA comunidad (ni porteria, ni administracion, ni otra comunidad)")
    void communityRecipients() {
        for (User u : List.of(a.owner(), a.neighbour(), a.guard(), a.admin())) subscribe(u, a);
        subscribe(b.owner(), b);

        List<UUID> all = subscriptions.findActiveForCommunityResidents(a.tenantId(), ResidentStatus.ACTIVE, "").stream()
                .map(PushSubscription::getUserId).toList();
        assertThat(all).containsExactlyInAnyOrder(a.owner().getId(), a.neighbour().getId());

        assertThat(subscriptions.findActiveForCommunityResidents(a.tenantId(), ResidentStatus.ACTIVE, "propietario"))
                .extracting(PushSubscription::getUserId).containsExactlyInAnyOrder(a.owner().getId(), a.neighbour().getId());
        assertThat(subscriptions.findActiveForCommunityResidents(a.tenantId(), ResidentStatus.ACTIVE, "arrendatario")).isEmpty();
    }

    private void subscribe(User u, Side side) {
        PushSubscription s = PushSubscription.builder().userId(u.getId()).endpoint("https://push.example.test/" + UUID.randomUUID())
                .p256dh("p256dh-sintetico").auth("auth-sintetico").deviceName("prueba").build();
        s.setTenantId(side.tenantId());
        subscriptions.save(s);
    }
}
