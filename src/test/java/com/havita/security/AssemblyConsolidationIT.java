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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/**
 * Fase 2 / E5 — consolidacion de asambleas:
 * <ul>
 *   <li>D9: no se revoca una delegacion cuyo delegado ya registro asistencia por la unidad en una asamblea EN CURSO;</li>
 *   <li>D10: no se crea una delegacion si la unidad tiene asistencia en una asamblea PROGRAMADA (o en curso);</li>
 *   <li>copropietarios reconocidos en la auto-inscripcion, con una sola representacion por unidad;</li>
 *   <li>asistencia por varias unidades ({@code GET /assemblies/{id}/my-attendance-units});</li>
 *   <li>carreras con hilos, conexiones y transacciones independientes en PostgreSQL, repetidas y verificando la base.</li>
 * </ul>
 */
class AssemblyConsolidationIT extends PostgresIntegrationTest {

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
    private User tenantOfHome;
    private Resident tenantOfHomeRel;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        tenantOfHome = account(a);
        tenantOfHomeRel = relation(a, a.home(), tenantOfHome, "Arrendatario");
    }

    // ── datos ─────────────────────────────────────────────────────────────────

    private Assembly assembly(Side side, AssemblyStatus status) {
        Assembly x = Assembly.builder().title("Asamblea E5").assemblyDate(LocalDate.now()).startTime(LocalTime.of(19, 0))
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
        String h = "e5-" + UUID.randomUUID().toString().substring(0, 8);
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

    private MvcResult delegateAs(User owner, Side side, Property p) throws Exception {
        return call(owner, side, post("/api/v1/properties/{propertyId}/voting-delegation", p.getId()));
    }

    private MvcResult revokeAs(User owner, Side side, Property p) throws Exception {
        return call(owner, side, delete("/api/v1/properties/{propertyId}/voting-delegation", p.getId()));
    }

    private MvcResult selfRegister(User u, Side side, Assembly asm, Property p) throws Exception {
        return call(u, side, post("/api/v1/assemblies/{id}/attendees/self-register", asm.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"propertyId\":\"" + p.getId() + "\"}"));
    }

    /** Cierre E5 / D14: el registro administrativo identifica al representante (el propietario de la fixture). */
    private MvcResult adminRegister(Side side, Assembly asm, Property p) throws Exception {
        Resident rep = p.getId().equals(side.home().getId()) ? side.ownerResident()
                : p.getId().equals(side.neighbourHome().getId()) ? side.neighbourResident() : null;
        String body = rep == null ? "{\"propertyId\":\"" + p.getId() + "\"}"
                : "{\"propertyId\":\"" + p.getId() + "\",\"residentId\":\"" + rep.getId() + "\"}";
        return call(side.admin(), side, post("/api/v1/assemblies/{id}/attendees", asm.getId())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MvcResult vote(User u, Side side, Poll p, Property unit, int option) throws Exception {
        UUID opt = p.getOptions().get(option).getId();
        return call(u, side, post("/api/v1/assemblies/polls/{id}/vote", p.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"" + unit.getId() + "\",\"optionIds\":[\"" + opt + "\"]}"));
    }

    private MvcResult myAttendance(User u, Side side, Assembly asm) throws Exception {
        return call(u, side, get("/api/v1/assemblies/{id}/my-attendance-units", asm.getId()));
    }

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
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

    private int votes(Poll p, Property unit) {
        return jdbc.queryForObject("select count(*) from poll_votes where poll_id = ? and property_id = ?",
                Integer.class, p.getId(), unit.getId());
    }

    /** Huella de delegaciones, asistencias y votos de la unidad y de la asamblea (un rechazo no escribe nada). */
    private String fingerprint(Property p) {
        return jdbc.queryForObject("select md5(coalesce((select string_agg(to_jsonb(d)::text, ',' order by d.id) from voting_delegations d "
                + "where d.property_id = ?), '') || coalesce((select string_agg(to_jsonb(t)::text, ',' order by t.id) "
                + "from assembly_attendees t where t.property_id = ?), '') || coalesce((select string_agg(to_jsonb(v)::text, ',' "
                + "order by v.id) from poll_votes v where v.property_id = ?), ''))", String.class, p.getId(), p.getId(), p.getId());
    }

    private String assemblyRow(Assembly asm) {
        return jdbc.queryForObject("select md5(to_jsonb(x)::text) from assemblies x where id = ?", String.class, asm.getId());
    }

    private Map<String, JsonNode> unitsById(MvcResult r) throws Exception {
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(200);
        Map<String, JsonNode> out = new HashMap<>();
        objectMapper.readTree(r.getResponse().getContentAsString()).path("data").forEach(n -> out.put(n.path("propertyId").asText(), n));
        return out;
    }

    private void endAssembly(Assembly asm) {
        jdbc.update("update assemblies set status = 'ENDED' where id = ?", asm.getId());
    }

    // ── D9: revocación restringida ────────────────────────────────────────────

    @Test
    @DisplayName("D9 · el delegado registró asistencia en una asamblea en curso: revocar → 409 DELEGATION_REVOCATION_BLOCKED_BY_ATTENDANCE, nada cambia y el delegado sigue votando")
    void revocationBlockedByDelegateAttendance() throws Exception {
        assertThat(status(delegateAs(a.owner(), a, a.home()))).isEqualTo(200);
        Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
        assertThat(status(selfRegister(tenantOfHome, a, asm, a.home()))).isEqualTo(201);
        String before = fingerprint(a.home());
        String quorum = assemblyRow(asm);

        MvcResult r = revokeAs(a.owner(), a, a.home());
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("DELEGATION_REVOCATION_BLOCKED_BY_ATTENDANCE");
        assertThat(fingerprint(a.home())).as("ni delegación, ni asistencia, ni votos cambian").isEqualTo(before);
        assertThat(assemblyRow(asm)).as("quórum intacto").isEqualTo(quorum);
        assertThat(activeDelegations(a.home())).isEqualTo(1);

        // El derecho de voto se mantiene: vota el delegado; el propietario no (nada pasa al propietario); un voto por unidad.
        Poll p = poll(asm, a);
        assertThat(status(vote(tenantOfHome, a, p, a.home(), 0))).isEqualTo(200);
        assertThat(code(vote(a.owner(), a, p, a.home(), 1))).isIn("UNAUTHORIZED_VOTE", "PROPERTY_ALREADY_VOTED");
        assertThat(votes(p, a.home())).isEqualTo(1);

        // Al finalizar la asamblea, la revocación vuelve a estar permitida.
        endAssembly(asm);
        assertThat(status(revokeAs(a.owner(), a, a.home()))).isEqualTo(200);
        assertThat(activeDelegations(a.home())).isZero();
    }

    @Test
    @DisplayName("D9 · no bloquea sin relación: asistencia de otra persona, asamblea finalizada, otra unidad del propietario y otra comunidad (D12: la programada sí)")
    void revocationNotBlockedWhenUnrelated() throws Exception {
        // Asistencia en curso SIN residente (registro administrativo): no es asistencia del delegado.
        delegation(a, a.home(), a.owner(), tenantOfHome);
        attendance(assembly(a, AssemblyStatus.IN_PROGRESS), a, a.home(), null);
        assertThat(status(revokeAs(a.owner(), a, a.home()))).isEqualTo(200);

        // Asistencia del delegado en una asamblea FINALIZADA: no bloquea. (Cierre E5 / D12: en una PROGRAMADA sí bloquea;
        // ver AssemblyRepresentationIT.)
        delegation(a, a.home(), a.owner(), tenantOfHome);
        Assembly ended = assembly(a, AssemblyStatus.ENDED);
        attendance(ended, a, a.home(), tenantOfHomeRel);
        assertThat(status(revokeAs(a.owner(), a, a.home()))).isEqualTo(200);
        delegation(a, a.home(), a.owner(), tenantOfHome);
        Assembly scheduled = assembly(a, AssemblyStatus.SCHEDULED);
        attendance(scheduled, a, a.home(), tenantOfHomeRel);
        assertThat(code(revokeAs(a.owner(), a, a.home()))).isEqualTo("DELEGATION_REVOCATION_BLOCKED_BY_ATTENDANCE");
        endAssembly(scheduled);
        assertThat(status(revokeAs(a.owner(), a, a.home()))).isEqualTo(200);

        // Otra unidad del propietario: el delegado de la unidad 1 asiste; la unidad 2 se revoca con normalidad.
        relation(a, a.vacant(), a.owner(), "Propietario");
        User tenant2 = account(a);
        relation(a, a.vacant(), tenant2, "Arrendatario");
        delegation(a, a.home(), a.owner(), tenantOfHome);
        delegation(a, a.vacant(), a.owner(), tenant2);
        Assembly live = assembly(a, AssemblyStatus.IN_PROGRESS);
        assertThat(status(selfRegister(tenantOfHome, a, live, a.home()))).isEqualTo(201);
        assertThat(code(revokeAs(a.owner(), a, a.home()))).isEqualTo("DELEGATION_REVOCATION_BLOCKED_BY_ATTENDANCE");
        assertThat(status(revokeAs(a.owner(), a, a.vacant()))).isEqualTo(200);

        // Otra comunidad: su delegación se revoca aunque la de A esté bloqueada.
        User tenantB = account(b);
        relation(b, b.home(), tenantB, "Arrendatario");
        delegation(b, b.home(), b.owner(), tenantB);
        assertThat(status(revokeAs(b.owner(), b, b.home()))).isEqualTo(200);
    }

    @Test
    @DisplayName("D9 · usuarios no autorizados no revocan (ni descubren el bloqueo): vecino 403, arrendatario NOT_THE_OWNER, otra comunidad rechazada")
    void revocationUnauthorized() throws Exception {
        delegation(a, a.home(), a.owner(), tenantOfHome);
        String before = fingerprint(a.home());
        assertThat(status(revokeAs(a.neighbour(), a, a.home()))).isEqualTo(403);
        assertThat(code(revokeAs(tenantOfHome, a, a.home()))).isEqualTo("NOT_THE_OWNER");
        assertThat(status(revokeAs(b.owner(), a, a.home()))).isIn(401, 403, 404);
        assertThat(fingerprint(a.home())).isEqualTo(before);
    }

    // ── D10: asistencia administrativa previa ─────────────────────────────────

    @Test
    @DisplayName("D10 · pre-registro administrativo en asamblea PROGRAMADA: delegar → 409 DELEGATION_BLOCKED_BY_ATTENDANCE; sigue al iniciar; libre al finalizar")
    void delegationBlockedByScheduledAdminAttendance() throws Exception {
        Assembly asm = assembly(a, AssemblyStatus.SCHEDULED);
        assertThat(status(adminRegister(a, asm, a.home()))).isEqualTo(201);
        String before = fingerprint(a.home());
        String quorum = assemblyRow(asm);

        MvcResult r = delegateAs(a.owner(), a, a.home());
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("DELEGATION_BLOCKED_BY_ATTENDANCE");
        assertThat(fingerprint(a.home())).isEqualTo(before);
        assertThat(assemblyRow(asm)).isEqualTo(quorum);

        jdbc.update("update assemblies set status = 'IN_PROGRESS' where id = ?", asm.getId());
        assertThat(code(delegateAs(a.owner(), a, a.home()))).isEqualTo("DELEGATION_BLOCKED_BY_ATTENDANCE");
        assertThat(attendees(asm, a.home())).as("la asistencia no se toca").isEqualTo(1);

        endAssembly(asm);
        assertThat(status(delegateAs(a.owner(), a, a.home()))).isEqualTo(200);
        assertThat(attendees(asm, a.home())).isEqualTo(1);
    }

    @Test
    @DisplayName("D10 · no bloquea: asistencia programada de otra unidad o de otra comunidad, ni asistencia histórica de una asamblea finalizada")
    void delegationNotBlockedWhenUnrelated() throws Exception {
        assertThat(status(adminRegister(a, assembly(a, AssemblyStatus.SCHEDULED), a.neighbourHome()))).isEqualTo(201);
        assertThat(status(adminRegister(b, assembly(b, AssemblyStatus.SCHEDULED), b.home()))).isEqualTo(201);
        attendance(assembly(a, AssemblyStatus.ENDED), a, a.home(), null);
        assertThat(status(delegateAs(a.owner(), a, a.home()))).isEqualTo(200);
        assertThat(activeDelegations(a.home())).isEqualTo(1);
    }

    // ── copropietarios ────────────────────────────────────────────────────────

    @Test
    @DisplayName("copropietarios · cualquiera de ellos se reconoce; una sola representación de la unidad, un voto y quórum sin duplicar")
    void coOwners() throws Exception {
        User coOwner = account(a);
        relation(a, a.home(), coOwner, "Propietario");
        Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);

        // El copropietario (no necesariamente el "primero") registra la asistencia.
        assertThat(status(selfRegister(coOwner, a, asm, a.home()))).isEqualTo(201);
        String quorum = assemblyRow(asm);
        MvcResult second = selfRegister(a.owner(), a, asm, a.home());
        assertThat(code(second)).isEqualTo("ALREADY_REGISTERED");
        assertThat(attendees(asm, a.home())).isEqualTo(1);
        assertThat(assemblyRow(asm)).as("el quórum no cuenta dos veces la unidad").isEqualTo(quorum);

        // Vota quien representa a la unidad; el otro copropietario no; un voto por unidad y pregunta.
        Poll p = poll(asm, a);
        assertThat(code(vote(a.owner(), a, p, a.home(), 0))).isEqualTo("UNAUTHORIZED_VOTE");
        assertThat(status(vote(coOwner, a, p, a.home(), 1))).isEqualTo(200);
        assertThat(code(vote(coOwner, a, p, a.home(), 1))).isEqualTo("PROPERTY_ALREADY_VOTED");
        assertThat(votes(p, a.home())).isEqualTo(1);

        // Con delegación vigente, ningún copropietario se registra (la delegación se respeta).
        endAssembly(asm);
        delegation(a, a.home(), a.owner(), tenantOfHome);
        Assembly asm2 = assembly(a, AssemblyStatus.IN_PROGRESS);
        assertThat(code(selfRegister(coOwner, a, asm2, a.home()))).isEqualTo("VOTING_RIGHT_DELEGATED");
        assertThat(code(selfRegister(a.owner(), a, asm2, a.home()))).isEqualTo("VOTING_RIGHT_DELEGATED");
    }

    @Test
    @DisplayName("auto-inscripción · arrendatario sin delegación a su favor y residente sin derecho de voto: rechazados sin escritura")
    void selfRegisterWithoutVotingRight() throws Exception {
        Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
        assertThat(code(selfRegister(tenantOfHome, a, asm, a.home()))).isEqualTo("VOTING_RIGHT_NOT_DELEGATED");
        User household = account(a);
        relation(a, a.home(), household, "Residente");
        assertThat(code(selfRegister(household, a, asm, a.home()))).isEqualTo("NO_VOTING_RIGHTS");
        // Unidad ajena: 403 sin revelar si la unidad tiene asistencia.
        assertThat(status(selfRegister(a.owner(), a, asm, a.neighbourHome()))).isEqualTo(403);
        assertThat(attendees(asm, a.home()) + attendees(asm, a.neighbourHome())).isZero();
    }

    // ── asistencia por varias unidades ────────────────────────────────────────

    @Test
    @DisplayName("my-attendance-units · varias unidades: pendiente, delegada y registrada; registrar una no toca las otras; solo unidades propias")
    void myAttendanceUnits() throws Exception {
        relation(a, a.vacant(), a.owner(), "Propietario");
        User tenant2 = account(a);
        relation(a, a.vacant(), tenant2, "Arrendatario");
        delegation(a, a.vacant(), a.owner(), tenant2);
        Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);

        Map<String, JsonNode> units = unitsById(myAttendance(a.owner(), a, asm));
        assertThat(units).containsOnlyKeys(a.home().getId().toString(), a.vacant().getId().toString());
        assertThat(units.get(a.home().getId().toString()).path("status").asText()).isEqualTo("PENDING");
        assertThat(units.get(a.home().getId().toString()).path("canRegister").asBoolean()).isTrue();
        JsonNode delegated = units.get(a.vacant().getId().toString());
        assertThat(delegated.path("status").asText()).isEqualTo("NOT_ELIGIBLE");
        assertThat(delegated.path("reasonCode").asText()).isEqualTo("VOTING_RIGHT_DELEGATED");

        assertThat(status(selfRegister(a.owner(), a, asm, a.home()))).isEqualTo(201);
        units = unitsById(myAttendance(a.owner(), a, asm));
        assertThat(units.get(a.home().getId().toString()).path("status").asText()).isEqualTo("REGISTERED");
        assertThat(units.get(a.home().getId().toString()).path("registeredByMe").asBoolean()).isTrue();
        assertThat(units.get(a.vacant().getId().toString()).path("status").asText()).isEqualTo("NOT_ELIGIBLE");
        assertThat(attendees(asm, a.vacant())).isZero();

        // El delegado ve su unidad habilitada; la asistencia del propietario en otra unidad no le afecta.
        Map<String, JsonNode> forTenant = unitsById(myAttendance(tenant2, a, asm));
        assertThat(forTenant).containsOnlyKeys(a.vacant().getId().toString());
        assertThat(forTenant.get(a.vacant().getId().toString()).path("status").asText()).isEqualTo("PENDING");

        // Asamblea programada: CLOSED; asamblea de otra comunidad: 404; gestión: 403 (endpoint del residente).
        Map<String, JsonNode> scheduled = unitsById(myAttendance(a.owner(), a, assembly(a, AssemblyStatus.SCHEDULED)));
        assertThat(scheduled.values()).allSatisfy(n -> assertThat(n.path("status").asText()).isEqualTo("CLOSED"));
        assertThat(status(myAttendance(a.owner(), a, assembly(b, AssemblyStatus.IN_PROGRESS)))).isEqualTo(404);
        assertThat(status(myAttendance(a.admin(), a, asm))).isEqualTo(403);
    }

    @Test
    @DisplayName("my-attendance-units · unidad revocada: desaparece del listado y su registro se rechaza")
    void myAttendanceUnitsRevoked() throws Exception {
        relation(a, a.vacant(), a.owner(), "Propietario");
        Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
        Resident own = residentRepository.findById(a.ownerResident().getId()).orElseThrow();
        own.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(own);
        assertThat(unitsById(myAttendance(a.owner(), a, asm))).containsOnlyKeys(a.vacant().getId().toString());
        assertThat(status(selfRegister(a.owner(), a, asm, a.home()))).isEqualTo(403);
        assertThat(attendees(asm, a.home())).isZero();
    }

    // ── concurrencia ──────────────────────────────────────────────────────────

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

    @Test
    @DisplayName("carrera 1 · delegar y asistencia ADMINISTRATIVA (asamblea programada) a la vez: nunca una delegación creada después de la asistencia")
    void delegationVersusAdminAttendance() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("update voting_delegations set status = 'REVOKED' where property_id = ? and status = 'ACTIVE'", a.home().getId());
            Assembly asm = assembly(a, AssemblyStatus.SCHEDULED);
            List<MvcResult> rs = concurrently(List.of(
                    () -> adminRegister(a, asm, a.home()),
                    () -> delegateAs(a.owner(), a, a.home())));
            // Cierre E5 / D13-D14: el registro administrativo identifica al propietario como representante; con la
            // delegación ya creada, el propietario no representa a la unidad. Exactamente una de las dos operaciones gana.
            int att = attendees(asm, a.home());
            int del = activeDelegations(a.home());
            assertThat(att + del).as("ronda " + round).isEqualTo(1);
            if (att == 1) {
                assertThat(status(rs.get(0))).isEqualTo(201);
                assertThat(code(rs.get(1))).as("ronda " + round).isEqualTo("DELEGATION_BLOCKED_BY_ATTENDANCE");
            } else {
                assertThat(status(rs.get(1))).isEqualTo(200);
                assertThat(code(rs.get(0))).as("ronda " + round).isEqualTo("VOTING_RIGHT_DELEGATED");
            }
            endAssembly(asm);
        }
    }

    @Test
    @DisplayName("carrera 3 · revocar y asistencia del delegado a la vez: o se revoca y el delegado no entra, o entra y la revocación se bloquea")
    void revocationVersusDelegateAttendance() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("update voting_delegations set status = 'REVOKED' where property_id = ? and status = 'ACTIVE'", a.home().getId());
            delegation(a, a.home(), a.owner(), tenantOfHome);
            Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
            List<MvcResult> rs = concurrently(List.of(
                    () -> selfRegister(tenantOfHome, a, asm, a.home()),
                    () -> revokeAs(a.owner(), a, a.home())));
            int att = attendees(asm, a.home());
            int del = activeDelegations(a.home());
            if (att == 1) {
                assertThat(status(rs.get(0))).as("ronda " + round).isEqualTo(201);
                assertThat(code(rs.get(1))).isEqualTo("DELEGATION_REVOCATION_BLOCKED_BY_ATTENDANCE");
                assertThat(del).as("ronda " + round + ": la delegación sigue vigente").isEqualTo(1);
            } else {
                assertThat(status(rs.get(1))).as("ronda " + round).isEqualTo(200);
                assertThat(code(rs.get(0))).isEqualTo("VOTING_RIGHT_NOT_DELEGATED");
                assertThat(del).isZero();
            }
            endAssembly(asm);
        }
    }

    @Test
    @DisplayName("carrera 4 · revocar y voto del delegado a la vez: con asistencia del delegado, la revocación se bloquea y hay un voto; sin ella, el voto nunca es posterior a la revocación")
    void revocationVersusDelegateVote() throws Exception {
        // a) El delegado registró asistencia: revocar siempre 409 (D9) y su voto entra.
        delegation(a, a.home(), a.owner(), tenantOfHome);
        Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
        assertThat(status(selfRegister(tenantOfHome, a, asm, a.home()))).isEqualTo(201);
        for (int round = 0; round < ROUNDS; round++) {
            Poll p = poll(asm, a);
            List<MvcResult> rs = concurrently(List.of(
                    () -> vote(tenantOfHome, a, p, a.home(), 0),
                    () -> revokeAs(a.owner(), a, a.home())));
            assertThat(status(rs.get(0))).as("ronda " + round).isEqualTo(200);
            assertThat(code(rs.get(1))).isEqualTo("DELEGATION_REVOCATION_BLOCKED_BY_ATTENDANCE");
            assertThat(votes(p, a.home())).isEqualTo(1);
            assertThat(activeDelegations(a.home())).isEqualTo(1);
        }
        endAssembly(asm);

        // b) Cierre E5 / D14: asistencia anterior a D14 SIN persona identificada. No es asistencia del delegado, así que la
        //    revocación está permitida; y no otorga derecho de voto a nadie: el delegado nunca vota por esa asistencia.
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("update voting_delegations set status = 'REVOKED' where property_id = ? and status = 'ACTIVE'", a.home().getId());
            delegation(a, a.home(), a.owner(), tenantOfHome);
            Assembly live = assembly(a, AssemblyStatus.IN_PROGRESS);
            attendance(live, a, a.home(), null);
            Poll p = poll(live, a);
            List<MvcResult> rs = concurrently(List.of(
                    () -> vote(tenantOfHome, a, p, a.home(), 0),
                    () -> revokeAs(a.owner(), a, a.home())));
            assertThat(status(rs.get(1))).as("ronda " + round).isEqualTo(200);
            assertThat(code(rs.get(0))).as("ronda " + round).isEqualTo("REPRESENTATIVE_NOT_IDENTIFIED");
            assertThat(votes(p, a.home())).isZero();
            endAssembly(live);
        }
    }

    @Test
    @DisplayName("carreras 6/7 · unidades distintas y comunidades distintas a la vez: todas las operaciones legítimas entran, sin bloqueos cruzados")
    void independentUnitsAndCommunities() throws Exception {
        relation(a, a.vacant(), a.owner(), "Propietario");
        User tenant2 = account(a);
        relation(a, a.vacant(), tenant2, "Arrendatario");
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("update voting_delegations set status = 'REVOKED' where property_id = ? and status = 'ACTIVE'", a.vacant().getId());
            Assembly asmA = assembly(a, AssemblyStatus.IN_PROGRESS);
            Assembly asmB = assembly(b, AssemblyStatus.IN_PROGRESS);
            List<MvcResult> rs = concurrently(List.of(
                    () -> selfRegister(a.owner(), a, asmA, a.home()),        // unidad 1 de A
                    () -> delegateAs(a.owner(), a, a.vacant()),              // unidad 2 de A (sin asistencia)
                    () -> selfRegister(a.neighbour(), a, asmA, a.neighbourHome()), // otra unidad de A
                    () -> selfRegister(b.owner(), b, asmB, b.home())));      // comunidad B
            assertThat(status(rs.get(0))).as("ronda " + round + " " + rs.get(0).getResponse().getContentAsString()).isEqualTo(201);
            assertThat(status(rs.get(1))).as("ronda " + round + " " + rs.get(1).getResponse().getContentAsString()).isEqualTo(200);
            assertThat(status(rs.get(2))).as("ronda " + round + " " + rs.get(2).getResponse().getContentAsString()).isEqualTo(201);
            assertThat(status(rs.get(3))).as("ronda " + round + " " + rs.get(3).getResponse().getContentAsString()).isEqualTo(201);
            assertThat(attendees(asmA, a.home()) + attendees(asmA, a.neighbourHome()) + attendees(asmB, b.home())).isEqualTo(3);
            assertThat(activeDelegations(a.vacant())).isEqualTo(1);
            Integer registered = jdbc.queryForObject("select registered_attendees from assemblies where id = ?", Integer.class, asmA.getId());
            assertThat(registered).as("quórum de A con sus dos unidades").isEqualTo(2);
            endAssembly(asmA);
            endAssembly(asmB);
        }
    }

    @Test
    @DisplayName("carrera 8 · reintentos concurrentes de la misma operación: una asistencia, una delegación, una revocación")
    void concurrentRetries() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("update voting_delegations set status = 'REVOKED' where property_id = ? and status = 'ACTIVE'", a.home().getId());
            List<MvcResult> del = concurrently(List.of(
                    () -> delegateAs(a.owner(), a, a.home()), () -> delegateAs(a.owner(), a, a.home()),
                    () -> delegateAs(a.owner(), a, a.home())));
            assertThat(del.stream().filter(r -> status(r) == 200).count()).as("ronda " + round).isEqualTo(1);
            for (MvcResult r : del) if (status(r) != 200) assertThat(code(r)).isEqualTo("DELEGATION_ALREADY_EXISTS");
            assertThat(activeDelegations(a.home())).isEqualTo(1);

            List<MvcResult> rev = concurrently(List.of(
                    () -> revokeAs(a.owner(), a, a.home()), () -> revokeAs(a.owner(), a, a.home()),
                    () -> revokeAs(a.owner(), a, a.home())));
            assertThat(rev.stream().filter(r -> status(r) == 200).count()).as("ronda " + round).isEqualTo(1);
            for (MvcResult r : rev) if (status(r) != 200) assertThat(code(r)).isEqualTo("NO_ACTIVE_DELEGATION");
            assertThat(activeDelegations(a.home())).isZero();

            Assembly asm = assembly(a, AssemblyStatus.IN_PROGRESS);
            List<MvcResult> reg = concurrently(List.of(
                    () -> selfRegister(a.owner(), a, asm, a.home()), () -> selfRegister(a.owner(), a, asm, a.home()),
                    () -> selfRegister(a.owner(), a, asm, a.home())));
            assertThat(reg.stream().filter(r -> status(r) == 201).count()).as("ronda " + round).isEqualTo(1);
            for (MvcResult r : reg) if (status(r) != 201) assertThat(code(r)).isEqualTo("ALREADY_REGISTERED");
            assertThat(attendees(asm, a.home())).isEqualTo(1);
            Integer registered = jdbc.queryForObject("select registered_attendees from assemblies where id = ?", Integer.class, asm.getId());
            assertThat(registered).isEqualTo(1);
            endAssembly(asm);
        }
    }
}
