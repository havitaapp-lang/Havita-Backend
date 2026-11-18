package com.havita.modules.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.assembly.domain.Assembly;
import com.havita.modules.assembly.domain.AssemblyAttendee;
import com.havita.modules.assembly.domain.AssemblyStatus;
import com.havita.modules.assembly.domain.Poll;
import com.havita.modules.assembly.domain.PollOption;
import com.havita.modules.assembly.domain.PollStatus;
import com.havita.modules.assembly.infrastructure.AssemblyAttendeeRepository;
import com.havita.modules.assembly.infrastructure.AssemblyRepository;
import com.havita.modules.assembly.infrastructure.PollRepository;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.intercom.application.IntercomService;
import com.havita.modules.intercom.domain.IntercomCall;
import com.havita.modules.intercom.domain.IntercomCallStatus;
import com.havita.modules.intercom.infrastructure.IntercomCallRepository;
import com.havita.modules.resident.domain.Resident;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-6: destinatarios de los eventos SSE de visitantes, panico, citofonia, revocaciones, votaciones y
 * comunicaciones. Se abre el stream real y se provocan los eventos con la API (o con el job de citofonia).
 */
class SseEventPolicyIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private IntercomCallRepository intercomCallRepository;
    @Autowired private IntercomService intercomService;
    @Autowired private AssemblyRepository assemblyRepository;
    @Autowired private AssemblyAttendeeRepository attendeeRepository;
    @Autowired private PollRepository pollRepository;
    @Autowired private JdbcTemplate jdbc;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
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

    private static String received(MvcResult stream) throws Exception {
        return stream.getResponse().getContentAsString();
    }

    /** Datos JSON de cada evento de un tipo recibido en el stream. */
    private List<com.fasterxml.jackson.databind.JsonNode> events(MvcResult stream, String type) throws Exception {
        List<com.fasterxml.jackson.databind.JsonNode> out = new ArrayList<>();
        for (String line : received(stream).split("\n")) {
            if (line.startsWith("data:") && line.contains("\"type\":\"" + type + "\"")) {
                out.add(objectMapper.readTree(line.substring("data:".length())));
            }
        }
        return out;
    }

    private org.springframework.test.web.servlet.ResultActions as(User user, Side side,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(user)).header("X-Tenant-ID", side.tenantId().toString()));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    @DisplayName("Visitante de la unidad del vecino (con documento y QR): lo reciben el vecino y la gestion, no otros residentes")
    void visitorEventsAreUnitScoped() throws Exception {
        MvcResult owner = open(a.owner(), a);
        MvcResult neighbour = open(a.neighbour(), a);
        MvcResult admin = open(a.admin(), a);
        MvcResult otherTenant = open(b.admin(), b);

        as(a.guard(), a, json(post("/api/v1/access/visitors"),
                "{\"propertyId\":\"%s\",\"name\":\"Visita Del Vecino\",\"documentId\":\"CC-7788\",\"vehiclePlate\":\"XYZ987\"}"
                        .formatted(a.neighbourHome().getId())))
                .andExpect(status().isCreated());

        assertThat(received(neighbour)).contains("VISITOR_CREATED").contains("CC-7788");
        assertThat(received(admin)).contains("VISITOR_CREATED");
        assertThat(received(owner)).doesNotContain("VISITOR_CREATED").doesNotContain("CC-7788").doesNotContain("XYZ987");
        assertThat(received(otherTenant)).doesNotContain("VISITOR_CREATED");
    }

    @Test
    @DisplayName("Panico del vecino: lo reciben porteria, la administracion y su unidad; no otros residentes")
    void panicEventsReachStaffAndUnit() throws Exception {
        MvcResult owner = open(a.owner(), a);
        MvcResult neighbour = open(a.neighbour(), a);
        MvcResult guard = open(a.guard(), a);

        as(a.neighbour(), a, json(post("/api/v1/realtime/panic"), "{\"alertType\":\"MEDICAL\",\"notes\":\"Dolor en el pecho\"}"))
                .andExpect(status().isCreated());

        assertThat(received(guard)).contains("PANIC_ALERT").contains("Dolor en el pecho");
        assertThat(received(neighbour)).contains("PANIC_ALERT");
        assertThat(received(owner)).doesNotContain("PANIC_ALERT").doesNotContain("Dolor en el pecho");
    }

    @Test
    @DisplayName("Citofonia: el evento de una llamada lo reciben porteria y el residente llamado, no otros residentes")
    void intercomEventsReachCallParticipants() throws Exception {
        IntercomCall call = IntercomCall.builder()
                .property(a.neighbourHome()).callerUser(a.guard()).callerName("Porteria")
                .recipientResident(a.neighbourResident()).recipientUser(a.neighbour()).recipientName("Vecino")
                .propertyUnit(a.neighbourHome().getNumber()).realtimeMeetingId("")
                .status(IntercomCallStatus.RINGING).ringingAt(OffsetDateTime.now().minusMinutes(5))
                .build();
        call.setTenantId(a.tenantId());
        call = intercomCallRepository.save(call);
        // El job marca como perdidas las llamadas que llevan mas de 45 s sonando.
        jdbc.update("update intercom_calls set created_at = now() - interval '5 minutes' where id = ?", call.getId());

        MvcResult owner = open(a.owner(), a);
        MvcResult neighbour = open(a.neighbour(), a);
        MvcResult guard = open(a.guard(), a);

        intercomService.handleRingTimeouts();

        assertThat(received(neighbour)).contains("INTERCOM_CALL_MISSED").contains(call.getId().toString());
        assertThat(received(guard)).contains("INTERCOM_CALL_MISSED");
        assertThat(received(owner)).doesNotContain("INTERCOM_CALL_MISSED");
    }

    @Test
    @DisplayName("Revocacion: sin correos; el afectado recibe su aviso, la unidad uno sin identificadores, el vecino nada")
    void revocationEventsCarryNoEmails() throws Exception {
        User relative = userRepository.save(User.builder()
                .email("familiar-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .passwordHash("{noop}not-used").fullName("Familiar").phone("3000000000").build());
        membershipRepository.save(Membership.builder()
                .user(relative).tenantId(a.tenantId()).role(RoleType.RESIDENT).status(MembershipStatus.ACTIVE).build());
        Resident rel = Resident.builder().property(a.home()).user(relative).name("Familiar").type("Residente")
                .email(relative.getEmail()).status(ResidentStatus.ACTIVE).build();
        rel.setTenantId(a.tenantId());
        rel = residentRepository.save(rel);

        MvcResult relativeStream = open(relative, a);
        MvcResult owner = open(a.owner(), a);
        MvcResult neighbour = open(a.neighbour(), a);
        MvcResult admin = open(a.admin(), a);

        as(a.owner(), a, delete("/api/v1/resident/me/people/{id}", rel.getId())).andExpect(status().isOk());

        var mine = events(relativeStream, "RESIDENT_ACCESS_REVOKED");
        assertThat(mine).isNotEmpty();
        assertThat(mine.get(0).get("payload").get("affectedUserIds").toString()).contains(relative.getId().toString());

        var unitVersion = events(owner, "RESIDENT_ACCESS_REVOKED");
        assertThat(unitVersion).isNotEmpty();
        assertThat(unitVersion.get(0).get("payload").has("affectedUserIds")).isFalse();
        assertThat(received(admin)).contains("RESIDENT_ACCESS_REVOKED");

        assertThat(received(neighbour)).doesNotContain("RESIDENT_ACCESS_REVOKED");
        for (MvcResult s : List.of(relativeStream, owner, neighbour, admin)) {
            assertThat(received(s)).doesNotContain("affectedEmails").doesNotContain(relative.getEmail());
        }
    }

    @Test
    @DisplayName("Votaciones: un borrador nuevo solo llega a la gestion; con resultados ocultos el residente no los recibe")
    void pollEventsRespectDraftAndHiddenResults() throws Exception {
        Assembly asm = Assembly.builder().title("Asamblea").assemblyDate(LocalDate.now()).startTime(LocalTime.NOON)
                .status(AssemblyStatus.IN_PROGRESS).build();
        asm.setTenantId(a.tenantId());
        asm = assemblyRepository.save(asm);
        AssemblyAttendee at = AssemblyAttendee.builder().assembly(asm).property(a.neighbourHome())
                .resident(a.neighbourResident()).coefficient(new BigDecimal("0.100000")).build();
        at.setTenantId(a.tenantId());
        attendeeRepository.save(at);
        Poll hidden = Poll.builder().assembly(asm).question("Presupuesto").status(PollStatus.LIVE)
                .showResultsToResidents(false).options(new ArrayList<>()).build();
        hidden.setTenantId(a.tenantId());
        hidden.getOptions().add(PollOption.builder().poll(hidden).text("Si").optionOrder(1).build());
        hidden = pollRepository.save(hidden);

        MvcResult owner = open(a.owner(), a);
        MvcResult admin = open(a.admin(), a);

        as(a.admin(), a, json(post("/api/v1/assemblies/polls"),
                "{\"assemblyId\":\"%s\",\"question\":\"Borrador secreto\",\"options\":[\"A\",\"B\"]}".formatted(asm.getId())))
                .andExpect(status().isCreated());
        // Cierre E5 / D14: vota el representante registrado de la unidad (la administración ya no emite votos).
        as(a.neighbour(), a, json(post("/api/v1/assemblies/polls/{id}/vote", hidden.getId()),
                "{\"propertyId\":\"%s\",\"optionIds\":[\"%s\"]}".formatted(a.neighbourHome().getId(), hidden.getOptions().get(0).getId())))
                .andExpect(status().isOk());

        assertThat(received(admin)).contains("POLL_CREATED").contains("Borrador secreto");
        assertThat(received(owner)).doesNotContain("POLL_CREATED").doesNotContain("Borrador secreto");

        var ownerUpdates = events(owner, "POLL_UPDATED");
        assertThat(ownerUpdates).isNotEmpty();
        assertThat(ownerUpdates.get(0).get("payload").get("options").get(0).get("votesCount").asLong()).isZero();
        var adminUpdates = events(admin, "POLL_UPDATED");
        assertThat(adminUpdates).isNotEmpty();
        assertThat(adminUpdates.get(adminUpdates.size() - 1).get("payload").get("options").get(0).get("votesCount").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("Comunicacion dirigida: el evento solo lleva el id (el contenido se lee aplicando la audiencia)")
    void communicationEventCarriesOnlyTheId() throws Exception {
        MvcResult owner = open(a.owner(), a);
        as(a.admin(), a, json(post("/api/v1/communications"),
                "{\"title\":\"Aviso solo para arrendatarios\",\"content\":\"Texto\",\"audience\":\"Arrendatarios\",\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isCreated());
        var ev = events(owner, "COMMUNICATION_CREATED");
        assertThat(ev).isNotEmpty();
        assertThat(received(owner)).doesNotContain("Aviso solo para arrendatarios");
        String id = ev.get(0).get("payload").get("id").asText();
        as(a.owner(), a, get("/api/v1/communications/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("WebSocket /ws deshabilitado: el endpoint STOMP/SockJS no existe ni autenticado")
    void webSocketEndpointIsDisabled() throws Exception {
        int s = as(a.admin(), a, get("/ws/info")).andReturn().getResponse().getStatus();
        assertThat(s).isEqualTo(404);
    }
}
