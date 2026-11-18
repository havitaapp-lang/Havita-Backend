package com.havita.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.assembly.domain.Assembly;
import com.havita.modules.assembly.domain.AssemblyAttendee;
import com.havita.modules.assembly.domain.AssemblyStatus;
import com.havita.modules.assembly.domain.Poll;
import com.havita.modules.assembly.domain.PollOption;
import com.havita.modules.assembly.domain.PollStatus;
import com.havita.modules.assembly.domain.VotingDelegation;
import com.havita.modules.assembly.domain.VotingDelegationStatus;
import com.havita.modules.assembly.infrastructure.AssemblyAttendeeRepository;
import com.havita.modules.assembly.infrastructure.AssemblyRepository;
import com.havita.modules.assembly.infrastructure.PollRepository;
import com.havita.modules.assembly.infrastructure.VotingDelegationRepository;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.property.domain.Property;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Fase 2 / E3 (D5, D8): una sola participacion por unidad y pregunta, tambien con peticiones CONCURRENTES. Cada peticion
 * concurrente sale de un hilo propio (su propia transaccion y conexion a PostgreSQL), arrancadas a la vez con un latch;
 * las carreras se repiten en varias rondas. El recuento se hace directamente en poll_votes.
 */
class PollVotingIT extends PostgresIntegrationTest {

    private static final int ROUNDS = 6;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private AssemblyRepository assemblyRepository;
    @Autowired private AssemblyAttendeeRepository attendeeRepository;
    @Autowired private PollRepository pollRepository;
    @Autowired private VotingDelegationRepository delegationRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private JdbcTemplate jdbc;

    private Side a;
    private Side b;
    private Assembly asm;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        asm = assembly(a);
    }

    // ── datos ─────────────────────────────────────────────────────────────────

    private Assembly assembly(Side side) {
        Assembly x = Assembly.builder().title("Asamblea E3").assemblyDate(LocalDate.now()).startTime(LocalTime.of(19, 0))
                .status(AssemblyStatus.IN_PROGRESS).build();
        x.setTenantId(side.tenantId());
        return assemblyRepository.save(x);
    }

    private Poll poll(Assembly assembly, Side side, String type) {
        Poll p = Poll.builder().assembly(assembly).question("Pregunta " + UUID.randomUUID().toString().substring(0, 6))
                .status(PollStatus.LIVE).type(type).options(new ArrayList<>()).build();
        p.setTenantId(side.tenantId());
        p.getOptions().add(PollOption.builder().poll(p).text("Si").optionOrder(1).build());
        p.getOptions().add(PollOption.builder().poll(p).text("No").optionOrder(2).build());
        return pollRepository.save(p);
    }

    /** Cierre E5 / D14: relacion ACTIVE del usuario con la unidad (el representante que se registra en la asistencia). */
    private Resident relationOf(User u, Property p) {
        return residentRepository.findByTenantIdAndPropertyId(a.tenantId(), p.getId()).stream()
                .filter(r -> r.getUser() != null && r.getUser().getId().equals(u.getId())).findFirst().orElseThrow();
    }

    private void attendee(Assembly assembly, Side side, Property property, Resident resident) {
        AssemblyAttendee at = AssemblyAttendee.builder().assembly(assembly).property(property).resident(resident)
                .coefficient(new BigDecimal("0.100000")).build();
        at.setTenantId(side.tenantId());
        attendeeRepository.save(at);
    }

    private User resident(Side side, Property property, String type) {
        String h = type.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        User u = userRepository.save(User.builder().email(h + "@example.test").passwordHash("{noop}x").fullName("Cuenta " + h)
                .phone("3000000000").build());
        membershipRepository.save(Membership.builder().user(u).tenantId(side.tenantId()).role(RoleType.RESIDENT)
                .status(MembershipStatus.ACTIVE).build());
        relation(side, property, u, type);
        return u;
    }

    private Resident relation(Side side, Property property, User u, String type) {
        Resident r = Resident.builder().property(property).user(u).name("Rel " + type).type(type)
                .email("r-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test").status(ResidentStatus.ACTIVE).build();
        r.setTenantId(side.tenantId());
        return residentRepository.save(r);
    }

    private void delegate(Property property, User owner, User tenant) {
        VotingDelegation d = VotingDelegation.builder().property(property).ownerUser(owner).delegateUser(tenant)
                .status(VotingDelegationStatus.ACTIVE).build();
        d.setTenantId(a.tenantId());
        delegationRepository.save(d);
    }

    // ── llamadas ──────────────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder voteReq(Poll p, UUID propertyId, UUID... options) {
        StringBuilder ids = new StringBuilder();
        for (UUID o : options) ids.append(ids.isEmpty() ? "" : ",").append('"').append(o).append('"');
        return post("/api/v1/assemblies/polls/{pollId}/vote", p.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"" + propertyId + "\",\"optionIds\":[" + ids + "]}");
    }

    private MvcResult call(User u, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(u)).header("X-Tenant-ID", side.tenantId().toString()))
                .andReturn();
    }

    private int votes(Poll p, UUID propertyId) {
        return jdbc.queryForObject("select count(*) from poll_votes where poll_id = ? and property_id = ?", Integer.class,
                p.getId(), propertyId);
    }

    private String code(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("code").asText();
    }

    private UUID opt(Poll p, int i) {
        return p.getOptions().get(i).getId();
    }

    /** Ejecuta las llamadas a la vez (hilos y transacciones independientes) y devuelve los estados HTTP. */
    private List<MvcResult> concurrently(List<Callable<MvcResult>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (Callable<MvcResult> c : calls) {
            futures.add(pool.submit(() -> { start.await(); return c.call(); }));
        }
        start.countDown();
        List<MvcResult> out = new ArrayList<>();
        for (Future<MvcResult> f : futures) out.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        return out;
    }

    private static long ok(List<MvcResult> rs) {
        return rs.stream().filter(r -> r.getResponse().getStatus() == 200).count();
    }

    // ── concurrencia ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("concurrencia · misma unidad, misma pregunta, misma opción (4 a la vez, varias rondas): un solo voto")
    void sameUnitSameOptionConcurrently() throws Exception {
        attendee(asm, a, a.home(), a.ownerResident());
        for (int round = 0; round < ROUNDS; round++) {
            Poll p = poll(asm, a, "SINGLE_CHOICE");
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) calls.add(() -> call(a.owner(), a, voteReq(p, a.home().getId(), opt(p, 0))));
            List<MvcResult> rs = concurrently(calls);
            assertThat(ok(rs)).as("ronda " + round).isEqualTo(1);
            assertThat(votes(p, a.home().getId())).as("ronda " + round).isEqualTo(1);
            for (MvcResult r : rs) if (r.getResponse().getStatus() != 200) assertThat(code(r)).isEqualTo("PROPERTY_ALREADY_VOTED");
        }
    }

    @Test
    @DisplayName("concurrencia · misma unidad con opciones DIFERENTES a la vez: un solo voto (antes: dos)")
    void sameUnitDifferentOptionsConcurrently() throws Exception {
        attendee(asm, a, a.home(), a.ownerResident());
        for (int round = 0; round < ROUNDS; round++) {
            Poll p = poll(asm, a, "SINGLE_CHOICE");
            List<MvcResult> rs = concurrently(List.of(
                    () -> call(a.owner(), a, voteReq(p, a.home().getId(), opt(p, 0))),
                    () -> call(a.owner(), a, voteReq(p, a.home().getId(), opt(p, 1))),
                    () -> call(a.owner(), a, voteReq(p, a.home().getId(), opt(p, 0))),
                    () -> call(a.owner(), a, voteReq(p, a.home().getId(), opt(p, 1)))));
            assertThat(ok(rs)).as("ronda " + round).isEqualTo(1);
            assertThat(votes(p, a.home().getId())).as("ronda " + round).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("concurrencia · propietario y arrendatario delegado a la vez: solo vota el delegado, una vez")
    void ownerAndDelegateConcurrently() throws Exception {
        User tenant = resident(a, a.home(), "Arrendatario");
        delegate(a.home(), a.owner(), tenant);
        attendee(asm, a, a.home(), relationOf(tenant, a.home())); // D14: la asistencia la registró el delegado
        for (int round = 0; round < ROUNDS; round++) {
            Poll p = poll(asm, a, "SINGLE_CHOICE");
            List<MvcResult> rs = concurrently(List.of(
                    () -> call(a.owner(), a, voteReq(p, a.home().getId(), opt(p, 0))),
                    () -> call(tenant, a, voteReq(p, a.home().getId(), opt(p, 1))),
                    () -> call(a.owner(), a, voteReq(p, a.home().getId(), opt(p, 0))),
                    () -> call(tenant, a, voteReq(p, a.home().getId(), opt(p, 1)))));
            assertThat(ok(rs)).isEqualTo(1);
            assertThat(votes(p, a.home().getId())).isEqualTo(1);
            // D14: vota el representante registrado (el delegado); el propietario no.
            assertThat(code(rs.get(0))).isEqualTo("UNAUTHORIZED_VOTE");
            assertThat(code(rs.get(2))).isEqualTo("UNAUTHORIZED_VOTE");
            String winner = jdbc.queryForObject("select cast(o.id as varchar) from poll_votes v join poll_options o on o.id = v.poll_option_id "
                    + "where v.poll_id = ?", String.class, p.getId());
            assertThat(winner).isEqualTo(opt(p, 1).toString()); // la opción del delegado
        }
    }

    @Test
    @DisplayName("concurrencia · delegar mientras el propietario vota (asistencia en curso): la delegación se rechaza y hay un solo voto, del propietario")
    void delegateWhileOwnerVotes() throws Exception {
        User tenant = resident(a, a.home(), "Arrendatario");
        attendee(asm, a, a.home(), a.ownerResident());
        for (int round = 0; round < ROUNDS; round++) {
            Poll p = poll(asm, a, "SINGLE_CHOICE");
            delegationRepository.findAll().stream().filter(d -> d.getProperty().getId().equals(a.home().getId())
                    && d.getStatus() == VotingDelegationStatus.ACTIVE).forEach(d -> { d.setStatus(VotingDelegationStatus.REVOKED); delegationRepository.save(d); });
            List<MvcResult> rs = concurrently(List.of(
                    () -> call(a.owner(), a, voteReq(p, a.home().getId(), opt(p, 0))),
                    () -> call(a.owner(), a, post("/api/v1/properties/{propertyId}/voting-delegation", a.home().getId()))));
            // Cierre E3: la unidad tiene asistencia en una asamblea en curso → la delegación se rechaza siempre.
            assertThat(rs.get(1).getResponse().getStatus()).as(rs.get(1).getResponse().getContentAsString()).isEqualTo(409);
            assertThat(code(rs.get(1))).isEqualTo("DELEGATION_BLOCKED_BY_ATTENDANCE");
            assertThat(rs.get(0).getResponse().getStatus()).as(rs.get(0).getResponse().getContentAsString()).isEqualTo(200);
            // Después, el arrendatario no puede votar (no hay delegación) y la unidad tiene un solo voto.
            MvcResult t = call(tenant, a, voteReq(p, a.home().getId(), opt(p, 1)));
            assertThat(t.getResponse().getStatus()).isEqualTo(409);
            assertThat(votes(p, a.home().getId())).as("ronda " + round).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("concurrencia · dos unidades distintas a la vez: ambas votan (no se bloquean entre sí)")
    void twoUnitsConcurrently() throws Exception {
        attendee(asm, a, a.home(), a.ownerResident());
        attendee(asm, a, a.neighbourHome(), a.neighbourResident());
        Poll p = poll(asm, a, "SINGLE_CHOICE");
        List<MvcResult> rs = concurrently(List.of(
                () -> call(a.owner(), a, voteReq(p, a.home().getId(), opt(p, 0))),
                () -> call(a.neighbour(), a, voteReq(p, a.neighbourHome().getId(), opt(p, 1)))));
        assertThat(ok(rs)).isEqualTo(2);
        assertThat(votes(p, a.home().getId())).isEqualTo(1);
        assertThat(votes(p, a.neighbourHome().getId())).isEqualTo(1);
    }

    // ── reglas ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reintento de un voto ya registrado: 409 PROPERTY_ALREADY_VOTED, sigue habiendo uno")
    void retryAlreadyVoted() throws Exception {
        attendee(asm, a, a.home(), a.ownerResident());
        Poll p = poll(asm, a, "SINGLE_CHOICE");
        assertThat(call(a.owner(), a, voteReq(p, a.home().getId(), opt(p, 0))).getResponse().getStatus()).isEqualTo(200);
        MvcResult again = call(a.owner(), a, voteReq(p, a.home().getId(), opt(p, 1)));
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(again)).isEqualTo("PROPERTY_ALREADY_VOTED");
        assertThat(votes(p, a.home().getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("D14 · asistencia SIN persona (anterior a D14): nadie vota hasta identificar al representante; la administración no vota")
    void anonymousAttendanceAndAdminVote() throws Exception {
        User coOwner = resident(a, a.home(), "Propietario");
        attendee(asm, a, a.home(), null);
        Poll p = poll(asm, a, "SINGLE_CHOICE");
        for (User u : List.of(a.owner(), coOwner)) {
            MvcResult r = call(u, a, voteReq(p, a.home().getId(), opt(p, 0)));
            assertThat(r.getResponse().getStatus()).isEqualTo(409);
            assertThat(code(r)).isEqualTo("REPRESENTATIVE_NOT_IDENTIFIED");
        }
        MvcResult admin = call(a.admin(), a, voteReq(p, a.home().getId(), opt(p, 0)));
        assertThat(code(admin)).isEqualTo("ADMIN_VOTE_NOT_SUPPORTED");
        assertThat(votes(p, a.home().getId())).isZero();
    }

    @Test
    @DisplayName("pregunta de opción única con dos opciones en la misma petición: 400 y ningún voto (antes: dos filas)")
    void singleChoiceRejectsSeveralOptions() throws Exception {
        attendee(asm, a, a.home(), a.ownerResident());
        Poll p = poll(asm, a, "SINGLE_CHOICE");
        MvcResult r = call(a.owner(), a, voteReq(p, a.home().getId(), opt(p, 0), opt(p, 1)));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(r)).isEqualTo("INVALID_VOTE_OPTIONS");
        assertThat(votes(p, a.home().getId())).isZero();
    }

    @Test
    @DisplayName("propietario de varias unidades: vota por cada una una vez; la consulta muestra el estado de cada unidad")
    void ownerOfSeveralUnits() throws Exception {
        Resident vacantRel = relation(a, a.vacant(), a.owner(), "Propietario");
        attendee(asm, a, a.home(), a.ownerResident());
        attendee(asm, a, a.vacant(), vacantRel);
        Poll p = poll(asm, a, "SINGLE_CHOICE");

        JsonNode before = objectMapper.readTree(call(a.owner(), a, get("/api/v1/assemblies/polls/{pollId}/my-voting-units", p.getId()))
                .getResponse().getContentAsString()).path("data");
        assertThat(before).hasSize(2);
        before.forEach(u -> assertThat(u.path("status").asText()).as(u.toString()).isEqualTo("PENDING"));

        assertThat(call(a.owner(), a, voteReq(p, a.home().getId(), opt(p, 0))).getResponse().getStatus()).isEqualTo(200);
        assertThat(call(a.owner(), a, voteReq(p, a.vacant().getId(), opt(p, 1))).getResponse().getStatus()).isEqualTo(200);
        assertThat(code(call(a.owner(), a, voteReq(p, a.vacant().getId(), opt(p, 0))))).isEqualTo("PROPERTY_ALREADY_VOTED");
        assertThat(votes(p, a.home().getId())).isEqualTo(1);
        assertThat(votes(p, a.vacant().getId())).isEqualTo(1);

        JsonNode after = objectMapper.readTree(call(a.owner(), a, get("/api/v1/assemblies/polls/{pollId}/my-voting-units", p.getId()))
                .getResponse().getContentAsString()).path("data");
        after.forEach(u -> {
            assertThat(u.path("status").asText()).isEqualTo("VOTED");
            assertThat(u.path("canVote").asBoolean()).isFalse();
        });
    }

    @Test
    @DisplayName("consulta de unidades: delegada, sin asistencia y otra comunidad se distinguen; solo unidades propias")
    void myVotingUnitsReasons() throws Exception {
        User tenant = resident(a, a.home(), "Arrendatario");
        delegate(a.home(), a.owner(), tenant);
        relation(a, a.vacant(), a.owner(), "Propietario"); // sin asistencia
        attendee(asm, a, a.home(), relationOf(tenant, a.home())); // D14: representada por el delegado
        Poll p = poll(asm, a, "SINGLE_CHOICE");
        JsonNode units = objectMapper.readTree(call(a.owner(), a, get("/api/v1/assemblies/polls/{pollId}/my-voting-units", p.getId()))
                .getResponse().getContentAsString()).path("data");
        assertThat(units).hasSize(2);
        for (JsonNode u : units) {
            String reason = u.path("reasonCode").asText();
            if (u.path("propertyId").asText().equals(a.home().getId().toString())) assertThat(reason).isEqualTo("UNAUTHORIZED_VOTE"); // D14: no es el representante
            else assertThat(reason).isEqualTo("PROPERTY_NOT_REGISTERED_IN_ASSEMBLY");
            assertThat(u.path("status").asText()).isEqualTo("NOT_ELIGIBLE");
        }
        JsonNode delegateView = objectMapper.readTree(call(tenant, a, get("/api/v1/assemblies/polls/{pollId}/my-voting-units", p.getId()))
                .getResponse().getContentAsString()).path("data");
        assertThat(delegateView).singleElement().satisfies(u -> assertThat(u.path("status").asText()).isEqualTo("PENDING"));
        // Pregunta de otra comunidad: 404, sin datos.
        assertThat(call(b.owner(), b, get("/api/v1/assemblies/polls/{pollId}/my-voting-units", p.getId())).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("unidad de otra comunidad y unidad revocada: rechazadas y sin voto")
    void foreignAndRevokedUnits() throws Exception {
        attendee(asm, a, a.home(), a.ownerResident());
        Poll p = poll(asm, a, "SINGLE_CHOICE");
        // Residente de B con la pregunta de A: la pregunta no existe para B.
        assertThat(call(b.owner(), b, voteReq(p, b.home().getId(), opt(p, 0))).getResponse().getStatus()).isEqualTo(404);
        // Residente de A con una unidad de B.
        assertThat(call(a.owner(), a, voteReq(p, b.home().getId(), opt(p, 0))).getResponse().getStatus()).isEqualTo(403);
        // Unidad revocada.
        Resident own = residentRepository.findById(a.ownerResident().getId()).orElseThrow();
        relation(a, a.vacant(), a.owner(), "Propietario"); // conserva otra unidad: sigue con sesión
        own.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(own);
        assertThat(call(a.owner(), a, voteReq(p, a.home().getId(), opt(p, 0))).getResponse().getStatus()).isEqualTo(403);
        assertThat(votes(p, a.home().getId())).isZero();
        assertThat(votes(p, b.home().getId())).isZero();
    }

    @Test
    @DisplayName("asamblea finalizada y pregunta cerrada: no se reciben votos")
    void closedAssemblyOrPoll() throws Exception {
        attendee(asm, a, a.home(), a.ownerResident());
        Poll closed = poll(asm, a, "SINGLE_CHOICE");
        closed.close();
        pollRepository.save(closed);
        MvcResult r1 = call(a.owner(), a, voteReq(closed, a.home().getId(), opt(closed, 0)));
        assertThat(code(r1)).isEqualTo("POLL_NOT_LIVE");

        Poll live = poll(asm, a, "SINGLE_CHOICE");
        asm.finish();
        assemblyRepository.save(asm);
        MvcResult r2 = call(a.owner(), a, voteReq(live, a.home().getId(), opt(live, 0)));
        assertThat(r2.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(r2)).isEqualTo("ASSEMBLY_ENDED");
        assertThat(votes(live, a.home().getId())).isZero();
        assertThat(votes(closed, a.home().getId())).isZero();
    }

    @Test
    @DisplayName("rollback: un voto que falla a mitad no deja filas ni el bloqueo tomado; el voto correcto entra después")
    void rollbackLeavesNothing() throws Exception {
        attendee(asm, a, a.home(), a.ownerResident());
        Poll p = poll(asm, a, "MULTIPLE_CHOICE");
        // Primera opción válida (se guarda) y segunda inexistente: la transacción se revierte entera.
        MvcResult bad = call(a.owner(), a, voteReq(p, a.home().getId(), opt(p, 0), UUID.randomUUID()));
        assertThat(bad.getResponse().getStatus()).isEqualTo(404);
        assertThat(votes(p, a.home().getId())).isZero();
        MvcResult good = call(a.owner(), a, voteReq(p, a.home().getId(), opt(p, 0), opt(p, 1)));
        assertThat(good.getResponse().getStatus()).isEqualTo(200);
        assertThat(votes(p, a.home().getId())).isEqualTo(2); // opción múltiple: una fila por opción, una participación
    }
}
