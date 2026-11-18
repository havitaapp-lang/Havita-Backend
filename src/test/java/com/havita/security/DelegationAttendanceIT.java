package com.havita.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.assembly.domain.Assembly;
import com.havita.modules.assembly.domain.AssemblyAttendee;
import com.havita.modules.assembly.domain.AssemblyStatus;
import com.havita.modules.assembly.domain.VotingDelegation;
import com.havita.modules.assembly.domain.VotingDelegationStatus;
import com.havita.modules.assembly.infrastructure.AssemblyAttendeeRepository;
import com.havita.modules.assembly.infrastructure.AssemblyRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Cierre E3 — regla definitiva: no se crea ni activa una delegacion de voto para una unidad con asistencia registrada en
 * una asamblea EN CURSO (IN_PROGRESS). Solo esa unidad; asambleas programadas o finalizadas no cuentan; la asistencia, el
 * quorum y los votos no se tocan. Las carreras usan hilos y transacciones independientes en PostgreSQL.
 */
class DelegationAttendanceIT extends PostgresIntegrationTest {

    private static final int ROUNDS = 8;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private AssemblyRepository assemblyRepository;
    @Autowired private AssemblyAttendeeRepository attendeeRepository;
    @Autowired private VotingDelegationRepository delegationRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private JdbcTemplate jdbc;

    private Side a;
    private Side b;
    private User tenantOfHome;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        tenantOfHome = resident(a, a.home(), "Arrendatario");
    }

    // ── datos ─────────────────────────────────────────────────────────────────

    private Assembly assembly(Side side, AssemblyStatus status) {
        Assembly x = Assembly.builder().title("Asamblea cierre E3").assemblyDate(LocalDate.now()).startTime(LocalTime.of(19, 0))
                .status(status).build();
        x.setTenantId(side.tenantId());
        return assemblyRepository.save(x);
    }

    private void attendance(Assembly asm, Side side, Property property) {
        AssemblyAttendee at = AssemblyAttendee.builder().assembly(asm).property(property).coefficient(new BigDecimal("0.1")).build();
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

    private MvcResult call(User u, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(u)).header("X-Tenant-ID", side.tenantId().toString())).andReturn();
    }

    private MvcResult delegateAs(User owner, Property p) throws Exception {
        return call(owner, a, post("/api/v1/properties/{propertyId}/voting-delegation", p.getId()));
    }

    private MvcResult selfRegister(User u, Assembly asm, Property p) throws Exception {
        return call(u, a, post("/api/v1/assemblies/{id}/attendees/self-register", asm.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"propertyId\":\"" + p.getId() + "\"}"));
    }

    private String code(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("code").asText();
    }

    private int activeDelegations(Property p) {
        return jdbc.queryForObject("select count(*) from voting_delegations where property_id = ? and status = 'ACTIVE'",
                Integer.class, p.getId());
    }

    private int attendees(Assembly asm, Property p) {
        return jdbc.queryForObject("select count(*) from assembly_attendees where assembly_id = ? and property_id = ?",
                Integer.class, asm.getId(), p.getId());
    }

    /** Huella de las filas de delegacion y asistencia de la unidad (para comprobar que un rechazo no escribe nada). */
    private String fingerprint(Property p) {
        return jdbc.queryForObject("select md5(coalesce((select string_agg(to_jsonb(d)::text, ',' order by d.id) from voting_delegations d "
                + "where d.property_id = ?), '') || coalesce((select string_agg(to_jsonb(t)::text, ',' order by t.id) "
                + "from assembly_attendees t where t.property_id = ?), ''))", String.class, p.getId(), p.getId());
    }

    private void assertBlocked(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("DELEGATION_BLOCKED_BY_ATTENDANCE");
    }

    // ── regla ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1 · sin asistencia registrada: la delegación se crea")
    void delegationWithoutAttendance() throws Exception {
        assembly(a, AssemblyStatus.IN_PROGRESS);
        assertThat(delegateAs(a.owner(), a.home()).getResponse().getStatus()).isEqualTo(200);
        assertThat(activeDelegations(a.home())).isEqualTo(1);
    }

    @Test
    @DisplayName("2/15 · con asistencia en una asamblea en curso: 409 DELEGATION_BLOCKED_BY_ATTENDANCE y nada cambia")
    void delegationBlockedByAttendance() throws Exception {
        Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
        attendance(asm, a, a.home());
        String before = fingerprint(a.home());
        String quorum = jdbc.queryForObject("select md5(to_jsonb(x)::text) from assemblies x where id = ?", String.class, asm.getId());
        assertBlocked(delegateAs(a.owner(), a.home()));
        assertThat(fingerprint(a.home())).as("ni delegación ni asistencia cambian").isEqualTo(before);
        assertThat(jdbc.queryForObject("select md5(to_jsonb(x)::text) from assemblies x where id = ?", String.class, asm.getId()))
                .as("quórum intacto").isEqualTo(quorum);
        assertThat(activeDelegations(a.home())).isZero();
    }

    @Test
    @DisplayName("3/4 · asistencia de OTRA unidad o de OTRA comunidad: no bloquea esta unidad")
    void otherUnitOrCommunityAttendance() throws Exception {
        Assembly asmA = assembly(a, AssemblyStatus.IN_PROGRESS);
        attendance(asmA, a, a.neighbourHome());
        Assembly asmB = assembly(b, AssemblyStatus.IN_PROGRESS);
        attendance(asmB, b, b.home());
        assertThat(delegateAs(a.owner(), a.home()).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("5 · propietario de varias unidades: solo se bloquea la unidad con asistencia")
    void ownerOfSeveralUnits() throws Exception {
        relation(a, a.vacant(), a.owner(), "Propietario");
        resident(a, a.vacant(), "Arrendatario");
        Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
        attendance(asm, a, a.home());
        assertBlocked(delegateAs(a.owner(), a.home()));
        assertThat(delegateAs(a.owner(), a.vacant()).getResponse().getStatus()).isEqualTo(200);
        assertThat(activeDelegations(a.vacant())).isEqualTo(1);
    }

    @Test
    @DisplayName("6/7 · delegación existente y revocada: ya existente → DELEGATION_ALREADY_EXISTS; revocar sigue permitido; re-delegar con asistencia → bloqueado")
    void existingAndRevokedDelegation() throws Exception {
        assertThat(delegateAs(a.owner(), a.home()).getResponse().getStatus()).isEqualTo(200);
        Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
        attendance(asm, a, a.home()); // asistencia posterior a la delegación (p. ej. la registró el arrendatario)
        assertThat(code(delegateAs(a.owner(), a.home()))).isEqualTo("DELEGATION_ALREADY_EXISTS");
        // Las reglas de revocación no cambian.
        assertThat(call(a.owner(), a, delete("/api/v1/properties/{propertyId}/voting-delegation", a.home().getId()))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(activeDelegations(a.home())).isZero();
        // Volver a delegar con asistencia en curso: bloqueado.
        assertBlocked(delegateAs(a.owner(), a.home()));
        assertThat(attendees(asm, a.home())).isEqualTo(1);
    }

    @Test
    @DisplayName("8/9 · asistencia en asamblea FINALIZADA: no bloquea; en asamblea PROGRAMADA sí (E5 / D10)")
    void scheduledOrEndedAssemblies() throws Exception {
        attendance(assembly(a, AssemblyStatus.ENDED), a, a.home());
        assertThat(delegateAs(a.owner(), a.home()).getResponse().getStatus()).isEqualTo(200);
        // E5 / D10 amplía la regla del cierre de E3 a las asambleas programadas (ver AssemblyConsolidationIT).
        jdbc.update("update voting_delegations set status = 'REVOKED' where property_id = ? and status = 'ACTIVE'", a.home().getId());
        attendance(assembly(a, AssemblyStatus.SCHEDULED), a, a.home());
        assertBlocked(delegateAs(a.owner(), a.home()));
    }

    @Test
    @DisplayName("13/14 · usuario sin autorización y unidad revocada: rechazados sin escrituras")
    void unauthorizedOrRevoked() throws Exception {
        String before = fingerprint(a.home());
        // Un vecino de la comunidad: la expresion del endpoint ya lo rechaza (no es residente de esa unidad).
        assertThat(delegateAs(a.neighbour(), a.home()).getResponse().getStatus()).isEqualTo(403);
        // Un arrendatario de la unidad (residente, pero no propietario): NOT_THE_OWNER.
        assertThat(code(delegateAs(tenantOfHome, a.home()))).isEqualTo("NOT_THE_OWNER");
        assertThat(delegateAs(b.owner(), a.home()).getResponse().getStatus()).as("otra comunidad").isIn(401, 403, 404);
        Resident own = residentRepository.findById(a.ownerResident().getId()).orElseThrow();
        relation(a, a.vacant(), a.owner(), "Propietario"); // conserva otra unidad
        own.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(own);
        assertThat(delegateAs(a.owner(), a.home()).getResponse().getStatus()).isIn(403, 409);
        assertThat(fingerprint(a.home())).isEqualTo(before);
    }

    // ── concurrencia ──────────────────────────────────────────────────────────

    /** Ejecuta las llamadas a la vez (hilos y transacciones independientes). */
    private List<MvcResult> concurrently(List<Callable<MvcResult>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (Callable<MvcResult> c : calls) futures.add(pool.submit(() -> { start.await(); return c.call(); }));
        start.countDown();
        List<MvcResult> out = new ArrayList<>();
        for (Future<MvcResult> f : futures) out.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        return out;
    }

    @Test
    @DisplayName("10 · registrar asistencia y delegar a la vez (varias rondas): nunca asistencia del propietario en curso + delegación activa")
    void attendanceAndDelegationConcurrently() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("update voting_delegations set status = 'REVOKED' where property_id = ? and status = 'ACTIVE'", a.home().getId());
            Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
            List<MvcResult> rs = concurrently(List.of(
                    () -> selfRegister(a.owner(), asm, a.home()),
                    () -> delegateAs(a.owner(), a.home())));
            int att = attendees(asm, a.home());
            int del = activeDelegations(a.home());
            assertThat(att + del).as("ronda " + round + ": exactamente una de las dos operaciones gana").isEqualTo(1);
            if (att == 1) {
                assertThat(rs.get(0).getResponse().getStatus()).isIn(200, 201);
                assertBlocked(rs.get(1));
            } else {
                assertThat(rs.get(1).getResponse().getStatus()).isEqualTo(200);
                assertThat(code(rs.get(0))).isEqualTo("VOTING_RIGHT_DELEGATED"); // regla existente de la auto-inscripción
            }
            // Cierra la asamblea de esta ronda para que no afecte a la siguiente.
            jdbc.update("update assemblies set status = 'ENDED' where id = ?", asm.getId());
        }
    }

    @Test
    @DisplayName("10b · el arrendatario delegado registra asistencia mientras el propietario intenta delegar de nuevo: estado coherente")
    void delegateAttendanceVersusRedelegation() throws Exception {
        assertThat(delegateAs(a.owner(), a.home()).getResponse().getStatus()).isEqualTo(200);
        for (int round = 0; round < ROUNDS; round++) {
            Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
            List<MvcResult> rs = concurrently(List.of(
                    () -> selfRegister(tenantOfHome, asm, a.home()),
                    () -> delegateAs(a.owner(), a.home())));
            assertThat(rs.get(0).getResponse().getStatus()).as(rs.get(0).getResponse().getContentAsString()).isIn(200, 201);
            assertThat(code(rs.get(1))).isIn("DELEGATION_ALREADY_EXISTS", "DELEGATION_BLOCKED_BY_ATTENDANCE");
            assertThat(activeDelegations(a.home())).isEqualTo(1);
            assertThat(attendees(asm, a.home())).isEqualTo(1);
            jdbc.update("update assemblies set status = 'ENDED' where id = ?", asm.getId());
        }
    }
}
