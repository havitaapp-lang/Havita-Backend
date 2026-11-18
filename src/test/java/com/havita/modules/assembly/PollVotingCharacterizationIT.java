package com.havita.modules.assembly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.assembly.domain.Assembly;
import com.havita.modules.assembly.domain.VotingDelegation;
import com.havita.modules.assembly.domain.VotingDelegationStatus;
import com.havita.modules.assembly.infrastructure.AssemblyRepository;
import com.havita.modules.assembly.infrastructure.VotingDelegationRepository;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Fase 2 / P3-E — PRUEBAS DE CARACTERIZACION del voto actual (PollService.castVote). Documentan el comportamiento
 * OBSERVADO sin modificar el codigo productivo; sirven de red de seguridad para el diseno del voto por poder (P4+).
 *
 * Convencion: las pruebas "observado = esperado" verifican reglas que ya se cumplen. Las marcadas "BRECHA (observado)"
 * reproducen en aislamiento una carencia documentada en tasks/P3_PROXY_VOTING_DESIGN.md; NO la legitiman como
 * comportamiento deseado: la correccion propuesta esta en ese documento y requiere autorizacion.
 *
 * Funciona en los dos modos de la suite (esquema de Hibernate o de Flyway) y con los poderes desactivados.
 */
class PollVotingCharacterizationIT extends PostgresIntegrationTest {

    private static final int ROUNDS = 8;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private AssemblyRepository assemblyRepository;
    @Autowired private VotingDelegationRepository delegationRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private PropertyRepository propertyRepository;
    @Autowired private JdbcTemplate jdbc;

    private Side a;
    private Side b;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
    }

    // ── utilidades ────────────────────────────────────────────────────────────

    private MockHttpServletResponse send(User u, Side s, MockHttpServletRequestBuilder req) throws Exception {
        return send(fixture.bearer(u), s, req);
    }

    private MockHttpServletResponse send(String bearer, Side s, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", bearer).header("X-Tenant-ID", s.tenantId().toString())).andReturn().getResponse();
    }

    private JsonNode json(MockHttpServletResponse r) throws Exception {
        return objectMapper.readTree(r.getContentAsString());
    }

    private void assertCode(MockHttpServletResponse r, int status, String code) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        if (code != null) assertThat(json(r).path("code").asText()).isEqualTo(code);
    }

    private User member(Side s, RoleType role) {
        User u = userRepository.save(User.builder().email("p3-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .passwordHash("{noop}x").fullName("Persona P3").phone("3000000000").build());
        membershipRepository.save(Membership.builder().user(u).tenantId(s.tenantId()).role(role).status(MembershipStatus.ACTIVE).build());
        return u;
    }

    private Resident relation(Side s, Property unit, User u, String type) {
        Resident r = Resident.builder().property(unit).user(u).name(u.getFullName()).type(type).email(u.getEmail())
                .status(ResidentStatus.ACTIVE).build();
        r.setTenantId(s.tenantId());
        return residentRepository.save(r);
    }

    /** Asamblea EN CURSO con una pregunta SINGLE_CHOICE abierta. [asamblea, pregunta, opcion "Si", opcion "No"]. */
    private UUID[] livePoll(Side s) throws Exception {
        Assembly x = Assembly.builder().title("Asamblea P3").assemblyDate(LocalDate.now()).startTime(LocalTime.NOON).build();
        x.setTenantId(s.tenantId());
        x = assemblyRepository.save(x);
        assertCode(send(s.admin(), s, post("/api/v1/assemblies/{id}/start", x.getId())), 200, null);
        MockHttpServletResponse poll = send(s.admin(), s, post("/api/v1/assemblies/polls").contentType(MediaType.APPLICATION_JSON)
                .content("{\"assemblyId\":\"" + x.getId() + "\",\"question\":\"Aprobar presupuesto\",\"type\":\"SINGLE_CHOICE\",\"options\":[\"Si\",\"No\"]}"));
        assertThat(poll.getStatus()).as(poll.getContentAsString()).isIn(200, 201);
        UUID pollId = UUID.fromString(json(poll).at("/data/id").asText());
        assertCode(send(s.admin(), s, post("/api/v1/assemblies/polls/{p}/open", pollId)), 200, null);
        return new UUID[]{x.getId(), pollId, UUID.fromString(json(poll).at("/data/options/0/id").asText()),
                UUID.fromString(json(poll).at("/data/options/1/id").asText())};
    }

    private MockHttpServletResponse selfRegister(User u, Side s, UUID assemblyId, Property unit) throws Exception {
        return send(u, s, post("/api/v1/assemblies/{id}/attendees/self-register", assemblyId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"propertyId\":\"" + unit.getId() + "\"}"));
    }

    private MockHttpServletResponse vote(User u, Side s, UUID pollId, Property unit, UUID... options) throws Exception {
        return vote(fixture.bearer(u), s, pollId, unit, options);
    }

    private MockHttpServletResponse vote(String bearer, Side s, UUID pollId, Property unit, UUID... options) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (UUID o : options) ids.append(ids.length() > 0 ? "," : "").append('"').append(o).append('"');
        return send(bearer, s, post("/api/v1/assemblies/polls/{p}/vote", pollId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"" + unit.getId() + "\",\"optionIds\":[" + ids + "]}"));
    }

    private long votes(UUID pollId, Property unit) {
        Long n = jdbc.queryForObject("select count(*) from poll_votes where poll_id = ? and property_id = ?", Long.class, pollId, unit.getId());
        return n == null ? 0 : n;
    }

    // ── observado = esperado ──────────────────────────────────────────────────

    @Test
    @DisplayName("propietario registrado vota; el voto queda asociado a su relacion (representante), con el coeficiente de la unidad")
    void ownerVotes() throws Exception {
        Property home = propertyRepository.findById(a.home().getId()).orElseThrow();
        home.setCoefficient(new BigDecimal("0.400000"));
        propertyRepository.save(home);
        UUID[] p = livePoll(a);
        assertCode(selfRegister(a.owner(), a, p[0], a.home()), 201, null);
        MockHttpServletResponse r = vote(a.owner(), a, p[1], a.home(), p[2]);
        assertCode(r, 200, null);
        assertThat(jdbc.queryForObject("select resident_id from poll_votes where poll_id = ? and property_id = ?", UUID.class, p[1], a.home().getId()))
                .isEqualTo(a.ownerResident().getId());
        assertThat(json(r).at("/data/totalVotedCoefficient").decimalValue()).isEqualByComparingTo("0.400000");
        assertThat(json(r).at("/data/options/0/coefficientSum").decimalValue()).isEqualByComparingTo("0.400000");
    }

    @Test
    @DisplayName("copropietarios: solo vota el representante registrado; el otro copropietario recibe UNAUTHORIZED_VOTE")
    void coOwners() throws Exception {
        User coOwner = member(a, RoleType.RESIDENT);
        relation(a, a.home(), coOwner, "Propietario");
        UUID[] p = livePoll(a);
        assertCode(selfRegister(coOwner, a, p[0], a.home()), 201, null);
        assertCode(selfRegister(a.owner(), a, p[0], a.home()), 409, "ALREADY_REGISTERED");
        assertCode(vote(a.owner(), a, p[1], a.home(), p[2]), 409, "UNAUTHORIZED_VOTE");
        assertCode(vote(coOwner, a, p[1], a.home(), p[3]), 200, null);
        assertThat(votes(p[1], a.home())).isEqualTo(1);
    }

    @Test
    @DisplayName("arrendatario delegado vota; el propietario (delegante) ya no representa a la unidad")
    void delegatedTenant() throws Exception {
        User tenant = member(a, RoleType.RESIDENT);
        relation(a, a.home(), tenant, "Arrendatario");
        assertCode(send(a.owner(), a, post("/api/v1/properties/{p}/voting-delegation", a.home().getId())), 200, null);
        UUID[] p = livePoll(a);
        assertCode(selfRegister(a.owner(), a, p[0], a.home()), 409, "VOTING_RIGHT_DELEGATED");
        assertCode(selfRegister(tenant, a, p[0], a.home()), 201, null);
        assertCode(vote(a.owner(), a, p[1], a.home(), p[2]), 409, "UNAUTHORIZED_VOTE");
        assertCode(vote(tenant, a, p[1], a.home(), p[2]), 200, null);
    }

    @Test
    @DisplayName("voto duplicado, varias opciones en SINGLE_CHOICE, pregunta cerrada y asamblea finalizada: rechazados")
    void duplicateClosedEnded() throws Exception {
        UUID[] p = livePoll(a);
        assertCode(selfRegister(a.owner(), a, p[0], a.home()), 201, null);
        assertCode(vote(a.owner(), a, p[1], a.home(), p[2], p[3]), 400, "INVALID_VOTE_OPTIONS");
        assertCode(vote(a.owner(), a, p[1], a.home(), p[2]), 200, null);
        assertCode(vote(a.owner(), a, p[1], a.home(), p[3]), 409, "PROPERTY_ALREADY_VOTED");
        assertThat(votes(p[1], a.home())).isEqualTo(1);

        UUID[] q = livePoll(a);
        assertCode(selfRegister(a.owner(), a, q[0], a.home()), 201, null);
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/polls/{p}/close", q[1])), 200, null);
        assertCode(vote(a.owner(), a, q[1], a.home(), q[2]), 409, "POLL_NOT_LIVE");

        UUID[] e = livePoll(a);
        assertCode(selfRegister(a.owner(), a, e[0], a.home()), 201, null);
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/{id}/finish", e[0])), 200, null);
        assertCode(vote(a.owner(), a, e[1], a.home(), e[2]), 409, "ASSEMBLY_ENDED");
        assertThat(votes(q[1], a.home()) + votes(e[1], a.home())).isZero();
    }

    @Test
    @DisplayName("sin asistencia no se vota; la administracion no vota por una unidad (ADMIN_VOTE_NOT_SUPPORTED)")
    void noAttendanceAndAdmin() throws Exception {
        UUID[] p = livePoll(a);
        assertCode(vote(a.owner(), a, p[1], a.home(), p[2]), 409, "PROPERTY_NOT_REGISTERED_IN_ASSEMBLY");
        assertCode(selfRegister(a.owner(), a, p[0], a.home()), 201, null);
        assertCode(vote(a.admin(), a, p[1], a.home(), p[2]), 409, "ADMIN_VOTE_NOT_SUPPORTED");
        assertThat(votes(p[1], a.home())).isZero();
    }

    @Test
    @DisplayName("usuario de otra comunidad, membresia desactivada y JWT obsoleto: no votan (permisos de PostgreSQL)")
    void foreignDisabledStale() throws Exception {
        UUID[] p = livePoll(a);
        assertCode(selfRegister(a.owner(), a, p[0], a.home()), 201, null);
        assertThat(vote(b.owner(), a, p[1], a.home(), p[2]).getStatus()).isIn(401, 403);
        MockHttpServletResponse foreign = send(b.owner(), b, post("/api/v1/assemblies/polls/{p}/vote", p[1])
                .contentType(MediaType.APPLICATION_JSON).content("{\"propertyId\":\"" + a.home().getId() + "\",\"optionIds\":[\"" + p[2] + "\"]}"));
        assertThat(foreign.getStatus()).isIn(403, 404);
        String stale = fixture.bearer(a.owner()); // emitido con la membresia ACTIVA
        Membership m = membershipRepository.findByUserIdAndTenantId(a.owner().getId(), a.tenantId()).orElseThrow();
        m.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(m);
        assertThat(vote(stale, a, p[1], a.home(), p[2]).getStatus()).isIn(401, 403);
        assertThat(votes(p[1], a.home())).isZero();
    }

    @Test
    @DisplayName("concurrencia: dos votos simultaneos del representante por la misma unidad -> uno solo (8 rondas)")
    void concurrentVotes() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            var c = fixture.create();
            Side s = c.a();
            UUID[] p = livePoll(s);
            assertCode(selfRegister(s.owner(), s, p[0], s.home()), 201, null);
            String token = fixture.bearer(s.owner());
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MockHttpServletResponse>> fs = new ArrayList<>();
            for (UUID option : List.of(p[2], p[3])) {
                Callable<MockHttpServletResponse> call = () -> {
                    go.await();
                    return vote(token, s, p[1], s.home(), option);
                };
                fs.add(pool.submit(call));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<MockHttpServletResponse> f : fs) statuses.add(f.get(60, TimeUnit.SECONDS).getStatus());
            pool.shutdown();
            assertThat(statuses).as("ronda " + round).containsExactlyInAnyOrder(200, 409);
            assertThat(votes(p[1], s.home())).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("resultados y quorum: el coeficiente votado coincide con el de las unidades que votaron; votos de otra comunidad no cuentan")
    void resultsAndQuorum() throws Exception {
        for (Property p : List.of(a.home(), a.neighbourHome())) {
            Property x = propertyRepository.findById(p.getId()).orElseThrow();
            x.setCoefficient(new BigDecimal("0.250000"));
            propertyRepository.save(x);
        }
        UUID[] p = livePoll(a);
        assertCode(selfRegister(a.owner(), a, p[0], a.home()), 201, null);
        assertCode(selfRegister(a.neighbour(), a, p[0], a.neighbourHome()), 201, null);
        assertCode(vote(a.owner(), a, p[1], a.home(), p[2]), 200, null);
        MockHttpServletResponse last = vote(a.neighbour(), a, p[1], a.neighbourHome(), p[3]);
        assertCode(last, 200, null);
        assertThat(json(last).at("/data/totalVotes").asLong()).isEqualTo(2);
        assertThat(json(last).at("/data/totalVotedCoefficient").decimalValue()).isEqualByComparingTo("0.500000");
        Assembly asm = assemblyRepository.findById(p[0]).orElseThrow();
        assertThat(asm.getRegisteredAttendees()).isEqualTo(2);
    }

    // ── BRECHAS V-1 / V-2: caracterizacion por esquema (P4) ───────────────────
    // Esquema habitual (Hibernate, sin V104; el de las bases existentes): la brecha SIGUE y se documenta como observada,
    // no como comportamiento deseado. Esquema con V104 (Flyway en bases aisladas): la brecha esta CERRADA en PostgreSQL.

    private boolean v104() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('public.poll_participations') is not null", Boolean.class));
    }

    @Test
    @DisplayName("V-1 · sin V104: BRECHA observada (PostgreSQL acepta una 2.a opción de la unidad); con V104: PostgreSQL la rechaza")
    void gapUniquenessOnlyInJava() throws Exception {
        UUID[] p = livePoll(a);
        assertCode(selfRegister(a.owner(), a, p[0], a.home()), 201, null);
        assertCode(vote(a.owner(), a, p[1], a.home(), p[2]), 200, null);
        // INSERT directo con la OTRA opcion de una SINGLE_CHOICE (contenedor aislado de pruebas; nunca una base real).
        String sql = "insert into poll_votes (id, tenant_id, poll_id, poll_option_id, property_id, resident_id, coefficient, voted_at, "
                + "created_at, updated_at) select ?, tenant_id, poll_id, ?, property_id, resident_id, coefficient, now(), now(), now() "
                + "from poll_votes where poll_id = ? and property_id = ?";
        Object[] args = {UUID.randomUUID(), p[3], p[1], a.home().getId()};
        if (!v104()) {
            jdbc.update(sql, args);
            assertThat(votes(p[1], a.home())).as("BRECHA (sin V104): dos filas de voto SINGLE_CHOICE para la misma unidad").isEqualTo(2);
        } else {
            Throwable error = org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update(sql, args));
            assertThat(error).as("V104: segundo voto vigente de la unidad rechazado por PostgreSQL")
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            // Restriccion leida del campo 'constraint' del error del servidor (no del texto): se traduce a 409.
            assertThat(com.havita.common.exception.UniqueConstraintConflicts.classify(error))
                    .map(com.havita.common.exception.UniqueConstraintConflicts.Conflict::code).contains("PROPERTY_ALREADY_VOTED");
            assertThat(votes(p[1], a.home())).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("V-2 · sin V104: BRECHA observada (el voto no registra actor ni título); con V104: la participación los registra")
    void gapNoActorRecorded() throws Exception {
        List<String> columns = jdbc.queryForList("select column_name from information_schema.columns where table_name = 'poll_votes'", String.class);
        assertThat(columns).contains("resident_id");
        assertThat(columns).as("poll_votes no se reescribe: ni actor, ni canal, ni poder en la fila del voto")
                .doesNotContain("voted_by_user_id", "proxy_id", "voided_at");
        if (!v104()) {
            assertThat(columns).as("BRECHA (sin V104): sin referencia a una participacion").doesNotContain("participation_id");
            return;
        }
        UUID[] p = livePoll(a);
        assertCode(selfRegister(a.owner(), a, p[0], a.home()), 201, null);
        assertCode(vote(a.owner(), a, p[1], a.home(), p[2]), 200, null);
        var row = jdbc.queryForMap("select pp.actor_user_id, pp.representation_type, pp.status, pp.resident_id from poll_votes v "
                + "join poll_participations pp on pp.id = v.participation_id where v.poll_id = ? and v.property_id = ?", p[1], a.home().getId());
        assertThat(row.get("actor_user_id")).isEqualTo(a.owner().getId());
        assertThat(row.get("representation_type")).isIn("OWNER", "CO_OWNER");
        assertThat(row.get("status")).isEqualTo("VALID");
        assertThat(jdbc.queryForObject("select count(*) from assembly_vote_events where poll_id = ? and action = 'VOTE_CAST' "
                + "and actor_user_id = ?", Long.class, p[1], a.owner().getId())).isEqualTo(1L);
    }
}
