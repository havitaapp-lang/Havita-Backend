package com.havita.modules.resident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.access.domain.Visitor;
import com.havita.modules.access.infrastructure.VisitorRepository;
import com.havita.modules.accounting.domain.PropertyInvoice;
import com.havita.modules.accounting.domain.PropertyInvoiceStatus;
import com.havita.modules.accounting.infrastructure.PropertyInvoiceRepository;
import com.havita.modules.assembly.domain.Assembly;
import com.havita.modules.assembly.domain.AssemblyAttendee;
import com.havita.modules.assembly.domain.AssemblyStatus;
import com.havita.modules.assembly.domain.Poll;
import com.havita.modules.assembly.domain.PollOption;
import com.havita.modules.assembly.domain.PollStatus;
import com.havita.modules.assembly.domain.PollVote;
import com.havita.modules.assembly.infrastructure.AssemblyAttendeeRepository;
import com.havita.modules.assembly.infrastructure.AssemblyRepository;
import com.havita.modules.assembly.infrastructure.PollRepository;
import com.havita.modules.assembly.infrastructure.PollVoteRepository;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.intercom.domain.IntercomCall;
import com.havita.modules.intercom.domain.IntercomCallStatus;
import com.havita.modules.intercom.infrastructure.IntercomCallRepository;
import com.havita.modules.pqrs.domain.PqrsRequest;
import com.havita.modules.pqrs.domain.PqrsRequestStatus;
import com.havita.modules.pqrs.infrastructure.PqrsRequestRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.realtime.domain.AlertType;
import com.havita.modules.realtime.domain.PanicAlert;
import com.havita.modules.realtime.infrastructure.PanicAlertRepository;
import com.havita.modules.resident.domain.Pet;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.domain.Vehicle;
import com.havita.modules.resident.infrastructure.PetRepository;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.modules.resident.infrastructure.VehicleRepository;
import com.havita.modules.reservation.domain.Reservation;
import com.havita.modules.reservation.infrastructure.ReservationRepository;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import com.havita.support.UnitDataFixture;
import com.havita.support.UnitDataFixture.UnitData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A-8: el boton Eliminar del Directorio (DELETE /residents/{id}) da de baja la relacion de una persona con una
 * unidad (baja logica). El historial de la comunidad se conserva, el acceso se corta aunque el JWT siga vigente y
 * las demas unidades y comunidades del usuario no se tocan.
 */
class ResidentRemovalIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private UnitDataFixture unitData;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private PqrsRequestRepository pqrsRepository;
    @Autowired private PropertyInvoiceRepository invoiceRepository;
    @Autowired private AssemblyRepository assemblyRepository;
    @Autowired private AssemblyAttendeeRepository attendeeRepository;
    @Autowired private PollRepository pollRepository;
    @Autowired private PollVoteRepository voteRepository;
    @Autowired private IntercomCallRepository intercomCallRepository;
    @Autowired private PanicAlertRepository panicAlertRepository;
    @Autowired private VisitorRepository visitorRepository;
    @Autowired private VehicleRepository vehicleRepository;
    @Autowired private PetRepository petRepository;
    @Autowired private com.havita.modules.property.infrastructure.PropertyRepository propertyRepository;

    private Side a;
    private Side b;
    private UnitData da;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        da = unitData.seed(a);
    }

    private ResultActions as(User user, Side side, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", fixture.bearer(user)).header("X-Tenant-ID", side.tenantId().toString()));
    }

    private ResultActions withToken(String token, Side side, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", token).header("X-Tenant-ID", side.tenantId().toString()));
    }

    private Resident reload(Resident r) {
        return residentRepository.findById(r.getId()).orElseThrow();
    }

    private Resident relation(Side side, Property property, User user, String type) {
        Resident r = Resident.builder().property(property).user(user).name("Relacion " + type).type(type)
                .email(user.getEmail()).status(ResidentStatus.ACTIVE).build();
        r.setTenantId(side.tenantId());
        return residentRepository.save(r);
    }

    private MvcResult openStream(User user, Side side) throws Exception {
        String body = as(user, side, post("/api/v1/sync/stream-ticket")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String ticket = objectMapper.readTree(body).get("ticket").asText();
        return mvc.perform(get("/api/v1/sync/stream").param("ticket", ticket)).andExpect(request().asyncStarted()).andReturn();
    }

    // ── Baja sin y con historial ───────────────────────────────────────────────

    @Test
    @DisplayName("Baja de un residente sin historial: la fila queda INACTIVE con autor, fecha y motivo (no se borra)")
    void removalWithoutHistory() throws Exception {
        as(a.admin(), a, delete("/api/v1/residents/{id}", a.censusOnly().getId())).andExpect(status().isOk());
        Resident r = reload(a.censusOnly());
        assertThat(r.getStatus()).isEqualTo(ResidentStatus.INACTIVE);
        assertThat(r.getEndReason()).isEqualTo("ADMIN_REMOVAL");
        assertThat(r.getEndedAt()).isNotNull();
        assertThat(r.getEndedBy().getId()).isEqualTo(a.admin().getId());
    }

    @Test
    @DisplayName("Baja de un residente con historial: sin HTTP 500 y las 11 tablas siguen apuntando al registro")
    void removalWithHistoryKeepsEverything() throws Exception {
        Resident owner = a.ownerResident();
        Property home = a.home();
        UUID t = a.tenantId();

        PqrsRequest pqrs = PqrsRequest.builder().property(home).resident(owner).createdByUser(a.owner()).title("Gotera")
                .description("Gotera en el bano").category("Mantenimiento").priority("Media").status(PqrsRequestStatus.NEW).build();
        pqrs.setTenantId(t);
        pqrs = pqrsRepository.save(pqrs);
        PropertyInvoice invoice = PropertyInvoice.builder().property(home).resident(owner).invoiceNumber("INV-H-" + UUID.randomUUID())
                .period("Marzo 2027").baseAmount(new BigDecimal("100000")).totalAmount(new BigDecimal("100000"))
                .balanceDue(BigDecimal.ZERO).status(PropertyInvoiceStatus.PAID).dueDate(LocalDate.now()).build();
        invoice.setTenantId(t);
        invoice = invoiceRepository.save(invoice);
        Assembly asm = Assembly.builder().title("Asamblea").assemblyDate(LocalDate.now()).startTime(LocalTime.NOON)
                .status(AssemblyStatus.ENDED).build();
        asm.setTenantId(t);
        asm = assemblyRepository.save(asm);
        AssemblyAttendee at = AssemblyAttendee.builder().assembly(asm).property(home).resident(owner).coefficient(new BigDecimal("0.1")).build();
        at.setTenantId(t);
        attendeeRepository.save(at);
        Poll poll = Poll.builder().assembly(asm).question("Pintar fachada").status(PollStatus.CLOSED).options(new ArrayList<>()).build();
        poll.setTenantId(t);
        poll.getOptions().add(PollOption.builder().poll(poll).text("Si").optionOrder(1).build());
        poll = pollRepository.save(poll);
        PollVote vote = PollVote.builder().poll(poll).pollOption(poll.getOptions().get(0)).property(home).resident(owner)
                .coefficient(new BigDecimal("0.1")).build();
        vote.setTenantId(t);
        voteRepository.save(vote);
        IntercomCall call = IntercomCall.builder().property(home).callerUser(a.guard()).callerName("Porteria")
                .recipientResident(owner).recipientUser(a.owner()).recipientName("Propietario").propertyUnit(home.getNumber())
                .realtimeMeetingId("").status(IntercomCallStatus.ENDED).ringingAt(OffsetDateTime.now()).build();
        call.setTenantId(t);
        intercomCallRepository.save(call);
        PanicAlert alert = PanicAlert.builder().property(home).resident(owner).alertType(AlertType.MEDICAL).build();
        alert.setTenantId(t);
        panicAlertRepository.save(alert);
        Visitor visitor = Visitor.builder().property(home).resident(owner).name("Visita").documentId("CC-1").qrCode("QR-" + UUID.randomUUID()).build();
        visitor.setTenantId(t);
        visitorRepository.save(visitor);
        Vehicle vehicle = Vehicle.builder().property(home).resident(owner).plate("ABC123").build();
        vehicle.setTenantId(t);
        vehicleRepository.save(vehicle);
        Pet pet = Pet.builder().property(home).resident(owner).name("Firulais").build();
        pet.setTenantId(t);
        petRepository.save(pet);

        as(a.admin(), a, delete("/api/v1/residents/{id}", owner.getId())).andExpect(status().isOk());

        assertThat(reload(owner).getStatus()).isEqualTo(ResidentStatus.INACTIVE);
        assertThat(reservationRepository.findById(da.ownReservation().getId()).orElseThrow().getResident().getId()).isEqualTo(owner.getId());
        assertThat(pqrsRepository.findById(pqrs.getId()).orElseThrow().getResident().getId()).isEqualTo(owner.getId());
        assertThat(invoiceRepository.findById(invoice.getId()).orElseThrow().getResident().getId()).isEqualTo(owner.getId());
        assertThat(voteRepository.countByPollId(poll.getId())).isEqualTo(1);
        assertThat(attendeeRepository.countByAssemblyId(asm.getId())).isEqualTo(1);
        assertThat(intercomCallRepository.findAll()).anyMatch(c -> c.getRecipientResident().getId().equals(owner.getId()));
        assertThat(panicAlertRepository.findAll()).anyMatch(p -> p.getResident() != null && p.getResident().getId().equals(owner.getId()));
        assertThat(visitorRepository.findAll()).anyMatch(v -> v.getResident() != null && v.getResident().getId().equals(owner.getId()));
        assertThat(vehicleRepository.findAll()).anyMatch(v -> v.getResident() != null && v.getResident().getId().equals(owner.getId()));
        assertThat(petRepository.findAll()).anyMatch(p -> p.getResident() != null && p.getResident().getId().equals(owner.getId()));

        // El historial sigue siendo consultable por la administracion y los reportes funcionan.
        as(a.admin(), a, get("/api/v1/accounting/invoices/by-property/{id}", home.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '%s')].residentId", invoice.getId()).value(org.hamcrest.Matchers.hasItem(owner.getId().toString())));
        as(a.admin(), a, get("/api/v1/pqrs/{id}", pqrs.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.residentId").value(owner.getId().toString()));
        as(a.admin(), a, get("/api/v1/assemblies/polls/{id}", poll.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalVotes").value(1));
        as(a.admin(), a, get("/api/v1/reservations/{id}", da.ownReservation().getId())).andExpect(status().isOk());
        as(a.admin(), a, get("/api/v1/reporting/properties/{id}/paz-y-salvo", home.getId())).andExpect(status().isOk());
        String csv = as(a.admin(), a, get("/api/v1/reporting/residents/census/csv")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("INACTIVE");
        // La reserva futura no se cancela automaticamente (decision pendiente, ver documento).
        assertThat(reservationRepository.findById(da.ownReservation().getId()).orElseThrow().getStatus().name()).isEqualTo("PENDING");
    }

    // ── Alcance: varias unidades y varias comunidades ──────────────────────────

    @Test
    @DisplayName("Residente con dos unidades: pierde solo la unidad dada de baja y conserva la otra y la comunidad")
    void removalKeepsOtherUnits() throws Exception {
        Resident second = relation(a, a.vacant(), a.owner(), "Propietario");
        String token = fixture.bearer(a.owner());
        MvcResult stream = openStream(a.owner(), a);

        as(a.admin(), a, delete("/api/v1/residents/{id}", a.ownerResident().getId())).andExpect(status().isOk());

        withToken(token, a, get("/api/v1/residents/by-property/{id}", a.home().getId())).andExpect(status().isForbidden());
        withToken(token, a, get("/api/v1/residents/by-property/{id}", a.vacant().getId())).andExpect(status().isOk());
        withToken(token, a, get("/api/v1/communications")).andExpect(status().isOk());
        assertThat(reload(second).getStatus()).isEqualTo(ResidentStatus.ACTIVE);

        // Recibe el aviso para refrescar, pero sin su id: el frontend no le cierra la sesion.
        String data = stream.getResponse().getContentAsString().lines()
                .filter(l -> l.startsWith("data:") && l.contains("RESIDENT_ACCESS_REVOKED") && l.contains("affectedUserIds"))
                .findFirst().orElseThrow();
        assertThat(data).doesNotContain(a.owner().getId().toString());
    }

    @Test
    @DisplayName("Ultima unidad dada de baja: el JWT anterior deja de servir en esa comunidad y sigue valiendo en la otra")
    void removalOfLastUnitKeepsOtherCommunity() throws Exception {
        membershipRepository.save(Membership.builder()
                .user(a.owner()).tenantId(b.tenantId()).role(RoleType.RESIDENT).status(MembershipStatus.ACTIVE).build());
        relation(b, b.vacant(), a.owner(), "Propietario");
        String token = fixture.bearer(a.owner());
        withToken(token, a, get("/api/v1/communications")).andExpect(status().isOk());

        as(a.admin(), a, delete("/api/v1/residents/{id}", a.ownerResident().getId())).andExpect(status().isOk());

        int inA = withToken(token, a, get("/api/v1/communications")).andReturn().getResponse().getStatus();
        assertThat(inA).isIn(401, 403);
        int meInA = withToken(token, a, get("/api/v1/resident/me/properties")).andReturn().getResponse().getStatus();
        assertThat(meInA).isIn(401, 403);
        withToken(token, b, get("/api/v1/communications")).andExpect(status().isOk());
        withToken(token, b, get("/api/v1/residents/by-property/{id}", b.vacant().getId())).andExpect(status().isOk());

        // No es una baja de la comunidad ni de la cuenta: las membresias no cambian.
        assertThat(membershipRepository.findByUserIdAndTenantId(a.owner().getId(), a.tenantId()).orElseThrow().getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(membershipRepository.findByUserIdAndTenantId(a.owner().getId(), b.tenantId()).orElseThrow().getStatus()).isEqualTo(MembershipStatus.ACTIVE);
        assertThat(userRepository.findById(a.owner().getId())).isPresent();
    }

    @Test
    @DisplayName("Conexion SSE abierta: el afectado recibe su aviso de revocacion y la conexion se cierra")
    void sseConnectionIsRevoked() throws Exception {
        MvcResult stream = openStream(a.owner(), a);
        as(a.admin(), a, delete("/api/v1/residents/{id}", a.ownerResident().getId())).andExpect(status().isOk());

        String data = stream.getResponse().getContentAsString().lines()
                .filter(l -> l.startsWith("data:") && l.contains("RESIDENT_ACCESS_REVOKED") && l.contains("affectedUserIds"))
                .findFirst().orElseThrow();
        assertThat(data).contains(a.owner().getId().toString());

        // El siguiente evento revalida la conexion (marcada por la revocacion) y la cierra: ya no recibe nada.
        as(a.guard(), a, post("/api/v1/access/packages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"%s\",\"recipientName\":\"Tras La Baja\"}".formatted(a.home().getId())))
                .andExpect(status().isCreated());
        assertThat(stream.getResponse().getContentAsString()).doesNotContain("Tras La Baja");
        int ticket = as(a.owner(), a, post("/api/v1/sync/stream-ticket")).andReturn().getResponse().getStatus();
        assertThat(ticket).isIn(401, 403);
    }

    // ── Seguridad e idempotencia ───────────────────────────────────────────────

    @Test
    @DisplayName("Un admin de otra comunidad no puede dar de baja (404) y un RESIDENT no puede (403)")
    void crossTenantAndRoleChecks() throws Exception {
        as(b.admin(), b, delete("/api/v1/residents/{id}", a.ownerResident().getId())).andExpect(status().isNotFound());
        as(a.neighbour(), a, delete("/api/v1/residents/{id}", a.ownerResident().getId())).andExpect(status().isForbidden());
        assertThat(reload(a.ownerResident()).getStatus()).isEqualTo(ResidentStatus.ACTIVE);
    }

    @Test
    @DisplayName("Baja repetida: responde 200 sin cambiar fecha, autor ni volver a publicar eventos")
    void repeatedRemovalHasNoSideEffects() throws Exception {
        MvcResult adminStream = openStream(a.admin(), a);
        as(a.admin(), a, delete("/api/v1/residents/{id}", a.censusOnly().getId())).andExpect(status().isOk());
        Resident first = reload(a.censusOnly());
        long events = adminStream.getResponse().getContentAsString().lines().filter(l -> l.startsWith("event:RESIDENT_ACCESS_REVOKED")).count();

        as(a.admin(), a, delete("/api/v1/residents/{id}", a.censusOnly().getId())).andExpect(status().isOk());
        Resident second = reload(a.censusOnly());
        assertThat(second.getEndedAt()).isEqualTo(first.getEndedAt());
        assertThat(adminStream.getResponse().getContentAsString().lines().filter(l -> l.startsWith("event:RESIDENT_ACCESS_REVOKED")).count())
                .isEqualTo(events);
    }

    // ── Reactivacion ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Reactivacion por la administracion: misma fila, solo esa unidad, acceso recuperado")
    void adminReactivation() throws Exception {
        Resident second = relation(a, a.vacant(), a.owner(), "Propietario");
        as(a.admin(), a, delete("/api/v1/residents/{id}", second.getId())).andExpect(status().isOk());
        as(a.admin(), a, delete("/api/v1/residents/{id}", a.ownerResident().getId())).andExpect(status().isOk());
        String token = fixture.bearer(a.owner());
        var unit = propertyRepository.findById(a.home().getId()).orElseThrow();
        unit.setStatus(com.havita.modules.property.domain.PropertyStatus.VACANT);
        propertyRepository.save(unit);

        as(a.admin(), a, put("/api/v1/residents/{id}", a.ownerResident().getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}")).andExpect(status().isOk());

        Resident back = reload(a.ownerResident());
        assertThat(back.getStatus()).isEqualTo(ResidentStatus.ACTIVE);
        assertThat(propertyRepository.findById(a.home().getId()).orElseThrow().getStatus())
                .isEqualTo(com.havita.modules.property.domain.PropertyStatus.OCCUPIED);
        assertThat(back.getEndedAt()).isNull();
        assertThat(back.getEndReason()).isNull();
        assertThat(reload(second).getStatus()).isEqualTo(ResidentStatus.INACTIVE);
        withToken(token, a, get("/api/v1/residents/by-property/{id}", a.home().getId())).andExpect(status().isOk());
        assertThat(residentRepository.findByUserIdAndTenantId(a.owner().getId(), a.tenantId())
                .stream().filter(r -> r.getProperty().getId().equals(a.home().getId()))).hasSize(1);
    }

    @Test
    @DisplayName("Volver a asignar la unidad a un residente dado de baja reactiva la misma fila (sin duplicados)")
    void reassignReactivatesWithoutDuplicates() throws Exception {
        as(a.admin(), a, delete("/api/v1/residents/{id}", a.ownerResident().getId())).andExpect(status().isOk());
        as(a.admin(), a, post("/api/v1/residents/users/{id}/properties", a.ownerResident().getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyIds\":[\"%s\"],\"type\":\"Propietario\",\"isMainContact\":false}".formatted(a.home().getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data[0].id").value(a.ownerResident().getId().toString()));
        assertThat(reload(a.ownerResident()).getStatus()).isEqualTo(ResidentStatus.ACTIVE);
        assertThat(residentRepository.findByUserIdAndTenantId(a.owner().getId(), a.tenantId())
                .stream().filter(r -> r.getProperty().getId().equals(a.home().getId()))).hasSize(1);
    }

    @Test
    @DisplayName("El propietario no puede reactivar por su cuenta un acceso retirado por la administracion (409)")
    void ownerCannotUndoAdminRemoval() throws Exception {
        User relativeUser = userRepository.save(User.builder().email("fam-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .passwordHash("{noop}x").fullName("Familiar").phone("3000000000").build());
        membershipRepository.save(Membership.builder().user(relativeUser).tenantId(a.tenantId()).role(RoleType.RESIDENT).status(MembershipStatus.ACTIVE).build());
        Resident relative = relation(a, a.home(), relativeUser, "Residente");

        as(a.admin(), a, delete("/api/v1/residents/{id}", relative.getId())).andExpect(status().isOk());
        as(a.owner(), a, patch("/api/v1/resident/me/people/{id}/reactivate", relative.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REACTIVATION_REQUIRES_ADMIN"));
        assertThat(reload(relative).getStatus()).isEqualTo(ResidentStatus.INACTIVE);
    }
}
