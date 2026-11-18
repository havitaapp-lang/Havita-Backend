package com.havita.security;

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
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Fase 2 / cierre de E5 — representacion de la unidad en asambleas:
 * <ul>
 *   <li>D11: cualquier copropietario activo puede representar, delegar o revocar (la delegacion la revoca su autor); el
 *       primer registro valido representa a la unidad y no se duplica ni se sustituye;</li>
 *   <li>D12: la revocacion se bloquea desde el pre-registro del delegado (asamblea programada) y en curso;</li>
 *   <li>D13: el registro administrativo valida comunidad, estado de la asamblea y la relacion de la persona indicada;</li>
 *   <li>carreras con hilos, conexiones y transacciones independientes en PostgreSQL, verificando el estado final.</li>
 * </ul>
 */
class AssemblyRepresentationIT extends PostgresIntegrationTest {

    private static final int ROUNDS = 8;

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
    /** Copropietarios de a.home(): a.owner() (fixture), coOwner1 y coOwner2. */
    private User coOwner1;
    private User coOwner2;
    private User lessee;
    private Resident lesseeRel;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        coOwner1 = account(a);
        relation(a, a.home(), coOwner1, "Propietario");
        coOwner2 = account(a);
        relation(a, a.home(), coOwner2, "Propietario");
        lessee = account(a);
        lesseeRel = relation(a, a.home(), lessee, "Arrendatario");
    }

    // ── datos ─────────────────────────────────────────────────────────────────

    private Assembly assembly(Side side, AssemblyStatus status) {
        Assembly x = Assembly.builder().title("Asamblea cierre E5").assemblyDate(LocalDate.now()).startTime(LocalTime.of(19, 0))
                .status(status).build();
        x.setTenantId(side.tenantId());
        return assemblyRepository.save(x);
    }

    private void attendance(Assembly asm, Side side, Property property, Resident resident) {
        AssemblyAttendee at = AssemblyAttendee.builder().assembly(asm).property(property).resident(resident)
                .coefficient(new BigDecimal("0.1")).build();
        at.setTenantId(side.tenantId());
        attendeeRepository.save(at);
    }

    private Poll poll(Assembly asm, Side side) {
        Poll p = Poll.builder().assembly(asm).question("Pregunta " + UUID.randomUUID().toString().substring(0, 6))
                .status(PollStatus.LIVE).type("SINGLE_CHOICE").options(new ArrayList<>()).build();
        p.setTenantId(side.tenantId());
        p.getOptions().add(PollOption.builder().poll(p).text("Si").optionOrder(1).build());
        p.getOptions().add(PollOption.builder().poll(p).text("No").optionOrder(2).build());
        return pollRepository.save(p);
    }

    private User account(Side side) {
        String h = "e5c-" + UUID.randomUUID().toString().substring(0, 8);
        User u = userRepository.save(User.builder().email(h + "@example.test").passwordHash("{noop}x").fullName("Cuenta " + h)
                .phone("3000000000").build());
        membershipRepository.save(Membership.builder().user(u).tenantId(side.tenantId()).role(RoleType.RESIDENT)
                .status(MembershipStatus.ACTIVE).build());
        return u;
    }

    private Resident relation(Side side, Property property, User u, String type) {
        Resident r = Resident.builder().property(property).user(u).name("Rel " + type).type(type)
                .email("r-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test").status(ResidentStatus.ACTIVE).build();
        r.setTenantId(side.tenantId());
        return residentRepository.save(r);
    }

    private Resident relationOf(User u, Property p) {
        return residentRepository.findByTenantIdAndPropertyId(a.tenantId(), p.getId()).stream()
                .filter(r -> r.getUser() != null && r.getUser().getId().equals(u.getId())).findFirst().orElseThrow();
    }

    private void delegation(Side side, Property property, User owner, User delegate) {
        VotingDelegation d = VotingDelegation.builder().property(property).ownerUser(owner).delegateUser(delegate)
                .status(VotingDelegationStatus.ACTIVE).build();
        d.setTenantId(side.tenantId());
        delegationRepository.save(d);
    }

    // ── llamadas ──────────────────────────────────────────────────────────────

    private MvcResult call(User u, Side side, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", fixture.bearer(u)).header("X-Tenant-ID", side.tenantId().toString())).andReturn();
    }

    private MvcResult delegateAs(User owner, Property p) throws Exception {
        return call(owner, a, post("/api/v1/properties/{propertyId}/voting-delegation", p.getId()));
    }

    private MvcResult revokeAs(User owner, Property p) throws Exception {
        return call(owner, a, delete("/api/v1/properties/{propertyId}/voting-delegation", p.getId()));
    }

    private MvcResult selfRegister(User u, Side side, Assembly asm, Property p) throws Exception {
        return call(u, side, post("/api/v1/assemblies/{id}/attendees/self-register", asm.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"propertyId\":\"" + p.getId() + "\"}"));
    }

    private MvcResult adminRegister(User admin, Side side, Assembly asm, Property p, UUID residentId) throws Exception {
        String body = residentId == null ? "{\"propertyId\":\"" + p.getId() + "\"}"
                : "{\"propertyId\":\"" + p.getId() + "\",\"residentId\":\"" + residentId + "\"}";
        return call(admin, side, post("/api/v1/assemblies/{id}/attendees", asm.getId()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MvcResult adminAction(Side side, String path, UUID id) throws Exception {
        return call(side.admin(), side, post(path, id));
    }

    private MvcResult vote(User u, Poll p, Property unit, int option) throws Exception {
        UUID opt = p.getOptions().get(option).getId();
        return call(u, a, post("/api/v1/assemblies/polls/{id}/vote", p.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"" + unit.getId() + "\",\"optionIds\":[\"" + opt + "\"]}"));
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private String code(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("code").asText();
    }

    private int activeDelegations(Property p) {
        return jdbc.queryForObject("select count(*) from voting_delegations where property_id = ? and status = 'ACTIVE'", Integer.class, p.getId());
    }

    private int attendees(Assembly asm, Property p) {
        return jdbc.queryForObject("select count(*) from assembly_attendees where assembly_id = ? and property_id = ?",
                Integer.class, asm.getId(), p.getId());
    }

    private int registered(Assembly asm) {
        return jdbc.queryForObject("select registered_attendees from assemblies where id = ?", Integer.class, asm.getId());
    }

    private int realAttendees(Assembly asm) {
        return jdbc.queryForObject("select count(*) from assembly_attendees where assembly_id = ?", Integer.class, asm.getId());
    }

    private UUID representativeUser(Assembly asm, Property p) {
        return jdbc.queryForObject("select r.user_id from assembly_attendees t join residents r on r.id = t.resident_id "
                + "where t.assembly_id = ? and t.property_id = ?", UUID.class, asm.getId(), p.getId());
    }

    /** Huella de delegaciones, asistencias y votos de la unidad (un rechazo no escribe nada). */
    private String fingerprint(Property p) {
        return jdbc.queryForObject("select md5(coalesce((select string_agg(to_jsonb(d)::text, ',' order by d.id) from voting_delegations d "
                + "where d.property_id = ?), '') || coalesce((select string_agg(to_jsonb(t)::text, ',' order by t.id) "
                + "from assembly_attendees t where t.property_id = ?), '') || coalesce((select string_agg(to_jsonb(v)::text, ',' "
                + "order by v.id) from poll_votes v where v.property_id = ?), ''))", String.class, p.getId(), p.getId(), p.getId());
    }

    private String assemblyRow(Assembly asm) {
        return jdbc.queryForObject("select md5(to_jsonb(x)::text) from assemblies x where id = ?", String.class, asm.getId());
    }

    private void endAssembly(Assembly asm) {
        jdbc.update("update assemblies set status = 'ENDED' where id = ?", asm.getId());
    }

    private void resetDelegations(Property p) {
        jdbc.update("update voting_delegations set status = 'REVOKED' where property_id = ? and status = 'ACTIVE'", p.getId());
    }

    /** Ejecuta las llamadas a la vez (hilos, conexiones y transacciones independientes). */
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

    private static void noServerErrors(List<MvcResult> rs, String what) {
        for (MvcResult r : rs) {
            assertThat(status(r)).as(what + ": sin errores 500").isLessThan(500);
        }
    }

    // ── D11: copropietarios ───────────────────────────────────────────────────

    @Test
    @DisplayName("D11 · carreras 1/2 · dos y tres copropietarios se registran a la vez: una representación, quórum 1, solo vota el representante")
    void coOwnersRegisterConcurrently() throws Exception {
        List<User> owners = List.of(a.owner(), coOwner1, coOwner2);
        for (int round = 0; round < ROUNDS; round++) {
            Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
            List<User> contenders = round % 2 == 0 ? owners.subList(0, 2) : owners; // 2 y 3 copropietarios
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (User u : contenders) calls.add(() -> selfRegister(u, a, asm, a.home()));
            List<MvcResult> rs = concurrently(calls);
            noServerErrors(rs, "ronda " + round);
            assertThat(rs.stream().filter(r -> status(r) == 201).count()).as("ronda " + round).isEqualTo(1);
            for (MvcResult r : rs) if (status(r) != 201) assertThat(code(r)).isEqualTo("ALREADY_REGISTERED");
            assertThat(attendees(asm, a.home())).isEqualTo(1);
            assertThat(registered(asm)).as("quórum sin duplicar").isEqualTo(1);

            UUID rep = representativeUser(asm, a.home());
            User winner = contenders.stream().filter(u -> u.getId().equals(rep)).findFirst().orElseThrow();
            assertThat(status(rs.get(contenders.indexOf(winner)))).as("el representante es quien obtuvo 201").isEqualTo(201);

            Poll p = poll(asm, a);
            for (User u : contenders) {
                if (u != winner) assertThat(code(vote(u, p, a.home(), 0))).isEqualTo("UNAUTHORIZED_VOTE");
            }
            assertThat(status(vote(winner, p, a.home(), 1))).isEqualTo(200);
            assertThat(jdbc.queryForObject("select count(*) from poll_votes where poll_id = ?", Integer.class, p.getId())).isEqualTo(1);
            // Nadie sustituye al representante: ni otro auto-registro ni un registro administrativo.
            assertThat(code(selfRegister(contenders.get(0) == winner ? contenders.get(1) : contenders.get(0), a, asm, a.home())))
                    .isEqualTo("ALREADY_REGISTERED");
            assertThat(code(adminRegister(a.admin(), a, asm, a.home(), relationOf(coOwner2, a.home()).getId())))
                    .isEqualTo("PROPERTY_ALREADY_REGISTERED");
            assertThat(representativeUser(asm, a.home())).isEqualTo(rep);
            endAssembly(asm);
        }
    }

    @Test
    @DisplayName("D11 · delegación entre copropietarios: cualquiera delega; solo el autor revoca; sin sobrescritura")
    void delegationBetweenCoOwners() throws Exception {
        assertThat(status(delegateAs(coOwner1, a.home()))).isEqualTo(200);
        UUID author = jdbc.queryForObject("select owner_user_id from voting_delegations where property_id = ? and status = 'ACTIVE'",
                UUID.class, a.home().getId());
        assertThat(author).as("la delegación queda a nombre de quien la otorgó").isEqualTo(coOwner1.getId());

        // Otro copropietario no la sobrescribe ni la revoca.
        String before = fingerprint(a.home());
        assertThat(code(delegateAs(a.owner(), a.home()))).isEqualTo("DELEGATION_ALREADY_EXISTS");
        MvcResult other = revokeAs(coOwner2, a.home());
        assertThat(status(other)).isEqualTo(409);
        assertThat(code(other)).isEqualTo("DELEGATION_GRANTED_BY_OTHER_OWNER");
        assertThat(fingerprint(a.home())).isEqualTo(before);

        // El autor la revoca.
        assertThat(status(revokeAs(coOwner1, a.home()))).isEqualTo(200);
        assertThat(jdbc.queryForObject("select revoked_by_user_id from voting_delegations where property_id = ? and status = 'REVOKED'",
                UUID.class, a.home().getId())).isEqualTo(coOwner1.getId());
        assertThat(activeDelegations(a.home())).isZero();
    }

    @Test
    @DisplayName("D11 · el autor dejó de ser propietario activo: otro copropietario activo puede revocar (regla anterior); un copropietario inactivo no")
    void authorNoLongerOwner() throws Exception {
        assertThat(status(delegateAs(coOwner1, a.home()))).isEqualTo(200);
        Resident rel = relationOf(coOwner1, a.home());
        rel.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(rel);
        relation(a, a.vacant(), coOwner1, "Propietario"); // conserva otra unidad: sigue autenticado
        // Un copropietario inactivo no revoca: la expresión del endpoint ya lo rechaza (403), sin escrituras.
        assertThat(status(revokeAs(coOwner1, a.home()))).as("un copropietario inactivo no revoca").isEqualTo(403);
        assertThat(activeDelegations(a.home())).isEqualTo(1);
        assertThat(status(revokeAs(coOwner2, a.home()))).isEqualTo(200);
        assertThat(activeDelegations(a.home())).isZero();
    }

    @Test
    @DisplayName("D11 · carrera 3 · copropietario A registra asistencia mientras B delega: exactamente una gana")
    void coOwnerAttendanceVersusDelegation() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            resetDelegations(a.home());
            Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
            List<MvcResult> rs = concurrently(List.of(
                    () -> selfRegister(coOwner1, a, asm, a.home()),
                    () -> delegateAs(coOwner2, a.home())));
            noServerErrors(rs, "ronda " + round);
            int att = attendees(asm, a.home());
            int del = activeDelegations(a.home());
            assertThat(att + del).as("ronda " + round).isEqualTo(1);
            if (att == 1) {
                assertThat(code(rs.get(1))).isEqualTo("DELEGATION_BLOCKED_BY_ATTENDANCE");
            } else {
                assertThat(code(rs.get(0))).isEqualTo("VOTING_RIGHT_DELEGATED");
            }
            endAssembly(asm);
        }
    }

    @Test
    @DisplayName("D11 · carrera 4 · dos copropietarios delegan a la vez: una delegación, cuyo autor es quien recibió 200")
    void coOwnersDelegateConcurrently() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            resetDelegations(a.home());
            List<MvcResult> rs = concurrently(List.of(() -> delegateAs(coOwner1, a.home()), () -> delegateAs(coOwner2, a.home())));
            noServerErrors(rs, "ronda " + round);
            assertThat(rs.stream().filter(r -> status(r) == 200).count()).as("ronda " + round).isEqualTo(1);
            assertThat(activeDelegations(a.home())).isEqualTo(1);
            UUID author = jdbc.queryForObject("select owner_user_id from voting_delegations where property_id = ? and status = 'ACTIVE'",
                    UUID.class, a.home().getId());
            User expected = status(rs.get(0)) == 200 ? coOwner1 : coOwner2;
            assertThat(author).isEqualTo(expected.getId());
            for (MvcResult r : rs) if (status(r) != 200) assertThat(code(r)).isEqualTo("DELEGATION_ALREADY_EXISTS");
        }
    }

    @Test
    @DisplayName("D11 · carrera 5 · el autor revoca mientras el delegado registra asistencia: o se revoca y el delegado no entra, o entra y se bloquea")
    void authorRevokesVersusDelegateAttendance() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            resetDelegations(a.home());
            delegation(a, a.home(), coOwner1, lessee);
            Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
            List<MvcResult> rs = concurrently(List.of(() -> selfRegister(lessee, a, asm, a.home()), () -> revokeAs(coOwner1, a.home())));
            noServerErrors(rs, "ronda " + round);
            if (attendees(asm, a.home()) == 1) {
                assertThat(code(rs.get(1))).as("ronda " + round).isEqualTo("DELEGATION_REVOCATION_BLOCKED_BY_ATTENDANCE");
                assertThat(activeDelegations(a.home())).isEqualTo(1);
            } else {
                assertThat(status(rs.get(1))).as("ronda " + round).isEqualTo(200);
                assertThat(code(rs.get(0))).isEqualTo("VOTING_RIGHT_NOT_DELEGATED");
                assertThat(activeDelegations(a.home())).isZero();
            }
            endAssembly(asm);
        }
    }

    // ── D12: revocación bloqueada desde el pre-registro ───────────────────────

    @Test
    @DisplayName("D12 · pre-registro administrativo del delegado (programada): revocar → 409; sigue al iniciar; libre al finalizar; nada cambia")
    void revocationBlockedFromPreRegistration() throws Exception {
        delegation(a, a.home(), a.owner(), lessee);
        Assembly asm = assembly(a, AssemblyStatus.SCHEDULED);
        assertThat(status(adminRegister(a.admin(), a, asm, a.home(), lesseeRel.getId()))).isEqualTo(201);
        String before = fingerprint(a.home());
        String quorum = assemblyRow(asm);

        MvcResult r = revokeAs(a.owner(), a.home());
        assertThat(status(r)).isEqualTo(409);
        assertThat(code(r)).isEqualTo("DELEGATION_REVOCATION_BLOCKED_BY_ATTENDANCE");
        assertThat(fingerprint(a.home())).isEqualTo(before);
        assertThat(assemblyRow(asm)).isEqualTo(quorum);

        assertThat(status(adminAction(a, "/api/v1/assemblies/{id}/start", asm.getId()))).isEqualTo(200);
        assertThat(code(revokeAs(a.owner(), a.home()))).isEqualTo("DELEGATION_REVOCATION_BLOCKED_BY_ATTENDANCE");
        assertThat(status(adminAction(a, "/api/v1/assemblies/{id}/finish", asm.getId()))).isEqualTo(200);
        assertThat(status(revokeAs(a.owner(), a.home()))).isEqualTo(200);
        assertThat(attendees(asm, a.home())).as("la asistencia no se toca").isEqualTo(1);
        assertThat(representativeUser(asm, a.home())).isEqualTo(lessee.getId());
    }

    @Test
    @DisplayName("D12 · no bloquea: asistencia del delegado en asamblea finalizada, de otra persona, de otra unidad o de otra comunidad")
    void revocationNotBlockedWhenUnrelated() throws Exception {
        delegation(a, a.home(), a.owner(), lessee);
        attendance(assembly(a, AssemblyStatus.ENDED), a, a.home(), lesseeRel);                       // finalizada
        attendance(assembly(a, AssemblyStatus.SCHEDULED), a, a.home(), null);                       // otra persona (sin residente)
        attendance(assembly(a, AssemblyStatus.SCHEDULED), a, a.neighbourHome(), a.neighbourResident()); // otra unidad
        Assembly asmB = assembly(b, AssemblyStatus.SCHEDULED);
        assertThat(status(adminRegister(b.admin(), b, asmB, b.home(), b.ownerResident().getId()))).isEqualTo(201); // otra comunidad
        assertThat(status(revokeAs(a.owner(), a.home()))).isEqualTo(200);
    }

    @Test
    @DisplayName("D12 · carrera 6 · revocar y pre-registro administrativo del delegado a la vez: nunca asistencia del delegado con delegación revocada")
    void revocationVersusAdminPreRegistration() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            resetDelegations(a.home());
            delegation(a, a.home(), a.owner(), lessee);
            Assembly asm = assembly(a, AssemblyStatus.SCHEDULED);
            List<MvcResult> rs = concurrently(List.of(
                    () -> adminRegister(a.admin(), a, asm, a.home(), lesseeRel.getId()),
                    () -> revokeAs(a.owner(), a.home())));
            noServerErrors(rs, "ronda " + round);
            if (attendees(asm, a.home()) == 1) {
                assertThat(code(rs.get(1))).as("ronda " + round).isEqualTo("DELEGATION_REVOCATION_BLOCKED_BY_ATTENDANCE");
                assertThat(activeDelegations(a.home())).isEqualTo(1);
            } else {
                assertThat(status(rs.get(1))).as("ronda " + round).isEqualTo(200);
                assertThat(code(rs.get(0))).isEqualTo("VOTING_RIGHT_NOT_DELEGATED");
                assertThat(activeDelegations(a.home())).isZero();
            }
            assertThat(registered(asm)).isEqualTo(realAttendees(asm));
            endAssembly(asm);
        }
    }

    @Test
    @DisplayName("D12 · carrera 7 · revocar mientras se inicia la asamblea: sin interbloqueos; con pre-registro del delegado siempre 409")
    void revocationVersusStart() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            resetDelegations(a.home());
            delegation(a, a.home(), a.owner(), lessee);
            boolean delegateAttends = round % 2 == 0;
            Assembly asm = assembly(a, AssemblyStatus.SCHEDULED);
            attendance(asm, a, a.home(), delegateAttends ? lesseeRel : null);
            List<MvcResult> rs = concurrently(List.of(
                    () -> adminAction(a, "/api/v1/assemblies/{id}/start", asm.getId()),
                    () -> revokeAs(a.owner(), a.home())));
            noServerErrors(rs, "ronda " + round);
            assertThat(status(rs.get(0))).isEqualTo(200);
            if (delegateAttends) {
                assertThat(code(rs.get(1))).as("ronda " + round).isEqualTo("DELEGATION_REVOCATION_BLOCKED_BY_ATTENDANCE");
                assertThat(activeDelegations(a.home())).isEqualTo(1);
            } else {
                assertThat(status(rs.get(1))).as("ronda " + round).isEqualTo(200);
                assertThat(activeDelegations(a.home())).isZero();
            }
            assertThat(jdbc.queryForObject("select status from assemblies where id = ?", String.class, asm.getId())).isEqualTo("IN_PROGRESS");
            endAssembly(asm);
        }
    }

    // ── D13: registro administrativo ──────────────────────────────────────────

    @Test
    @DisplayName("D13 · registro administrativo válido: copropietario, arrendatario con delegación a su favor y sin persona; programada y en curso; quórum correcto")
    void adminRegistrationValid() throws Exception {
        Assembly scheduled = assembly(a, AssemblyStatus.SCHEDULED);
        assertThat(status(adminRegister(a.admin(), a, scheduled, a.home(), relationOf(coOwner2, a.home()).getId()))).isEqualTo(201);
        assertThat(representativeUser(scheduled, a.home())).isEqualTo(coOwner2.getId());
        // Cierre E5 / D14: sin persona identificada ya no se registra (antes: 201 y una representación anónima).
        MvcResult anonymous = adminRegister(a.admin(), a, scheduled, a.neighbourHome(), null);
        assertThat(status(anonymous)).isEqualTo(409);
        assertThat(code(anonymous)).isEqualTo("REPRESENTATIVE_REQUIRED");
        assertThat(registered(scheduled)).isEqualTo(1);

        Assembly live = assembly(a, AssemblyStatus.IN_PROGRESS);
        delegation(a, a.home(), a.owner(), lessee);
        assertThat(status(adminRegister(a.admin(), a, live, a.home(), lesseeRel.getId()))).isEqualTo(201);
        assertThat(registered(live)).isEqualTo(1);
        // Votación después de registrar asistencia: vota el representante registrado.
        Poll p = poll(live, a);
        assertThat(status(vote(lessee, p, a.home(), 0))).isEqualTo(200);
        assertThat(code(vote(a.owner(), p, a.home(), 1))).isIn("UNAUTHORIZED_VOTE", "PROPERTY_ALREADY_VOTED");
    }

    @Test
    @DisplayName("D13 · registro administrativo rechazado sin escrituras: persona de otra unidad, inactiva, sin derecho o de otra comunidad; asamblea finalizada; duplicado; otra comunidad")
    void adminRegistrationRejected() throws Exception {
        Assembly asm = assembly(a, AssemblyStatus.SCHEDULED);
        String quorum = assemblyRow(asm);

        // Persona de OTRA unidad de la comunidad.
        assertThat(code(adminRegister(a.admin(), a, asm, a.home(), a.neighbourResident().getId()))).isEqualTo("INVALID_UNIT_REPRESENTATION");
        // Copropietario INACTIVO.
        Resident inactive = relationOf(coOwner1, a.home());
        inactive.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(inactive);
        assertThat(code(adminRegister(a.admin(), a, asm, a.home(), inactive.getId()))).isEqualTo("INVALID_UNIT_REPRESENTATION");
        // Arrendatario sin delegación, con delegación revocada y con delegación a favor de OTRA persona.
        assertThat(code(adminRegister(a.admin(), a, asm, a.home(), lesseeRel.getId()))).isEqualTo("VOTING_RIGHT_NOT_DELEGATED");
        delegation(a, a.home(), a.owner(), lessee);
        jdbc.update("update voting_delegations set status = 'REVOKED' where property_id = ?", a.home().getId());
        assertThat(code(adminRegister(a.admin(), a, asm, a.home(), lesseeRel.getId()))).isEqualTo("VOTING_RIGHT_NOT_DELEGATED");
        User otherLessee = account(a);
        delegation(a, a.home(), a.owner(), otherLessee);
        assertThat(code(adminRegister(a.admin(), a, asm, a.home(), lesseeRel.getId()))).isEqualTo("VOTING_RIGHT_NOT_DELEGATED");
        // Propietario con la delegación vigente: no representa a la unidad.
        assertThat(code(adminRegister(a.admin(), a, asm, a.home(), a.ownerResident().getId()))).isEqualTo("VOTING_RIGHT_DELEGATED");
        resetDelegations(a.home());
        // Residente del hogar (sin derecho de voto).
        User household = account(a);
        Resident householdRel = relation(a, a.home(), household, "Residente");
        assertThat(code(adminRegister(a.admin(), a, asm, a.home(), householdRel.getId()))).isEqualTo("NO_VOTING_RIGHTS");
        // Persona, unidad o asamblea de OTRA comunidad: 404, sin revelar nada.
        assertThat(status(adminRegister(a.admin(), a, asm, a.home(), b.ownerResident().getId()))).isEqualTo(404);
        assertThat(status(adminRegister(a.admin(), a, asm, b.home(), null))).isEqualTo(404);
        assertThat(status(adminRegister(a.admin(), a, assembly(b, AssemblyStatus.SCHEDULED), a.home(), null))).isEqualTo(404);
        // Administrador de OTRA comunidad y residente: rechazados por autorización.
        assertThat(status(adminRegister(b.admin(), a, asm, a.home(), null))).isIn(401, 403);
        assertThat(status(adminRegister(a.owner(), a, asm, a.home(), null))).isEqualTo(403);
        // Asamblea FINALIZADA.
        Assembly ended = assembly(a, AssemblyStatus.ENDED);
        String endedRow = assemblyRow(ended);
        MvcResult fin = adminRegister(a.admin(), a, ended, a.home(), null);
        assertThat(status(fin)).isEqualTo(409);
        assertThat(code(fin)).isEqualTo("ASSEMBLY_ENDED");
        assertThat(assemblyRow(ended)).isEqualTo(endedRow);

        assertThat(attendees(asm, a.home())).as("ningún rechazo escribe una asistencia").isZero();
        assertThat(assemblyRow(asm)).as("quórum intacto").isEqualTo(quorum);

        // Duplicado: no se sustituye al representante ya registrado.
        assertThat(status(adminRegister(a.admin(), a, asm, a.home(), relationOf(coOwner2, a.home()).getId()))).isEqualTo(201);
        assertThat(code(adminRegister(a.admin(), a, asm, a.home(), a.ownerResident().getId()))).isEqualTo("PROPERTY_ALREADY_REGISTERED");
        assertThat(representativeUser(asm, a.home())).isEqualTo(coOwner2.getId());
        assertThat(registered(asm)).isEqualTo(1);
    }

    @Test
    @DisplayName("seguridad · auto-registro: copropietario de otra unidad o inactivo, arrendatario sin delegación (ninguna, revocada, a otro), otra comunidad, usuario desactivado, unidad revocada, asamblea ajena o finalizada")
    void selfRegistrationNegatives() throws Exception {
        Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
        String quorum = assemblyRow(asm);
        assertThat(status(selfRegister(a.neighbour(), a, asm, a.home()))).as("copropietario de otra unidad").isEqualTo(403);
        assertThat(code(selfRegister(lessee, a, asm, a.home()))).as("sin delegación").isEqualTo("VOTING_RIGHT_NOT_DELEGATED");
        delegation(a, a.home(), a.owner(), lessee);
        jdbc.update("update voting_delegations set status = 'REVOKED' where property_id = ?", a.home().getId());
        assertThat(code(selfRegister(lessee, a, asm, a.home()))).as("delegación revocada").isEqualTo("VOTING_RIGHT_NOT_DELEGATED");
        delegation(a, a.home(), a.owner(), account(a));
        assertThat(code(selfRegister(lessee, a, asm, a.home()))).as("delegación a favor de otra persona").isEqualTo("VOTING_RIGHT_NOT_DELEGATED");
        resetDelegations(a.home());
        assertThat(status(selfRegister(b.owner(), b, asm, a.home()))).as("residente de otra comunidad").isIn(403, 404);
        assertThat(status(selfRegister(a.owner(), a, assembly(b, AssemblyStatus.IN_PROGRESS), a.home()))).as("asamblea de otra comunidad").isEqualTo(404);
        assertThat(code(selfRegister(a.owner(), a, assembly(a, AssemblyStatus.ENDED), a.home()))).as("asamblea finalizada").isEqualTo("ASSEMBLY_NOT_IN_PROGRESS");
        // Copropietario inactivo (conserva otra unidad) y unidad revocada.
        Resident inactive = relationOf(coOwner1, a.home());
        inactive.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(inactive);
        relation(a, a.vacant(), coOwner1, "Propietario");
        assertThat(status(selfRegister(coOwner1, a, asm, a.home()))).as("copropietario inactivo / unidad revocada").isEqualTo(403);
        // Usuario desactivado (membresía inactiva): la petición no llega autenticada.
        jdbc.update("update memberships set status = 'INACTIVE' where user_id = ?", coOwner2.getId());
        assertThat(status(selfRegister(coOwner2, a, asm, a.home()))).as("usuario desactivado").isIn(401, 403);
        // Ningún rechazo escribe: sin asistencias de la unidad y quórum intacto (las delegaciones son datos de la prueba).
        assertThat(jdbc.queryForObject("select count(*) from assembly_attendees where property_id = ?", Integer.class, a.home().getId())).isZero();
        assertThat(assemblyRow(asm)).isEqualTo(quorum);
    }

    @Test
    @DisplayName("carrera 8 · registro administrativo y auto-registro de la misma unidad a la vez: una representación y quórum correcto")
    void adminVersusSelfRegistration() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
            List<MvcResult> rs = concurrently(List.of(
                    () -> adminRegister(a.admin(), a, asm, a.home(), relationOf(coOwner1, a.home()).getId()),
                    () -> selfRegister(coOwner2, a, asm, a.home())));
            noServerErrors(rs, "ronda " + round);
            assertThat(rs.stream().filter(r -> status(r) == 201).count()).as("ronda " + round).isEqualTo(1);
            assertThat(code(status(rs.get(0)) == 201 ? rs.get(1) : rs.get(0))).isIn("ALREADY_REGISTERED", "PROPERTY_ALREADY_REGISTERED");
            assertThat(attendees(asm, a.home())).isEqualTo(1);
            assertThat(registered(asm)).isEqualTo(1);
            assertThat(representativeUser(asm, a.home())).isIn(coOwner1.getId(), coOwner2.getId());
            endAssembly(asm);
        }
    }

    @Test
    @DisplayName("carrera 11 · registro administrativo mientras se finaliza la asamblea: o entra antes del cierre o se rechaza; quórum coherente")
    void adminRegistrationVersusFinish() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
            List<MvcResult> rs = concurrently(List.of(
                    () -> adminRegister(a.admin(), a, asm, a.home(), relationOf(coOwner1, a.home()).getId()),
                    () -> adminAction(a, "/api/v1/assemblies/{id}/finish", asm.getId())));
            noServerErrors(rs, "ronda " + round);
            assertThat(status(rs.get(1))).isEqualTo(200);
            if (status(rs.get(0)) == 201) {
                assertThat(attendees(asm, a.home())).isEqualTo(1);
            } else {
                assertThat(code(rs.get(0))).as("ronda " + round).isEqualTo("ASSEMBLY_ENDED");
                assertThat(attendees(asm, a.home())).isZero();
            }
            assertThat(jdbc.queryForObject("select status from assemblies where id = ?", String.class, asm.getId())).isEqualTo("ENDED");
            assertThat(registered(asm)).as("ronda " + round + ": el cierre no pisa el quórum").isEqualTo(realAttendees(asm));
        }
    }

    @Test
    @DisplayName("carrera 14 · cerrar la pregunta mientras se vota: o el voto entra antes del cierre o se rechaza; nunca después")
    void closePollVersusVote() throws Exception {
        Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
        attendance(asm, a, a.home(), relationOf(a.owner(), a.home()));
        for (int round = 0; round < ROUNDS; round++) {
            Poll p = poll(asm, a);
            int option = round % 2;
            List<MvcResult> rs = concurrently(List.of(
                    () -> vote(a.owner(), p, a.home(), option),
                    () -> adminAction(a, "/api/v1/assemblies/polls/{id}/close", p.getId())));
            noServerErrors(rs, "ronda " + round);
            assertThat(status(rs.get(1))).isEqualTo(200);
            int votes = jdbc.queryForObject("select count(*) from poll_votes where poll_id = ?", Integer.class, p.getId());
            if (status(rs.get(0)) == 200) {
                assertThat(votes).isEqualTo(1);
                Timestamp voted = jdbc.queryForObject("select created_at from poll_votes where poll_id = ?", Timestamp.class, p.getId());
                Timestamp closed = jdbc.queryForObject("select closed_at from polls where id = ?", Timestamp.class, p.getId());
                if (closed != null) assertThat(voted).as("ronda " + round).isBeforeOrEqualTo(closed);
            } else {
                assertThat(code(rs.get(0))).as("ronda " + round).isEqualTo("POLL_NOT_LIVE");
                assertThat(votes).isZero();
            }
            assertThat(jdbc.queryForObject("select status from polls where id = ?", String.class, p.getId())).isEqualTo("CLOSED");
        }
    }

    @Test
    @DisplayName("bloqueos · dos unidades con asistencia en las MISMAS dos asambleas delegan a la vez: sin interbloqueos ni errores 500 (bloqueo solo de la asistencia)")
    void independentUnitsSharingAssembliesDoNotDeadlock() throws Exception {
        relation(a, a.neighbourHome(), account(a), "Arrendatario");
        for (int round = 0; round < ROUNDS * 2; round++) {
            Assembly x = assembly(a, AssemblyStatus.SCHEDULED);
            Assembly y = assembly(a, AssemblyStatus.SCHEDULED);
            for (Assembly asm : List.of(x, y)) {
                attendance(asm, a, a.home(), null);
                attendance(asm, a, a.neighbourHome(), null);
            }
            List<MvcResult> rs = concurrently(List.of(
                    () -> delegateAs(a.owner(), a.home()), () -> delegateAs(a.neighbour(), a.neighbourHome()),
                    () -> delegateAs(coOwner1, a.home()), () -> delegateAs(a.neighbour(), a.neighbourHome())));
            noServerErrors(rs, "ronda " + round);
            for (MvcResult r : rs) assertThat(code(r)).as("ronda " + round).isEqualTo("DELEGATION_BLOCKED_BY_ATTENDANCE");
            assertThat(activeDelegations(a.home()) + activeDelegations(a.neighbourHome())).isZero();
            endAssembly(x);
            endAssembly(y);
        }
        assertThat(Set.of(a.home().getId(), a.neighbourHome().getId())).hasSize(2);
    }

    // ── D14: representación administrativa ───────────────────────────────────

    private MvcResult adminRegisterRaw(User admin, Side side, Assembly asm, String body) throws Exception {
        return call(admin, side, post("/api/v1/assemblies/{id}/attendees", asm.getId()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MvcResult assignRepresentative(User admin, Side side, Assembly asm, UUID attendeeId, UUID residentId) throws Exception {
        return call(admin, side, put("/api/v1/assemblies/{id}/attendees/{attendeeId}/representative", asm.getId(), attendeeId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"residentId\":\"" + residentId + "\"}"));
    }

    private UUID attendeeId(Assembly asm, Property p) {
        return jdbc.queryForObject("select id from assembly_attendees where assembly_id = ? and property_id = ?", UUID.class, asm.getId(), p.getId());
    }

    private User secondAdmin(Side side) {
        String h = "e5c-admin-" + UUID.randomUUID().toString().substring(0, 8);
        User u = userRepository.save(User.builder().email(h + "@example.test").passwordHash("{noop}x").fullName("Admin " + h)
                .phone("3000000000").build());
        membershipRepository.save(Membership.builder().user(u).tenantId(side.tenantId()).role(RoleType.COMMUNITY_ADMIN)
                .status(MembershipStatus.ACTIVE).build());
        return u;
    }

    @Test
    @DisplayName("D14 · registro administrativo: representante obligatorio y apoderado no disponible (sin modelo verificable); sin escrituras")
    void adminRegistrationRequiresRepresentative() throws Exception {
        Assembly asm = assembly(a, AssemblyStatus.SCHEDULED);
        String quorum = assemblyRow(asm);
        MvcResult none = adminRegisterRaw(a.admin(), a, asm, "{\"propertyId\":\"" + a.home().getId() + "\"}");
        assertThat(status(none)).isEqualTo(409);
        assertThat(code(none)).isEqualTo("REPRESENTATIVE_REQUIRED");
        MvcResult poa = adminRegisterRaw(a.admin(), a, asm, "{\"propertyId\":\"" + a.home().getId()
                + "\",\"isPowerOfAttorney\":true,\"representativeName\":\"Apoderado E2E\"}");
        assertThat(code(poa)).isEqualTo("POWER_OF_ATTORNEY_NOT_SUPPORTED");
        // Ni siquiera indicando una persona válida: el poder no es verificable.
        MvcResult poaWithPerson = adminRegisterRaw(a.admin(), a, asm, "{\"propertyId\":\"" + a.home().getId() + "\",\"residentId\":\""
                + relationOf(coOwner1, a.home()).getId() + "\",\"isPowerOfAttorney\":true,\"representativeName\":\"Apoderado E2E\"}");
        assertThat(code(poaWithPerson)).isEqualTo("POWER_OF_ATTORNEY_NOT_SUPPORTED");
        assertThat(attendees(asm, a.home())).isZero();
        assertThat(assemblyRow(asm)).isEqualTo(quorum);
    }

    @Test
    @DisplayName("D14 · personas elegibles: propietarios, copropietarios y arrendatario con su elegibilidad real; ajenos 404; residente 403")
    void eligibleRepresentatives() throws Exception {
        Assembly asm = assembly(a, AssemblyStatus.SCHEDULED);
        MvcResult r = call(a.admin(), a, get("/api/v1/assemblies/{id}/attendees/eligible-representatives", asm.getId()).param("propertyId", a.home().getId().toString()));
        assertThat(status(r)).isEqualTo(200);
        var list = objectMapper.readTree(r.getResponse().getContentAsString()).path("data");
        assertThat(list).hasSize(4); // propietario, 2 copropietarios y arrendatario (el censo sin cuenta no es Propietario/Arrendatario elegible)
        list.forEach(n -> {
            boolean owner = n.path("type").asText().equalsIgnoreCase("Propietario");
            assertThat(n.path("eligible").asBoolean()).as(n.toString()).isEqualTo(owner);
            if (!owner) assertThat(n.path("reasonCode").asText()).isEqualTo("VOTING_RIGHT_NOT_DELEGATED");
        });
        // Con delegación vigente se invierte: el arrendatario representa y los propietarios no.
        delegation(a, a.home(), a.owner(), lessee);
        var delegated = objectMapper.readTree(call(a.admin(), a, get("/api/v1/assemblies/{id}/attendees/eligible-representatives", asm.getId())
                .param("propertyId", a.home().getId().toString())).getResponse().getContentAsString()).path("data");
        delegated.forEach(n -> assertThat(n.path("eligible").asBoolean()).as(n.toString())
                .isEqualTo(n.path("type").asText().equalsIgnoreCase("Arrendatario")));
        assertThat(status(call(a.admin(), a, get("/api/v1/assemblies/{id}/attendees/eligible-representatives", asm.getId())
                .param("propertyId", b.home().getId().toString())))).isEqualTo(404);
        assertThat(status(call(a.admin(), a, get("/api/v1/assemblies/{id}/attendees/eligible-representatives", assembly(b, AssemblyStatus.SCHEDULED).getId())
                .param("propertyId", a.home().getId().toString())))).isEqualTo(404);
        assertThat(status(call(a.owner(), a, get("/api/v1/assemblies/{id}/attendees/eligible-representatives", asm.getId())
                .param("propertyId", a.home().getId().toString())))).isEqualTo(403);
    }

    @Test
    @DisplayName("D14 · titular presente: la administración elige a un copropietario; solo él vota en TODAS las preguntas; quórum 1")
    void adminSelectedCoOwnerRepresentsAllQuestions() throws Exception {
        Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
        assertThat(status(adminRegister(a.admin(), a, asm, a.home(), relationOf(coOwner2, a.home()).getId()))).isEqualTo(201);
        assertThat(registered(asm)).isEqualTo(1);
        for (int q = 0; q < 3; q++) {
            Poll p = poll(asm, a);
            assertThat(code(vote(a.owner(), p, a.home(), 0))).as("pregunta " + q).isEqualTo("UNAUTHORIZED_VOTE");
            assertThat(code(vote(coOwner1, p, a.home(), 0))).as("pregunta " + q).isEqualTo("UNAUTHORIZED_VOTE");
            assertThat(status(vote(coOwner2, p, a.home(), 1))).as("pregunta " + q).isEqualTo(200);
            assertThat(jdbc.queryForObject("select r.user_id from poll_votes v join residents r on r.id = v.resident_id where v.poll_id = ?",
                    UUID.class, p.getId())).as("el voto queda asociado al representante").isEqualTo(coOwner2.getId());
        }
        // Otro copropietario no sustituye al representante: ni auto-registro ni registro administrativo.
        assertThat(code(selfRegister(coOwner1, a, asm, a.home()))).isEqualTo("ALREADY_REGISTERED");
        assertThat(code(adminRegister(a.admin(), a, asm, a.home(), relationOf(a.owner(), a.home()).getId()))).isEqualTo("PROPERTY_ALREADY_REGISTERED");
        assertThat(representativeUser(asm, a.home())).isEqualTo(coOwner2.getId());
        assertThat(registered(asm)).isEqualTo(1);
    }

    @Test
    @DisplayName("D14 · asistencia anterior sin persona: nadie vota; la administración la regulariza explícitamente; sin sustituir ni atribuir votos")
    void legacyAnonymousAttendanceRegularization() throws Exception {
        Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
        attendance(asm, a, a.home(), null);
        jdbc.update("update assemblies set registered_attendees = 1 where id = ?", asm.getId());
        Poll p = poll(asm, a);
        for (User u : List.of(a.owner(), coOwner1, coOwner2)) assertThat(code(vote(u, p, a.home(), 0))).isEqualTo("REPRESENTATIVE_NOT_IDENTIFIED");
        assertThat(code(vote(a.admin(), p, a.home(), 0))).isEqualTo("ADMIN_VOTE_NOT_SUPPORTED");
        UUID att = attendeeId(asm, a.home());
        String quorum = assemblyRow(asm);

        // Rechazos sin escrituras: persona de otra unidad, de otra comunidad, residente (rol), administrador de otra comunidad.
        assertThat(code(assignRepresentative(a.admin(), a, asm, att, a.neighbourResident().getId()))).isEqualTo("INVALID_UNIT_REPRESENTATION");
        assertThat(status(assignRepresentative(a.admin(), a, asm, att, b.ownerResident().getId()))).isEqualTo(404);
        assertThat(status(assignRepresentative(a.owner(), a, asm, att, relationOf(coOwner1, a.home()).getId()))).isEqualTo(403);
        assertThat(status(assignRepresentative(b.admin(), a, asm, att, relationOf(coOwner1, a.home()).getId()))).isIn(401, 403);
        assertThat(status(assignRepresentative(b.admin(), b, assembly(b, AssemblyStatus.IN_PROGRESS), att, relationOf(coOwner1, a.home()).getId()))).isEqualTo(404);
        assertThat(jdbc.queryForObject("select resident_id from assembly_attendees where id = ?", UUID.class, att)).isNull();

        // Regularización válida: queda fijado el representante; el quórum no cambia.
        assertThat(status(assignRepresentative(a.admin(), a, asm, att, relationOf(coOwner1, a.home()).getId()))).isEqualTo(200);
        assertThat(assemblyRow(asm)).isEqualTo(quorum);
        assertThat(status(vote(coOwner1, p, a.home(), 1))).isEqualTo(200);
        assertThat(code(vote(coOwner2, poll(asm, a), a.home(), 0))).isEqualTo("UNAUTHORIZED_VOTE");
        // No se sustituye.
        assertThat(code(assignRepresentative(a.admin(), a, asm, att, relationOf(coOwner2, a.home()).getId()))).isEqualTo("REPRESENTATIVE_ALREADY_SET");

        // Con votos ya emitidos sin representante (datos anteriores), no se atribuyen a nadie.
        Assembly withVotes = assembly(a, AssemblyStatus.IN_PROGRESS);
        attendance(withVotes, a, a.neighbourHome(), null);
        Poll old = poll(withVotes, a);
        jdbc.update("insert into poll_votes (id, tenant_id, poll_id, poll_option_id, property_id, coefficient, voted_at, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, ?, ?, ?, 0.1, now(), now(), now())", a.tenantId(), old.getId(), old.getOptions().get(0).getId(), a.neighbourHome().getId());
        assertThat(code(assignRepresentative(a.admin(), a, withVotes, attendeeId(withVotes, a.neighbourHome()), a.neighbourResident().getId())))
                .isEqualTo("ATTENDANCE_HAS_VOTES");
        // Asamblea finalizada: no se modifica.
        Assembly ended = assembly(a, AssemblyStatus.ENDED);
        attendance(ended, a, a.home(), null);
        assertThat(code(assignRepresentative(a.admin(), a, ended, attendeeId(ended, a.home()), relationOf(coOwner1, a.home()).getId())))
                .isEqualTo("ASSEMBLY_ENDED");
    }

    @Test
    @DisplayName("revocación excepcional · el autor perdió la propiedad: otro copropietario activo revoca (auditado); D12 la sigue bloqueando; un autor activo no se salta")
    void exceptionalRevocation() throws Exception {
        assertThat(status(delegateAs(coOwner1, a.home()))).isEqualTo(200);
        // Autor activo: otro copropietario no revoca.
        assertThat(code(revokeAs(coOwner2, a.home()))).isEqualTo("DELEGATION_GRANTED_BY_OTHER_OWNER");
        // El autor pierde la propiedad (conserva otra unidad para seguir autenticado).
        Resident authorRel = relationOf(coOwner1, a.home());
        authorRel.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(authorRel);
        relation(a, a.vacant(), coOwner1, "Propietario");
        // D12: con el delegado pre-registrado, ni siquiera la revocación excepcional pasa.
        Assembly asm = assembly(a, AssemblyStatus.SCHEDULED);
        attendance(asm, a, a.home(), lesseeRel);
        String before = fingerprint(a.home());
        assertThat(code(revokeAs(coOwner2, a.home()))).isEqualTo("DELEGATION_REVOCATION_BLOCKED_BY_ATTENDANCE");
        assertThat(fingerprint(a.home())).isEqualTo(before);
        endAssembly(asm);
        // Unidad de otra comunidad: rechazada.
        assertThat(status(call(b.owner(), b, delete("/api/v1/properties/{propertyId}/voting-delegation", a.home().getId())))).isIn(403, 404);
        // Revocación excepcional permitida y auditada.
        assertThat(status(revokeAs(coOwner2, a.home()))).isEqualTo(200);
        assertThat(jdbc.queryForObject("select revoked_by_user_id from voting_delegations where property_id = ? and status = 'REVOKED'",
                UUID.class, a.home().getId())).isEqualTo(coOwner2.getId());
        assertThat(attendees(asm, a.home())).as("asistencias intactas").isEqualTo(1);
    }

    @Test
    @DisplayName("D14 · carrera 2 · dos administradores registran representantes distintos para la misma unidad: uno solo, sin sustitución")
    void twoAdminsRegisterDifferentRepresentatives() throws Exception {
        User admin2 = secondAdmin(a);
        for (int round = 0; round < ROUNDS; round++) {
            Assembly asm = assembly(a, AssemblyStatus.SCHEDULED);
            List<MvcResult> rs = concurrently(List.of(
                    () -> adminRegister(a.admin(), a, asm, a.home(), relationOf(coOwner1, a.home()).getId()),
                    () -> adminRegister(admin2, a, asm, a.home(), relationOf(coOwner2, a.home()).getId())));
            noServerErrors(rs, "ronda " + round);
            assertThat(rs.stream().filter(r -> status(r) == 201).count()).as("ronda " + round).isEqualTo(1);
            MvcResult winner = status(rs.get(0)) == 201 ? rs.get(0) : rs.get(1);
            assertThat(code(winner == rs.get(0) ? rs.get(1) : rs.get(0))).isEqualTo("PROPERTY_ALREADY_REGISTERED");
            assertThat(representativeUser(asm, a.home())).isEqualTo(winner == rs.get(0) ? coOwner1.getId() : coOwner2.getId());
            assertThat(registered(asm)).isEqualTo(1);
            endAssembly(asm);
        }
    }

    @Test
    @DisplayName("D14 · carreras 4/5 · apoderado administrativo con auto-registro (el poder siempre se rechaza) y registro administrativo con delegación")
    void powerOfAttorneyAndAdminVersusDelegation() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            resetDelegations(a.home()); // la delegación ganadora de la ronda anterior no debe afectar a esta
            Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
            List<MvcResult> rs = concurrently(List.of(
                    () -> adminRegisterRaw(a.admin(), a, asm, "{\"propertyId\":\"" + a.home().getId() + "\",\"isPowerOfAttorney\":true,\"representativeName\":\"Apoderado\"}"),
                    () -> selfRegister(a.owner(), a, asm, a.home())));
            noServerErrors(rs, "ronda " + round);
            assertThat(code(rs.get(0))).isEqualTo("POWER_OF_ATTORNEY_NOT_SUPPORTED");
            assertThat(status(rs.get(1))).isEqualTo(201);
            assertThat(registered(asm)).isEqualTo(1);
            endAssembly(asm);

            resetDelegations(a.home());
            Assembly scheduled = assembly(a, AssemblyStatus.SCHEDULED);
            List<MvcResult> rs2 = concurrently(List.of(
                    () -> adminRegister(a.admin(), a, scheduled, a.home(), relationOf(coOwner1, a.home()).getId()),
                    () -> delegateAs(coOwner2, a.home())));
            noServerErrors(rs2, "ronda " + round);
            int att = attendees(scheduled, a.home());
            int del = activeDelegations(a.home());
            assertThat(att + del).as("ronda " + round).isEqualTo(1);
            if (att == 1) assertThat(code(rs2.get(1))).isEqualTo("DELEGATION_BLOCKED_BY_ATTENDANCE");
            else assertThat(code(rs2.get(0))).isEqualTo("VOTING_RIGHT_DELEGATED");
            endAssembly(scheduled);
        }
    }

    @Test
    @DisplayName("D14 · carreras 9/10/11 · representante, otro copropietario y la administración votan a la vez: un voto, del representante")
    void representativeVersusOthersVoting() throws Exception {
        Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
        attendance(asm, a, a.home(), relationOf(coOwner1, a.home()));
        for (int round = 0; round < ROUNDS; round++) {
            Poll p = poll(asm, a);
            List<MvcResult> rs = concurrently(List.of(
                    () -> vote(coOwner1, p, a.home(), 0),
                    () -> vote(coOwner2, p, a.home(), 1),
                    () -> vote(a.admin(), p, a.home(), 1),
                    () -> vote(coOwner1, p, a.home(), 1)));
            noServerErrors(rs, "ronda " + round);
            assertThat(jdbc.queryForObject("select count(*) from poll_votes where poll_id = ?", Integer.class, p.getId())).isEqualTo(1);
            assertThat(code(rs.get(1))).isEqualTo("UNAUTHORIZED_VOTE");
            assertThat(code(rs.get(2))).isEqualTo("ADMIN_VOTE_NOT_SUPPORTED");
            assertThat(rs.stream().filter(r -> status(r) == 200).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select r.user_id from poll_votes v join residents r on r.id = v.resident_id where v.poll_id = ?",
                    UUID.class, p.getId())).isEqualTo(coOwner1.getId());
        }
    }

    @Test
    @DisplayName("D14 · carrera 14 · finalizar la asamblea mientras se vota: o el voto entra antes o se rechaza (ASSEMBLY_ENDED)")
    void finishVersusVote() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
            attendance(asm, a, a.home(), relationOf(coOwner1, a.home()));
            Poll p = poll(asm, a);
            List<MvcResult> rs = concurrently(List.of(
                    () -> vote(coOwner1, p, a.home(), 0),
                    () -> adminAction(a, "/api/v1/assemblies/{id}/finish", asm.getId())));
            noServerErrors(rs, "ronda " + round);
            assertThat(status(rs.get(1))).isEqualTo(200);
            int votes = jdbc.queryForObject("select count(*) from poll_votes where poll_id = ?", Integer.class, p.getId());
            if (status(rs.get(0)) == 200) {
                assertThat(votes).isEqualTo(1);
                Timestamp voted = jdbc.queryForObject("select created_at from poll_votes where poll_id = ?", Timestamp.class, p.getId());
                Timestamp finished = jdbc.queryForObject("select updated_at from assemblies where id = ?", Timestamp.class, asm.getId());
                assertThat(voted).as("ronda " + round + ": el voto es anterior a la finalización").isBeforeOrEqualTo(finished);
            } else {
                assertThat(code(rs.get(0))).as("ronda " + round).isEqualTo("ASSEMBLY_ENDED");
                assertThat(votes).isZero();
            }
        }
    }

    @Test
    @DisplayName("cierre E5 · GET /assemblies?status=IN_PROGRESS encuentra la asamblea en curso aunque haya más de una página; solo de la comunidad")
    void activeAssemblyBeyondFirstPage() throws Exception {
        for (int i = 0; i < 25; i++) assembly(a, AssemblyStatus.ENDED);
        Assembly live = assembly(a, AssemblyStatus.IN_PROGRESS);
        assembly(b, AssemblyStatus.IN_PROGRESS);
        var page = objectMapper.readTree(call(a.owner(), a, get("/api/v1/assemblies").param("status", "IN_PROGRESS").param("size", "5"))
                .getResponse().getContentAsString()).path("data").path("content");
        assertThat(page).singleElement().satisfies(n -> assertThat(n.path("id").asText()).isEqualTo(live.getId().toString()));
        assertThat(status(call(a.owner(), a, get("/api/v1/assemblies").param("status", "NOPE")))).isEqualTo(400);
    }
}
