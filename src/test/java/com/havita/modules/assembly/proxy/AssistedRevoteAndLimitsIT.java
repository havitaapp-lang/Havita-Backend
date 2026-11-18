package com.havita.modules.assembly.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.havita.modules.assembly.domain.Assembly;
import com.havita.modules.assembly.infrastructure.AssemblyRepository;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.MembershipStatus;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.domain.PropertyStatus;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.domain.ResidentStatus;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.support.TwoTenantFixture;
import com.havita.support.TwoTenantFixture.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Fase 2 / P5-A y P5-D — nueva emision de votos asistidos tras su anulacion (cadena P1 &lt;- P2 &lt;- P3, V105) y limite
 * de representacion por comunidad (V106).
 *
 * Contenedor PostgreSQL PROPIO (Testcontainers, aislado; nunca una base real) con Flyway V100-V106, la bandera de
 * poderes activa SOLO en este contexto y un limite global de respaldo de 3. Datos sinteticos. Carreras con peticiones
 * HTTP simultaneas (transacciones independientes), 8 rondas; invariantes de votacion y de cadena tras cada prueba.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AssistedRevoteAndLimitsIT {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("p5_revote_it");
    static final Path STORAGE;

    static {
        if (org.testcontainers.DockerClientFactory.instance().isDockerAvailable()) {
            PG.start();
        }
        try {
            STORAGE = Files.createTempDirectory("havita-p5-revote-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.flyway.locations", () -> "classpath:db/flyway");
        r.add("spring.flyway.table", () -> "havita_schema_history");
        r.add("spring.flyway.baseline-on-migrate", () -> "false");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("havita.assembly-proxies.enabled", () -> "true");
        r.add("havita.assembly-proxies.max-units-per-proxy", () -> "3");
        r.add("storage.provider", () -> "local");
        r.add("storage.local.base-path", STORAGE::toString);
    }

    private static final int ROUNDS = 8;
    static final byte[] PDF = "%PDF-1.7\n% poder sintetico P5\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private AssemblyRepository assemblyRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private UserRepository userRepository;
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

    @AfterEach
    void invariants() {
        assertInvariants();
    }

    // ── datos y llamadas ──────────────────────────────────────────────────────

    private record PollRef(UUID id, List<UUID> options) {
        UUID opt(int i) {
            return options.get(i);
        }
    }

    private record World(Side s, Assembly asm, PollRef p, UUID proxy, String doc) {}

    private Assembly started(Side s) throws Exception {
        Assembly x = Assembly.builder().title("Asamblea P5").assemblyDate(LocalDate.now()).startTime(LocalTime.NOON).build();
        x.setTenantId(s.tenantId());
        x = assemblyRepository.save(x);
        assertCode(send(s.admin(), s, post("/api/v1/assemblies/{id}/start", x.getId())), 200, null);
        return x;
    }

    private PollRef poll(Side s, Assembly asm) throws Exception {
        MockHttpServletResponse r = send(s.admin(), s, post("/api/v1/assemblies/polls").contentType(MediaType.APPLICATION_JSON)
                .content("{\"assemblyId\":\"" + asm.getId() + "\",\"question\":\"Pregunta P5\",\"type\":\"SINGLE_CHOICE\",\"options\":[\"A\",\"B\"]}"));
        assertThat(r.getStatus()).as(r.getContentAsString()).isIn(200, 201);
        UUID id = UUID.fromString(json(r).at("/data/id").asText());
        assertCode(send(s.admin(), s, post("/api/v1/assemblies/polls/{p}/open", id)), 200, null);
        List<UUID> ids = new ArrayList<>();
        for (JsonNode o : json(r).at("/data/options")) ids.add(UUID.fromString(o.path("id").asText()));
        return new PollRef(id, ids);
    }

    private User member(Side s, RoleType role) {
        User u = userRepository.save(User.builder().email("p5-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .passwordHash("{noop}x").fullName("Persona P5").phone("3000000000").build());
        membershipRepository.save(Membership.builder().user(u).tenantId(s.tenantId()).role(role).status(MembershipStatus.ACTIVE).build());
        return u;
    }

    private record Owned(Property unit, User owner) {}

    private Owned ownedUnit(Side s) {
        Property p = Property.builder().number("P5-" + UUID.randomUUID().toString().substring(0, 6)).name("Apto P5")
                .status(PropertyStatus.OCCUPIED).coefficient(new BigDecimal("0.1")).build();
        p.setTenantId(s.tenantId());
        p = propertyRepository.save(p);
        User owner = member(s, RoleType.RESIDENT);
        Resident r = Resident.builder().property(p).user(owner).name(owner.getFullName()).type("Propietario").email(owner.getEmail())
                .status(ResidentStatus.ACTIVE).build();
        r.setTenantId(s.tenantId());
        residentRepository.save(r);
        return new Owned(p, owner);
    }

    private static String doc() {
        return "P5" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

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

    private String code(MockHttpServletResponse r) throws Exception {
        return json(r).path("code").asText();
    }

    private UUID requestProxy(User grantor, Side s, Assembly asm, Property unit, UUID proxyUserId, String docNumber) throws Exception {
        String data = "{\"propertyId\":\"" + unit.getId() + "\"" + (proxyUserId != null ? ",\"proxyUserId\":\"" + proxyUserId + "\"" : "")
                + ",\"proxyFullName\":\"Apoderada P5\",\"proxyDocumentType\":\"CC\",\"proxyDocumentNumber\":\"" + docNumber + "\"}";
        MockHttpServletResponse r = send(grantor, s, multipart("/api/v1/assemblies/{a}/proxies", asm.getId())
                .file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE, data.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("document", "poder.pdf", "application/pdf", PDF)));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return UUID.fromString(json(r).at("/data/id").asText());
    }

    private MockHttpServletResponse approve(Side s, Assembly asm, UUID proxy) throws Exception {
        return send(s.admin(), s, post("/api/v1/assemblies/{a}/proxies/{p}/approve", asm.getId(), proxy));
    }

    private UUID usedExternalProxy(Side s, Assembly asm, User grantor, Property unit, String docNumber) throws Exception {
        UUID id = requestProxy(grantor, s, asm, unit, null, docNumber);
        assertCode(approve(s, asm, id), 200, null);
        assertCode(send(s.admin(), s, post("/api/v1/assemblies/{a}/proxies/{p}/attendance", asm.getId(), id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"documentType\":\"CC\",\"documentNumber\":\"" + docNumber + "\"}")), 201, null);
        return id;
    }

    private MockHttpServletResponse assisted(User admin, Side s, PollRef p, UUID proxy, String docNumber, UUID option) throws Exception {
        return assisted(fixture.bearer(admin), s, p, proxy, docNumber, option);
    }

    private MockHttpServletResponse assisted(String bearer, Side s, PollRef p, UUID proxy, String docNumber, UUID option) throws Exception {
        return send(bearer, s, post("/api/v1/assemblies/polls/{id}/assisted-vote", p.id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"proxyId\":\"" + proxy + "\",\"documentType\":\"CC\",\"documentNumber\":\"" + docNumber
                        + "\",\"expressedByProxyConfirmed\":true,\"optionIds\":[\"" + option + "\"]}"));
    }

    private MockHttpServletResponse voidVote(User admin, Side s, PollRef p, UUID participation, String reason) throws Exception {
        return send(admin, s, post("/api/v1/assemblies/polls/{id}/participations/{x}/void", p.id(), participation)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + reason + "\"}"));
    }

    private UUID participationId(MockHttpServletResponse r) throws Exception {
        return UUID.fromString(json(r).at("/data/participationId").asText());
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    /** Comunidad nueva con asamblea en curso, pregunta abierta y un poder externo USED. */
    private World world() throws Exception {
        Side s = fixture.create().a();
        Assembly asm = started(s);
        PollRef p = poll(s, asm);
        String d = doc();
        return new World(s, asm, p, usedExternalProxy(s, asm, s.owner(), s.home(), d), d);
    }

    /** Invariantes de votacion y de cadena en TODA la base. */
    private void assertInvariants() {
        assertThat(count("select count(*) from (select poll_id, property_id from poll_participations where status = 'VALID' "
                + "group by 1, 2 having count(*) > 1) x")).as("participaciones vigentes duplicadas").isZero();
        assertThat(count("select count(*) from poll_participations pp where not exists (select 1 from poll_votes v "
                + "where v.participation_id = pp.id)")).as("participacion sin votos").isZero();
        assertThat(count("select count(*) from poll_participations c join poll_participations p on p.id = c.supersedes_participation_id "
                + "where p.status <> 'VOIDED' or p.representation_type <> 'ADMIN_ASSISTED_PROXY' or c.representation_type <> "
                + "'ADMIN_ASSISTED_PROXY' or p.proxy_id <> c.proxy_id or p.poll_id <> c.poll_id or p.property_id <> c.property_id "
                + "or c.cast_at < p.voided_at")).as("cadena incoherente").isZero();
        assertThat(count("select count(*) from (select poll_id, property_id from poll_participations where supersedes_participation_id "
                + "is null group by 1, 2 having count(*) <> 1) x")).as("una sola raiz por unidad y pregunta").isZero();
        assertThat(count("select count(*) from poll_participations pp where pp.status = 'VOIDED' and (select count(*) from "
                + "assembly_vote_events e where e.participation_id = pp.id and e.action = 'ASSISTED_VOTE_VOIDED') <> 1"))
                .as("un evento por anulacion").isZero();
        assertThat(count("select count(*) from poll_participations pp where (select count(*) from assembly_vote_events e where "
                + "e.participation_id = pp.id and e.action in ('VOTE_CAST', 'ASSISTED_VOTE_CAST', 'ASSISTED_VOTE_REISSUED')) <> 1"))
                .as("un evento de emision por participacion").isZero();
        assertThat(count("select count(*) from poll_participations pp where (pp.supersedes_participation_id is null) <> exists "
                + "(select 1 from assembly_vote_events e where e.participation_id = pp.id and e.action <> 'ASSISTED_VOTE_REISSUED' "
                + "and e.action in ('VOTE_CAST', 'ASSISTED_VOTE_CAST'))")).as("evento de nueva emision coherente").isZero();
        assertThat(count("select count(*) from poll_participations pp join polls p on p.id = pp.poll_id where p.closed_at is not null "
                + "and (pp.cast_at > p.closed_at or pp.voided_at > p.closed_at)")).as("voto o anulacion despues del cierre").isZero();
        assertThat(count("select count(*) from (select v.poll_id, v.property_id from poll_votes v left join poll_participations pp "
                + "on pp.id = v.participation_id where v.participation_id is null or pp.status = 'VALID' group by 1, 2 "
                + "having count(distinct coalesce(v.participation_id, '00000000-0000-0000-0000-000000000000'::uuid)) > 1) x"))
                .as("votos vigentes de dos participaciones").isZero();
    }

    // ── P5-A: nueva emision ───────────────────────────────────────────────────

    @Test
    @DisplayName("P5-A · tres ciclos anular → nueva emision: cadena P1 <- P2 <- P3, opciones originales conservadas, solo P3 cuenta")
    void threeCycles() throws Exception {
        World w = world();
        User admin2 = member(w.s(), RoleType.COMMUNITY_ADMIN);
        UUID p1 = participationId(assisted(w.s().admin(), w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(0)));
        assertCode(voidVote(w.s().admin(), w.s(), w.p(), p1, "Error de digitacion"), 200, null);
        MockHttpServletResponse r2 = assisted(admin2, w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(1));
        assertCode(r2, 201, null);
        UUID p2 = participationId(r2);
        assertThat(json(r2).at("/data/supersedesParticipationId").asText()).isEqualTo(p1.toString());
        assertThat(json(r2).at("/data/poll/options/1/votesCount").asLong()).isEqualTo(1);
        assertThat(json(r2).at("/data/poll/options/0/votesCount").asLong()).isZero();
        assertCode(voidVote(admin2, w.s(), w.p(), p2, "El apoderado corrigio su voto"), 200, null);
        MockHttpServletResponse r3 = assisted(w.s().admin(), w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(0));
        assertCode(r3, 201, null);
        UUID p3 = participationId(r3);
        assertThat(json(r3).at("/data/supersedesParticipationId").asText()).isEqualTo(p2.toString());
        assertThat(json(r3).at("/data/poll/totalVotes").asLong()).isEqualTo(1);
        assertThat(json(r3).at("/data/poll/options/0/votesCount").asLong()).isEqualTo(1);

        // Historial autorizado: las tres, en orden, con su opcion original, quien emitio, quien anulo y por que.
        MockHttpServletResponse h = send(w.s().admin(), w.s(), get("/api/v1/assemblies/polls/{p}/participations", w.p().id()));
        assertCode(h, 200, null);
        JsonNode list = json(h).at("/data");
        assertThat(list).hasSize(3);
        assertThat(list.get(0).path("participationId").asText()).isEqualTo(p1.toString());
        assertThat(list.get(0).path("status").asText()).isEqualTo("VOIDED");
        assertThat(list.get(0).path("optionIds").get(0).asText()).isEqualTo(w.p().opt(0).toString());
        assertThat(list.get(0).path("actorUserId").asText()).isEqualTo(w.s().admin().getId().toString());
        assertThat(list.get(0).path("voidReason").asText()).isEqualTo("Error de digitacion");
        assertThat(list.get(1).path("participationId").asText()).isEqualTo(p2.toString());
        assertThat(list.get(1).path("supersedesParticipationId").asText()).isEqualTo(p1.toString());
        assertThat(list.get(1).path("actorUserId").asText()).isEqualTo(admin2.getId().toString());
        assertThat(list.get(1).path("voidedByUserId").asText()).isEqualTo(admin2.getId().toString());
        assertThat(list.get(1).path("optionIds").get(0).asText()).isEqualTo(w.p().opt(1).toString());
        assertThat(list.get(2).path("status").asText()).isEqualTo("VALID");
        assertThat(list.get(2).path("supersedesParticipationId").asText()).isEqualTo(p2.toString());
        assertThat(h.getContentAsString()).doesNotContain(w.doc()).doesNotContain("Apoderada");
        assertThat(count("select count(*) from assembly_vote_events where poll_id = ? and action = 'ASSISTED_VOTE_REISSUED'", w.p().id())).isEqualTo(2);
        assertThat(count("select count(*) from assembly_vote_events where poll_id = ? and action = 'ASSISTED_VOTE_CAST'", w.p().id())).isEqualTo(1);
        assertThat(count("select count(*) from poll_votes where poll_id = ? and property_id = ?", w.p().id(), w.s().home().getId())).isEqualTo(3);
        assertThat(p3).isNotNull();
    }

    @Test
    @DisplayName("P5-A · nueva emision rechazada: con un voto vigente, identidad distinta, residente, administrador inactivo, "
            + "pregunta cerrada, asamblea finalizada; el historial solo para la administracion de la comunidad")
    void reissueRejections() throws Exception {
        World w = world();
        UUID p1 = participationId(assisted(w.s().admin(), w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(0)));
        // Vigente: no hay nueva emision sin anular.
        assertCode(assisted(w.s().admin(), w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(1)), 409, "PROPERTY_ALREADY_VOTED");
        assertCode(voidVote(w.s().admin(), w.s(), w.p(), p1, "motivo"), 200, null);
        // Todas las verificaciones se repiten en la nueva emision.
        assertCode(assisted(w.s().admin(), w.s(), w.p(), w.proxy(), w.doc() + "X", w.p().opt(1)), 409, "PROXY_IDENTITY_MISMATCH");
        assertThat(assisted(w.s().owner(), w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(1)).getStatus()).isEqualTo(403);
        User admin2 = member(w.s(), RoleType.COMMUNITY_ADMIN);
        String token = fixture.bearer(admin2);
        Membership m = membershipRepository.findByUserIdAndTenantId(admin2.getId(), w.s().tenantId()).orElseThrow();
        m.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(m);
        assertThat(assisted(token, w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(1)).getStatus()).isIn(401, 403);
        // Por la via directa no hay nueva emision (solo asistida).
        assertThat(send(w.s().owner(), w.s(), post("/api/v1/assemblies/polls/{id}/vote", w.p().id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"propertyId\":\"" + w.s().home().getId() + "\",\"optionIds\":[\"" + w.p().opt(1) + "\"]}")).getStatus()).isIn(403, 409);
        // Historial: residente 403, otra comunidad 404.
        assertThat(send(w.s().owner(), w.s(), get("/api/v1/assemblies/polls/{p}/participations", w.p().id())).getStatus()).isEqualTo(403);
        assertThat(send(b.admin(), b, get("/api/v1/assemblies/polls/{p}/participations", w.p().id())).getStatus()).isEqualTo(404);
        // Pregunta cerrada: ni nueva emision ni anulacion.
        assertCode(send(w.s().admin(), w.s(), post("/api/v1/assemblies/polls/{p}/close", w.p().id())), 200, null);
        assertCode(assisted(w.s().admin(), w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(1)), 409, "POLL_NOT_LIVE");
        assertThat(count("select count(*) from poll_participations where poll_id = ?", w.p().id())).isEqualTo(1);
        // Asamblea finalizada.
        World w2 = world();
        UUID q1 = participationId(assisted(w2.s().admin(), w2.s(), w2.p(), w2.proxy(), w2.doc(), w2.p().opt(0)));
        assertCode(voidVote(w2.s().admin(), w2.s(), w2.p(), q1, "motivo"), 200, null);
        assertCode(send(w2.s().admin(), w2.s(), post("/api/v1/assemblies/{id}/finish", w2.asm().getId())), 200, null);
        assertCode(assisted(w2.s().admin(), w2.s(), w2.p(), w2.proxy(), w2.doc(), w2.p().opt(1)), 409, "ASSEMBLY_ENDED");
        assertThat(count("select count(*) from poll_participations where poll_id = ?", w2.p().id())).isEqualTo(1);
    }

    @Test
    @DisplayName("P5-A · PostgreSQL: la cadena solo continua una anulada del mismo poder, es inmutable y no cruza preguntas")
    void chainGuards() throws Exception {
        World w = world();
        UUID p1 = participationId(assisted(w.s().admin(), w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(0)));
        String insert = "insert into poll_participations (id, tenant_id, assembly_id, poll_id, property_id, attendee_id, "
                + "representation_type, actor_user_id, proxy_id, grantor_resident_id, identity_verified_by_user_id, identity_verified_at, "
                + "identity_verification_method, expressed_by_proxy_confirmed, status, cast_at, supersedes_participation_id) "
                + "select gen_random_uuid(), tenant_id, assembly_id, poll_id, property_id, attendee_id, representation_type, actor_user_id, "
                + "proxy_id, grantor_resident_id, identity_verified_by_user_id, now(), 'IN_PERSON_DOCUMENT', true, 'VALID', now(), %s "
                + "from poll_participations where id = ?";
        // Sustituir una participacion VIGENTE: rechazado.
        assertThat(catchThrowable(() -> jdbc.update(insert.formatted("id"), p1))).hasMessageContaining("solo un voto asistido anulado");
        // Una segunda raiz (sin cadena) para la misma unidad y pregunta: rechazada.
        assertThat(catchThrowable(() -> jdbc.update(insert.formatted("null"), p1))).hasMessageContaining("debe continuar la cadena");
        assertCode(voidVote(w.s().admin(), w.s(), w.p(), p1, "motivo"), 200, null);
        UUID p2 = participationId(assisted(w.s().admin(), w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(1)));
        // La referencia no se modifica.
        assertThat(catchThrowable(() -> jdbc.update("update poll_participations set supersedes_participation_id = null where id = ?", p2)))
                .hasMessageContaining("no se modifica");
        // Una anulada ya sustituida no se sustituye otra vez (indice unico).
        assertThat(catchThrowable(() -> jdbc.update(insert.formatted("id"), p1))).isNotNull();
        // Referencia a una participacion de otra pregunta: la FK compuesta lo impide.
        World other = world();
        UUID o1 = participationId(assisted(other.s().admin(), other.s(), other.p(), other.proxy(), other.doc(), other.p().opt(0)));
        assertThat(catchThrowable(() -> jdbc.update(insert.formatted("'" + o1 + "'::uuid"), p2))).isNotNull();
        assertThat(count("select count(*) from poll_participations where poll_id = ?", w.p().id())).isEqualTo(2);
    }

    // ── P5-A: concurrencia ────────────────────────────────────────────────────

    private List<MockHttpServletResponse> concurrently(List<Callable<MockHttpServletResponse>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> fs = new ArrayList<>();
        for (Callable<MockHttpServletResponse> c : calls) {
            fs.add(pool.submit(() -> {
                go.await();
                return c.call();
            }));
        }
        go.countDown();
        List<MockHttpServletResponse> out = new ArrayList<>();
        for (Future<MockHttpServletResponse> f : fs) out.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        for (MockHttpServletResponse r : out) assertThat(r.getStatus()).as("nunca 5xx: " + r.getContentAsString()).isLessThan(500);
        return out;
    }

    private static int ok(MockHttpServletResponse r) {
        return r.getStatus() < 300 ? 1 : 0;
    }

    @Test
    @DisplayName("P5-A · carrera: dos administradores (o el mismo dos veces) emiten de nuevo tras la misma anulacion: una sola (8 rondas)")
    void raceTwoReissues() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = world();
            UUID p1 = participationId(assisted(w.s().admin(), w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(0)));
            assertCode(voidVote(w.s().admin(), w.s(), w.p(), p1, "motivo"), 200, null);
            User second = round % 2 == 0 ? member(w.s(), RoleType.COMMUNITY_ADMIN) : w.s().admin();
            var r = concurrently(List.of(() -> assisted(w.s().admin(), w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(0)),
                    () -> assisted(second, w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(1))));
            assertThat(ok(r.get(0)) + ok(r.get(1))).as("ronda " + round).isEqualTo(1);
            assertThat(code(r.get(ok(r.get(0)) == 1 ? 1 : 0))).isEqualTo("PROPERTY_ALREADY_VOTED");
            assertThat(count("select count(*) from poll_participations where poll_id = ?", w.p().id())).isEqualTo(2);
            assertThat(count("select count(*) from poll_participations where poll_id = ? and status = 'VALID'", w.p().id())).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("P5-A · carreras: nueva emision frente a cierre de la pregunta y frente a finalizar la asamblea (8 rondas)")
    void raceReissueVersusCloseAndFinish() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = world();
            UUID p1 = participationId(assisted(w.s().admin(), w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(0)));
            assertCode(voidVote(w.s().admin(), w.s(), w.p(), p1, "motivo"), 200, null);
            boolean close = round % 2 == 0;
            var r = concurrently(List.of(() -> assisted(w.s().admin(), w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(1)),
                    () -> close ? send(w.s().admin(), w.s(), post("/api/v1/assemblies/polls/{p}/close", w.p().id()))
                            : send(w.s().admin(), w.s(), post("/api/v1/assemblies/{id}/finish", w.asm().getId()))));
            assertThat(r.get(1).getStatus()).as("ronda " + round).isEqualTo(200);
            long parts = count("select count(*) from poll_participations where poll_id = ?", w.p().id());
            if (ok(r.get(0)) == 1) {
                assertThat(parts).isEqualTo(2);
            } else {
                assertThat(code(r.get(0))).isIn("POLL_NOT_LIVE", "ASSEMBLY_ENDED", "ASSEMBLY_NOT_IN_PROGRESS");
                assertThat(parts).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("P5-A · carreras: anular la nueva emision dos veces a la vez, y anular frente a cierre (8 rondas)")
    void raceVoidOfReissue() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = world();
            UUID p1 = participationId(assisted(w.s().admin(), w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(0)));
            assertCode(voidVote(w.s().admin(), w.s(), w.p(), p1, "motivo"), 200, null);
            UUID p2 = participationId(assisted(w.s().admin(), w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(1)));
            boolean twoVoids = round % 2 == 0;
            var r = concurrently(List.of(() -> voidVote(w.s().admin(), w.s(), w.p(), p2, "primera"),
                    () -> twoVoids ? voidVote(w.s().admin(), w.s(), w.p(), p2, "segunda")
                            : send(w.s().admin(), w.s(), post("/api/v1/assemblies/polls/{p}/close", w.p().id()))));
            String status = jdbc.queryForObject("select status from poll_participations where id = ?", String.class, p2);
            if (twoVoids) {
                assertThat(ok(r.get(0)) + ok(r.get(1))).as("ronda " + round).isEqualTo(1);
                assertThat(status).isEqualTo("VOIDED");
            } else {
                assertThat(r.get(1).getStatus()).isEqualTo(200);
                assertThat(status).isEqualTo(ok(r.get(0)) == 1 ? "VOIDED" : "VALID");
            }
        }
    }

    @Test
    @DisplayName("P5-A · carreras: nueva emision frente a la baja de la membresia administrativa y frente a degradar su rol (8 rondas)")
    void raceReissueVersusAuthorizationChanges() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            World w = world();
            UUID p1 = participationId(assisted(w.s().admin(), w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(0)));
            assertCode(voidVote(w.s().admin(), w.s(), w.p(), p1, "motivo"), 200, null);
            User admin2 = member(w.s(), RoleType.COMMUNITY_ADMIN);
            String token = fixture.bearer(admin2);
            String change = round % 2 == 0 ? "update memberships set status = 'INACTIVE' where user_id = ? and tenant_id = ?"
                    : "update memberships set role = 'STAFF' where user_id = ? and tenant_id = ?";
            var r = concurrently(List.of(() -> assisted(token, w.s(), w.p(), w.proxy(), w.doc(), w.p().opt(1)),
                    () -> {
                        jdbc.update(change, admin2.getId(), w.s().tenantId());
                        return new MockHttpServletResponse();
                    }));
            assertThat(r.get(0).getStatus()).as("ronda " + round).isIn(201, 401, 403);
            assertThat(count("select count(*) from poll_participations where poll_id = ?", w.p().id())).isEqualTo(1 + ok(r.get(0)));
            if (ok(r.get(0)) == 1) {
                assertThat(jdbc.queryForObject("select actor_user_id from poll_participations where poll_id = ? and status = 'VALID'",
                        UUID.class, w.p().id())).isEqualTo(admin2.getId());
            }
        }
    }

    // ── P5-D: limite por comunidad ────────────────────────────────────────────

    private MockHttpServletResponse setLimit(Side s, String body) throws Exception {
        return send(s.admin(), s, put("/api/v1/assemblies/proxy-settings").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("P5-D · limite por comunidad: valor global de respaldo, configuracion propia, sin limite, validacion, historial y aislamiento")
    void communityLimit() throws Exception {
        JsonNode initial = json(send(a.admin(), a, get("/api/v1/assemblies/proxy-settings"))).at("/data");
        assertThat(initial.path("configured").asBoolean()).isFalse();
        assertThat(initial.path("effectiveLimit").asInt()).isEqualTo(3);
        assertThat(send(a.owner(), a, get("/api/v1/assemblies/proxy-settings")).getStatus()).isEqualTo(403);
        assertThat(setLimit(a, "{\"maxUnitsPerProxy\":0}").getStatus()).isEqualTo(400);
        assertCode(setLimit(a, "{\"maxUnitsPerProxy\":1}"), 200, null);
        JsonNode after = json(send(a.admin(), a, get("/api/v1/assemblies/proxy-settings"))).at("/data");
        assertThat(after.path("configured").asBoolean()).isTrue();
        assertThat(after.path("effectiveLimit").asInt()).isEqualTo(1);
        // Aislamiento: la otra comunidad sigue con el valor global.
        assertThat(json(send(b.admin(), b, get("/api/v1/assemblies/proxy-settings"))).at("/data/effectiveLimit").asInt()).isEqualTo(3);

        // Con limite 1, el mismo apoderado externo no se aprueba para una segunda unidad.
        Assembly asm = started(a);
        Owned u1 = ownedUnit(a);
        Owned u2 = ownedUnit(a);
        String d = doc();
        UUID x1 = requestProxy(u1.owner(), a, asm, u1.unit(), null, d);
        UUID x2 = requestProxy(u2.owner(), a, asm, u2.unit(), null, d);
        assertCode(approve(a, asm, x1), 200, null);
        assertCode(approve(a, asm, x2), 409, "PROXY_REPRESENTATION_LIMIT_REACHED");
        // La otra comunidad, con el global (3), si aprueba dos del mismo apoderado.
        Assembly asmB = started(b);
        Owned b1 = ownedUnit(b);
        Owned b2 = ownedUnit(b);
        String db = doc();
        assertCode(approve(b, asmB, requestProxy(b1.owner(), b, asmB, b1.unit(), null, db)), 200, null);
        assertCode(approve(b, asmB, requestProxy(b2.owner(), b, asmB, b2.unit(), null, db)), 200, null);
        // Sin limite en la comunidad (null): se aprueba la segunda.
        assertCode(setLimit(a, "{\"maxUnitsPerProxy\":null}"), 200, null);
        assertCode(approve(a, asm, x2), 200, null);
        // No retroactivo: bajar el limite no toca poderes ya aprobados.
        assertCode(setLimit(a, "{\"maxUnitsPerProxy\":1}"), 200, null);
        assertThat(count("select count(*) from assembly_proxies where id in (?, ?) and status = 'APPROVED'", x1, x2)).isEqualTo(2);
        // Historial de cambios con valor anterior y nuevo, y de solo insercion.
        assertThat(count("select count(*) from community_proxy_settings_events where tenant_id = ?", a.tenantId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("select previous_max_units from community_proxy_settings_events where tenant_id = ? "
                + "order by occurred_at desc limit 1", Integer.class, a.tenantId())).isNull();
        assertThat(catchThrowable(() -> jdbc.update("delete from community_proxy_settings_events where tenant_id = ?", a.tenantId())))
                .hasMessageContaining("solo insercion");
        assertThat(count("select count(*) from community_proxy_settings_events where tenant_id = ?", b.tenantId())).isZero();
    }

    @Test
    @DisplayName("P5-D · carreras: dos aprobaciones frente al limite 1 (una sola) y cambio del limite frente a una aprobacion (8 rondas)")
    void raceLimits() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Side s = fixture.create().a();
            Assembly asm = started(s);
            Owned u1 = ownedUnit(s);
            Owned u2 = ownedUnit(s);
            String d = doc();
            UUID x1 = requestProxy(u1.owner(), s, asm, u1.unit(), null, d);
            UUID x2 = requestProxy(u2.owner(), s, asm, u2.unit(), null, d);
            if (round % 2 == 0) {
                assertCode(setLimit(s, "{\"maxUnitsPerProxy\":1}"), 200, null);
                var r = concurrently(List.of(() -> approve(s, asm, x1), () -> approve(s, asm, x2)));
                assertThat(ok(r.get(0)) + ok(r.get(1))).as("ronda " + round).isEqualTo(1);
                assertThat(code(r.get(ok(r.get(0)) == 1 ? 1 : 0))).isEqualTo("PROXY_REPRESENTATION_LIMIT_REACHED");
            } else {
                assertCode(setLimit(s, "{\"maxUnitsPerProxy\":2}"), 200, null);
                assertCode(approve(s, asm, x1), 200, null);
                var r = concurrently(List.of(() -> setLimit(s, "{\"maxUnitsPerProxy\":1}"), () -> approve(s, asm, x2)));
                assertThat(r.get(0).getStatus()).as("ronda " + round).isEqualTo(200);
                // O la aprobacion se ordena antes del cambio (y queda aprobada: no retroactivo) o despues (limite 1).
                if (ok(r.get(1)) == 0) assertThat(code(r.get(1))).isEqualTo("PROXY_REPRESENTATION_LIMIT_REACHED");
                assertThat(jdbc.queryForObject("select max_units_per_proxy from community_proxy_settings where tenant_id = ?",
                        Integer.class, s.tenantId())).isEqualTo(1);
            }
            long approved = count("select count(*) from assembly_proxies where id in (?, ?) and status = 'APPROVED'", x1, x2);
            assertThat(approved).isBetween(1L, 2L);
        }
    }

    // ── P6: lecturas para el frontend (poderes propios y eventos) ─────────────

    @Test
    @DisplayName("P6 · /proxies/mine: el otorgante (GRANTOR) y el apoderado con cuenta (PROXY) ven su poder; nadie mas; otra comunidad 404")
    void myProxies() throws Exception {
        Assembly asm = started(a);
        UUID proxy = requestProxy(a.owner(), a, asm, a.home(), a.neighbour().getId(), doc());
        JsonNode grantor = json(send(a.owner(), a, get("/api/v1/assemblies/{a}/proxies/mine", asm.getId()))).at("/data");
        assertThat(grantor).hasSize(1);
        assertThat(grantor.get(0).path("relation").asText()).isEqualTo("GRANTOR");
        assertThat(grantor.get(0).at("/proxy/id").asText()).isEqualTo(proxy.toString());
        assertThat(grantor.get(0).toString()).doesNotContain("documentKey").doesNotContain("tenants/");
        JsonNode asProxy = json(send(a.neighbour(), a, get("/api/v1/assemblies/{a}/proxies/mine", asm.getId()))).at("/data");
        assertThat(asProxy).hasSize(1);
        assertThat(asProxy.get(0).path("relation").asText()).isEqualTo("PROXY");
        // P6: el apoderado (que no es residente de esa unidad) sabe a que unidad representa.
        assertThat(asProxy.get(0).at("/proxy/propertyNumber").asText()).isEqualTo(a.home().getNumber());
        // Un residente sin relacion con el poder y la administracion por esta ruta: nada.
        Owned other = ownedUnit(a);
        assertThat(json(send(other.owner(), a, get("/api/v1/assemblies/{a}/proxies/mine", asm.getId()))).at("/data")).isEmpty();
        assertThat(json(send(a.admin(), a, get("/api/v1/assemblies/{a}/proxies/mine", asm.getId()))).at("/data")).isEmpty();
        // Asamblea de otra comunidad: 404 sin datos.
        assertThat(send(b.owner(), b, get("/api/v1/assemblies/{a}/proxies/mine", asm.getId())).getStatus()).isEqualTo(404);
        // El otorgante que deja de ser propietario activo deja de verlo (como el documento).
        Resident rel = residentRepository.findById(a.ownerResident().getId()).orElseThrow();
        rel.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(rel);
        Resident keep = Resident.builder().property(ownedUnit(a).unit()).user(a.owner()).name("x").type("Residente")
                .email(a.owner().getEmail()).status(ResidentStatus.ACTIVE).build();
        keep.setTenantId(a.tenantId());
        residentRepository.save(keep);
        assertThat(json(send(a.owner(), a, get("/api/v1/assemblies/{a}/proxies/mine", asm.getId()))).at("/data")).isEmpty();
    }

    @Test
    @DisplayName("P6 · /proxies/{id}/events: historial en orden para la administracion; residente 403; otra comunidad 404")
    void proxyEvents() throws Exception {
        Assembly asm = started(a);
        UUID proxy = requestProxy(a.owner(), a, asm, a.home(), null, doc());
        assertCode(approve(a, asm, proxy), 200, null);
        MockHttpServletResponse r = send(a.admin(), a, get("/api/v1/assemblies/{a}/proxies/{p}/events", asm.getId(), proxy));
        assertCode(r, 200, null);
        JsonNode ev = json(r).at("/data");
        assertThat(ev).hasSize(3);
        assertThat(ev.get(0).path("action").asText()).isEqualTo("PROXY_REQUESTED");
        assertThat(ev.get(1).path("action").asText()).isEqualTo("PROXY_DOCUMENT_REGISTERED");
        assertThat(ev.get(2).path("action").asText()).isEqualTo("PROXY_APPROVED");
        assertThat(ev.get(2).path("toStatus").asText()).isEqualTo("APPROVED");
        assertThat(r.getContentAsString()).doesNotContain("Apoderada").doesNotContain("tenants/");
        assertThat(send(a.owner(), a, get("/api/v1/assemblies/{a}/proxies/{p}/events", asm.getId(), proxy)).getStatus()).isEqualTo(403);
        assertThat(send(b.admin(), b, get("/api/v1/assemblies/{a}/proxies/{p}/events", asm.getId(), proxy)).getStatus()).isEqualTo(404);
    }

    // ── P7-B (G3): designacion por la administracion con evidencia ────────────

    private MockHttpServletResponse adminDesignate(User admin, Side s, Assembly asm, Property unit, UUID grantorResidentId,
                                                   String docNumber, String evidence) throws Exception {
        return adminDesignate(fixture.bearer(admin), s, asm, unit, grantorResidentId, docNumber, evidence);
    }

    private MockHttpServletResponse adminDesignate(String bearer, Side s, Assembly asm, Property unit, UUID grantorResidentId,
                                                   String docNumber, String evidence) throws Exception {
        String data = "{\"propertyId\":\"" + unit.getId() + "\",\"grantorResidentId\":\"" + grantorResidentId + "\""
                + ",\"proxyFullName\":\"Apoderada P7\",\"proxyDocumentType\":\"CC\",\"proxyDocumentNumber\":\"" + docNumber + "\""
                + (evidence != null ? "," + evidence : "") + "}";
        return send(bearer, s, multipart("/api/v1/assemblies/{a}/proxies/admin", asm.getId())
                .file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE, data.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("document", "poder.pdf", "application/pdf", PDF)));
    }

    private static final String EVIDENCE_OK = "\"evidenceType\":\"SIGNED_DOCUMENT_IN_PERSON\",\"grantorConsentAttested\":true";

    private UUID ownerRelationOf(Property unit) {
        return jdbc.queryForObject("select id from residents where property_id = ? and type = 'Propietario' and status = 'ACTIVE' limit 1",
                UUID.class, unit.getId());
    }

    @Test
    @DisplayName("P7-B · designacion administrativa: evidencia y declaracion obligatorias; queda EN REVISION y auditada sin datos de identidad")
    void adminDesignationWithEvidence() throws Exception {
        Assembly asm = started(a);
        long proxiesBefore = count("select count(*) from assembly_proxies where tenant_id = ?", a.tenantId());
        long residentsBefore = count("select count(*) from residents where tenant_id = ?", a.tenantId());
        String d = doc();
        UUID grantor = a.ownerResident().getId();
        assertCode(adminDesignate(a.admin(), a, asm, a.home(), grantor, d, null), 400, "EVIDENCE_TYPE_REQUIRED");
        assertCode(adminDesignate(a.admin(), a, asm, a.home(), grantor, d, "\"evidenceType\":\"SIGNED_DOCUMENT_IN_PERSON\""),
                400, "GRANTOR_CONSENT_ATTESTATION_REQUIRED");
        assertCode(adminDesignate(a.admin(), a, asm, a.home(), grantor, d,
                "\"evidenceType\":\"SIGNED_DOCUMENT_IN_PERSON\",\"grantorConsentAttested\":false"), 400, "GRANTOR_CONSENT_ATTESTATION_REQUIRED");
        assertThat(adminDesignate(a.admin(), a, asm, a.home(), grantor, d,
                "\"evidenceType\":\"INVENTADA\",\"grantorConsentAttested\":true").getStatus()).isEqualTo(400);
        assertThat(count("select count(*) from assembly_proxies where tenant_id = ?", a.tenantId())).isEqualTo(proxiesBefore);

        // El propietario no indica evidencia administrativa en su propia solicitud.
        String ownerData = "{\"propertyId\":\"" + a.home().getId() + "\",\"proxyFullName\":\"Apoderada P7\",\"proxyDocumentType\":\"CC\","
                + "\"proxyDocumentNumber\":\"" + d + "\"," + EVIDENCE_OK + "}";
        assertCode(send(a.owner(), a, multipart("/api/v1/assemblies/{a}/proxies", asm.getId())
                .file(new MockMultipartFile("data", "", MediaType.APPLICATION_JSON_VALUE, ownerData.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("document", "poder.pdf", "application/pdf", PDF))), 400, "EVIDENCE_NOT_APPLICABLE");

        // Un residente o la administracion de otra comunidad: no.
        assertThat(adminDesignate(a.owner(), a, asm, a.home(), grantor, d, EVIDENCE_OK).getStatus()).isEqualTo(403);
        assertThat(adminDesignate(b.admin(), b, asm, a.home(), grantor, d, EVIDENCE_OK).getStatus()).isEqualTo(404);
        // El otorgante debe ser propietario ACTIVO de esa unidad (no la relacion de otra unidad).
        assertCode(adminDesignate(a.admin(), a, asm, a.home(), a.neighbourResident().getId(), d, EVIDENCE_OK), 409, "GRANTOR_NOT_ACTIVE_OWNER");

        MockHttpServletResponse ok = adminDesignate(a.admin(), a, asm, a.home(), grantor, d,
                "\"evidenceType\":\"SIGNED_DOCUMENT_SENT_BY_GRANTOR\",\"grantorConsentAttested\":true");
        assertCode(ok, 201, null);
        JsonNode created = json(ok).at("/data");
        UUID id = UUID.fromString(created.path("id").asText());
        assertThat(created.path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(created.path("createdChannel").asText()).isEqualTo("ADMIN");
        // Auditoria: actor = administrador, unidad, asamblea, estado y tipo de evidencia; nunca el documento de identidad.
        assertThat(count("select count(*) from assembly_proxy_events where proxy_id = ? and action = 'PROXY_DOCUMENT_REGISTERED' "
                        + "and reason_code = 'EVIDENCE_SIGNED_DOCUMENT_SENT_BY_GRANTOR' and actor_user_id = ? and actor_channel = 'ADMIN' "
                        + "and property_id = ? and assembly_id = ?", id, a.admin().getId(), a.home().getId(), asm.getId())).isEqualTo(1);
        assertThat(count("select count(*) from assembly_proxy_events where proxy_id = ? and action = 'PROXY_REQUESTED' "
                + "and to_status = 'PENDING_REVIEW' and actor_user_id = ?", id, a.admin().getId())).isEqualTo(1);
        MockHttpServletResponse ev = send(a.admin(), a, get("/api/v1/assemblies/{a}/proxies/{p}/events", asm.getId(), id));
        assertThat(ev.getContentAsString()).contains("EVIDENCE_SIGNED_DOCUMENT_SENT_BY_GRANTOR").doesNotContain(d);
        assertThat(count("select count(*) from assembly_proxy_events where proxy_id = ? and coalesce(reason_code, '') like ?", id, "%" + d + "%"))
                .isZero();
        // No crea derechos: ninguna relacion nueva; el otorgante la ve como suya.
        assertThat(count("select count(*) from residents where tenant_id = ?", a.tenantId())).isEqualTo(residentsBefore);
        JsonNode mine = json(send(a.owner(), a, get("/api/v1/assemblies/{a}/proxies/mine", asm.getId()))).at("/data");
        assertThat(mine.get(0).path("relation").asText()).isEqualTo("GRANTOR");
        // Un segundo poder vigente para la unidad: rechazado como en la solicitud del propietario.
        assertCode(adminDesignate(a.admin(), a, asm, a.home(), grantor, doc(), EVIDENCE_OK), 409, "PROXY_ALREADY_EXISTS");
    }

    @Test
    @DisplayName("P7-B · designacion administrativa: no elude el limite, ni una asamblea finalizada, ni un administrador desactivado")
    void adminDesignationGuards() throws Exception {
        Assembly asm = started(a);
        assertCode(setLimit(a, "{\"maxUnitsPerProxy\":1}"), 200, null);
        Owned u1 = ownedUnit(a);
        Owned u2 = ownedUnit(a);
        String d = doc();
        MockHttpServletResponse r1 = adminDesignate(a.admin(), a, asm, u1.unit(), ownerRelationOf(u1.unit()), d, EVIDENCE_OK);
        MockHttpServletResponse r2 = adminDesignate(a.admin(), a, asm, u2.unit(), ownerRelationOf(u2.unit()), d, EVIDENCE_OK);
        assertCode(r1, 201, null);
        assertCode(r2, 201, null);
        assertCode(approve(a, asm, UUID.fromString(json(r1).at("/data/id").asText())), 200, null);
        assertCode(approve(a, asm, UUID.fromString(json(r2).at("/data/id").asText())), 409, "PROXY_REPRESENTATION_LIMIT_REACHED");

        // Administrador desactivado: su token anterior deja de servir para designar.
        User admin2 = member(a, RoleType.COMMUNITY_ADMIN);
        String stale = fixture.bearer(admin2);
        Membership m = membershipRepository.findByUserIdAndTenantId(admin2.getId(), a.tenantId()).orElseThrow();
        m.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(m);
        Owned u3 = ownedUnit(a);
        assertThat(adminDesignate(stale, a, asm, u3.unit(), ownerRelationOf(u3.unit()), doc(), EVIDENCE_OK).getStatus()).isIn(401, 403);

        // Asamblea finalizada: no admite designaciones.
        assertCode(send(a.admin(), a, post("/api/v1/assemblies/{id}/finish", asm.getId())), 200, null);
        assertCode(adminDesignate(a.admin(), a, asm, u3.unit(), ownerRelationOf(u3.unit()), doc(), EVIDENCE_OK), 409, "ASSEMBLY_ENDED");
    }

    // ── P7-C (G4): historial del limite ───────────────────────────────────────

    @Test
    @DisplayName("P7-C · historial del limite: anterior/nuevo/fecha/administrador, orden y paginacion; solo administracion vigente; aislado")
    void limitHistory() throws Exception {
        assertThat(json(send(a.admin(), a, get("/api/v1/assemblies/proxy-settings/history"))).at("/data/total").asLong()).isZero();
        assertCode(setLimit(a, "{\"maxUnitsPerProxy\":2}"), 200, null);
        assertCode(setLimit(a, "{\"maxUnitsPerProxy\":5}"), 200, null);
        assertCode(setLimit(a, "{\"maxUnitsPerProxy\":null}"), 200, null);
        assertCode(setLimit(b, "{\"maxUnitsPerProxy\":7}"), 200, null);

        MockHttpServletResponse r = send(a.admin(), a, get("/api/v1/assemblies/proxy-settings/history"));
        assertCode(r, 200, null);
        JsonNode page = json(r).at("/data");
        assertThat(page.path("total").asLong()).isEqualTo(3);
        assertThat(page.path("communityId").asText()).isEqualTo(a.tenantId().toString());
        JsonNode items = page.path("items");
        assertThat(items).hasSize(3);
        // Mas reciente primero: 5 -> sin limite, 2 -> 5, (sin configuracion) -> 2
        assertThat(items.get(0).path("previousMaxUnits").asInt()).isEqualTo(5);
        assertThat(items.get(0).path("newMaxUnits").isNull()).isTrue();
        assertThat(items.get(1).path("previousMaxUnits").asInt()).isEqualTo(2);
        assertThat(items.get(1).path("newMaxUnits").asInt()).isEqualTo(5);
        assertThat(items.get(2).path("previousConfigured").asBoolean()).isFalse();
        assertThat(items.get(2).path("newMaxUnits").asInt()).isEqualTo(2);
        assertThat(items.get(0).path("actorUserId").asText()).isEqualTo(a.admin().getId().toString());
        assertThat(items.get(0).path("actorName").asText()).isEqualTo(a.admin().getFullName());
        assertThat(items.get(0).path("occurredAt").asText()).isNotBlank();
        assertThat(items.get(0).has("reason")).isTrue();
        assertThat(r.getContentAsString()).doesNotContain(a.admin().getEmail());
        assertThat(r.getContentAsString()).doesNotContain(b.tenantId().toString());

        // Paginacion estable y limites de tamano.
        JsonNode p1 = json(send(a.admin(), a, get("/api/v1/assemblies/proxy-settings/history?page=1&size=2"))).at("/data");
        assertThat(p1.path("items")).hasSize(1);
        assertThat(p1.path("items").get(0).path("newMaxUnits").asInt()).isEqualTo(2);
        assertThat(send(a.admin(), a, get("/api/v1/assemblies/proxy-settings/history?size=0")).getStatus()).isEqualTo(400);
        assertThat(send(a.admin(), a, get("/api/v1/assemblies/proxy-settings/history?size=101")).getStatus()).isEqualTo(400);
        assertThat(send(a.admin(), a, get("/api/v1/assemblies/proxy-settings/history?page=-1")).getStatus()).isEqualTo(400);

        // Aislamiento: la otra comunidad solo ve su cambio; el admin de B con la comunidad de A no entra.
        JsonNode pb = json(send(b.admin(), b, get("/api/v1/assemblies/proxy-settings/history"))).at("/data");
        assertThat(pb.path("total").asLong()).isEqualTo(1);
        assertThat(pb.path("items").get(0).path("newMaxUnits").asInt()).isEqualTo(7);
        assertThat(send(b.admin(), a, get("/api/v1/assemblies/proxy-settings/history")).getStatus()).isIn(401, 403);
        // Residente y personal: no.
        assertThat(send(a.owner(), a, get("/api/v1/assemblies/proxy-settings/history")).getStatus()).isEqualTo(403);
        assertThat(send(a.guard(), a, get("/api/v1/assemblies/proxy-settings/history")).getStatus()).isIn(401, 403);
        // Administrador desactivado (token anterior): no.
        User admin2 = member(a, RoleType.COMMUNITY_ADMIN);
        String stale = fixture.bearer(admin2);
        assertThat(send(stale, a, get("/api/v1/assemblies/proxy-settings/history")).getStatus()).isEqualTo(200);
        Membership m = membershipRepository.findByUserIdAndTenantId(admin2.getId(), a.tenantId()).orElseThrow();
        m.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(m);
        assertThat(send(stale, a, get("/api/v1/assemblies/proxy-settings/history")).getStatus()).isIn(401, 403);
        // Inmutable: el historial no se modifica.
        assertThat(catchThrowable(() -> jdbc.update("update community_proxy_settings_events set new_max_units = 9 where tenant_id = ?",
                a.tenantId()))).hasMessageContaining("solo insercion");
    }
}
