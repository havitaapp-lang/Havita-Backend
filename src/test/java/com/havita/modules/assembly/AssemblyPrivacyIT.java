package com.havita.modules.assembly;

import com.havita.modules.assembly.domain.Assembly;
import com.havita.modules.assembly.domain.AssemblyAttendee;
import com.havita.modules.assembly.domain.AssemblyStatus;
import com.havita.modules.assembly.domain.Poll;
import com.havita.modules.assembly.domain.PollOption;
import com.havita.modules.assembly.domain.PollStatus;
import com.havita.modules.assembly.infrastructure.AssemblyAttendeeRepository;
import com.havita.modules.assembly.infrastructure.AssemblyRepository;
import com.havita.modules.assembly.infrastructure.PollRepository;
import com.havita.modules.assembly.infrastructure.PollVoteRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-6 (asambleas): asistentes, votaciones y resultados, voto y delegacion de voto, con dos comunidades.
 * La asamblea de cada comunidad esta en curso, con la asistencia de las dos unidades ocupadas registrada.
 */
class AssemblyPrivacyIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;
    @Autowired private AssemblyRepository assemblyRepository;
    @Autowired private AssemblyAttendeeRepository attendeeRepository;
    @Autowired private PollRepository pollRepository;
    @Autowired private PollVoteRepository voteRepository;
    @Autowired private VotingDelegationRepository delegationRepository;
    @Autowired private ResidentRepository residentRepository;
    @Autowired private MembershipRepository membershipRepository;
    @Autowired private UserRepository userRepository;

    private Side a;
    private Side b;
    private Assembly assemblyA;
    private Assembly assemblyB;
    private Poll livePollHidden;
    private Poll draftPoll;
    private Poll pollB;
    private User tenantOfHome;

    @BeforeEach
    void seed() {
        var c = fixture.create();
        a = c.a();
        b = c.b();
        assemblyA = assembly(a);
        assemblyB = assembly(b);
        attendee(a, assemblyA, a.home(), a.ownerResident());
        attendee(a, assemblyA, a.neighbourHome(), a.neighbourResident());
        attendee(b, assemblyB, b.home(), b.ownerResident());
        livePollHidden = poll(a, assemblyA, "Aprobar presupuesto", PollStatus.LIVE, false);
        draftPoll = poll(a, assemblyA, "Borrador: cambio de administrador", PollStatus.DRAFT, true);
        pollB = poll(b, assemblyB, "Votacion de otra comunidad", PollStatus.LIVE, true);

        // Arrendatario con cuenta en la unidad del propietario (para la delegacion de voto)
        tenantOfHome = userRepository.save(User.builder()
                .email("arrendatario-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test")
                .passwordHash("{noop}not-used").fullName("Arrendatario Home").phone("3000000000").build());
        membershipRepository.save(Membership.builder()
                .user(tenantOfHome).tenantId(a.tenantId()).role(RoleType.RESIDENT).status(MembershipStatus.ACTIVE).build());
        Resident rel = Resident.builder().property(a.home()).user(tenantOfHome).name("Arrendatario Home")
                .type("Arrendatario").email(tenantOfHome.getEmail()).status(ResidentStatus.ACTIVE).build();
        rel.setTenantId(a.tenantId());
        residentRepository.save(rel);
    }

    private Assembly assembly(Side side) {
        Assembly asm = Assembly.builder().title("Asamblea ordinaria").assemblyDate(LocalDate.now())
                .startTime(LocalTime.of(19, 0)).status(AssemblyStatus.IN_PROGRESS).build();
        asm.setTenantId(side.tenantId());
        return assemblyRepository.save(asm);
    }

    private void attendee(Side side, Assembly asm, Property property, Resident resident) {
        AssemblyAttendee at = AssemblyAttendee.builder().assembly(asm).property(property).resident(resident)
                .coefficient(new BigDecimal("0.125000")).build();
        at.setTenantId(side.tenantId());
        attendeeRepository.save(at);
    }

    private Poll poll(Side side, Assembly asm, String question, PollStatus status, boolean showResults) {
        Poll p = Poll.builder().assembly(asm).question(question).status(status).showResultsToResidents(showResults)
                .options(new ArrayList<>()).build();
        p.setTenantId(side.tenantId());
        p.getOptions().add(PollOption.builder().poll(p).text("Si").optionOrder(1).build());
        p.getOptions().add(PollOption.builder().poll(p).text("No").optionOrder(2).build());
        return pollRepository.save(p);
    }

    private ResultActions as(User user, Side side, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request
                .header("Authorization", fixture.bearer(user))
                .header("X-Tenant-ID", side.tenantId().toString()));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String vote(Poll poll, UUID propertyId) {
        return "{\"propertyId\":\"%s\",\"optionIds\":[\"%s\"]}".formatted(propertyId, poll.getOptions().get(0).getId());
    }

    private void deactivate(Resident resident) {
        Resident r = residentRepository.findById(resident.getId()).orElseThrow();
        r.setStatus(ResidentStatus.INACTIVE);
        residentRepository.save(r);
    }

    // ── Asistentes ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Asistentes de una asamblea de otra comunidad o inventada: 404, tambien para el admin")
    void attendeesOfOtherTenantAreHidden() throws Exception {
        as(a.admin(), a, get("/api/v1/assemblies/{id}/attendees", assemblyB.getId())).andExpect(status().isNotFound());
        as(a.owner(), a, get("/api/v1/assemblies/{id}/attendees", assemblyB.getId())).andExpect(status().isNotFound());
        as(a.owner(), a, get("/api/v1/assemblies/{id}/attendees", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("RESIDENT solo ve el registro de asistencia de su unidad; el admin ve el quorum completo")
    void residentSeesOnlyOwnAttendance() throws Exception {
        as(a.owner(), a, get("/api/v1/assemblies/{id}/attendees", assemblyA.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].propertyId").value(a.home().getId().toString()));
        as(a.admin(), a, get("/api/v1/assemblies/{id}/attendees", assemblyA.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("Con la relacion o la membresia revocadas el residente no ve asistentes")
    void revokedResidentSeesNoAttendees() throws Exception {
        deactivate(a.ownerResident());
        // A-8: sin ninguna relacion activa el JWT ya no autentica en la comunidad (401): no ve asistentes.
        as(a.owner(), a, get("/api/v1/assemblies/{id}/attendees", assemblyA.getId()))
                .andExpect(status().isUnauthorized());

        var m = membershipRepository.findByUserIdAndTenantId(a.neighbour().getId(), a.tenantId()).orElseThrow();
        String token = fixture.bearer(a.neighbour());
        m.setStatus(MembershipStatus.INACTIVE);
        membershipRepository.save(m);
        mvc.perform(get("/api/v1/assemblies/{id}/attendees", assemblyA.getId())
                        .header("Authorization", token).header("X-Tenant-ID", a.tenantId().toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("X-Tenant-ID de otra comunidad no autentica la peticion")
    void forgedTenantHeader() throws Exception {
        int s = as(a.owner(), b, get("/api/v1/assemblies/{id}/attendees", assemblyB.getId())).andReturn().getResponse().getStatus();
        assertThat(s).isIn(401, 403);
    }

    @Test
    @DisplayName("Auto-registro: la unidad del vecino responde 403 sin revelar si ya registro asistencia")
    void selfRegisterForNeighbourUnitIsForbidden() throws Exception {
        as(a.owner(), a, json(post("/api/v1/assemblies/{id}/attendees/self-register", assemblyA.getId()),
                "{\"propertyId\":\"%s\"}".formatted(a.neighbourHome().getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Auto-registro legitimo del propietario en una asamblea en curso")
    void ownerSelfRegisters() throws Exception {
        Assembly second = assembly(a);
        as(a.owner(), a, json(post("/api/v1/assemblies/{id}/attendees/self-register", second.getId()),
                "{\"propertyId\":\"%s\"}".formatted(a.home().getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.propertyId").value(a.home().getId().toString()));
    }

    @Test
    @DisplayName("Auto-registro con la relacion de propietario INACTIVE: 403")
    void inactiveOwnerCannotSelfRegister() throws Exception {
        // A-8: con la unica relacion o la membresia revocadas el JWT ya no autentica en esta comunidad
        // (ResidentAccessGate): la denegacion llega como 401 antes que el 403/404 del endpoint.
        Assembly second = assembly(a);
        deactivate(a.ownerResident());
        as(a.owner(), a, json(post("/api/v1/assemblies/{id}/attendees/self-register", second.getId()),
                "{\"propertyId\":\"%s\"}".formatted(a.home().getId())))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
        assertThat(attendeeRepository.countByAssemblyId(second.getId())).isZero();
    }

    // ── Votaciones y resultados ────────────────────────────────────────────────

    @Test
    @DisplayName("Votaciones de una asamblea de otra comunidad: 404")
    void pollsOfOtherTenantAreHidden() throws Exception {
        as(a.admin(), a, get("/api/v1/assemblies/{id}/polls", assemblyB.getId())).andExpect(status().isNotFound());
        as(a.owner(), a, get("/api/v1/assemblies/polls/{id}", pollB.getId())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("RESIDENT no ve votaciones en borrador; el admin si")
    void draftsAreForStaff() throws Exception {
        as(a.owner(), a, get("/api/v1/assemblies/{id}/polls", assemblyA.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(livePollHidden.getId().toString()));
        as(a.owner(), a, get("/api/v1/assemblies/polls/{id}", draftPoll.getId())).andExpect(status().isNotFound());
        as(a.admin(), a, get("/api/v1/assemblies/{id}/polls", assemblyA.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("showResultsToResidents=false: el residente no recibe resultados por opcion; el admin si")
    void hiddenResultsAreNotSentToResidents() throws Exception {
        // Cierre E5 / D14: vota el representante registrado de la unidad (la administración ya no emite votos).
        as(a.neighbour(), a, json(post("/api/v1/assemblies/polls/{id}/vote", livePollHidden.getId()), vote(livePollHidden, a.neighbourHome().getId())))
                .andExpect(status().isOk());
        as(a.owner(), a, get("/api/v1/assemblies/polls/{id}", livePollHidden.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.options[0].votesCount").value(0))
                .andExpect(jsonPath("$.data.options[0].percentage").value(nullValue()))
                .andExpect(jsonPath("$.data.totalVotedCoefficient").value(nullValue()));
        as(a.admin(), a, get("/api/v1/assemblies/polls/{id}", livePollHidden.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.options[0].votesCount").value(1));
    }

    // ── Voto ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Votar por la unidad del vecino: 403 y no se registra el voto")
    void cannotVoteForNeighbourUnit() throws Exception {
        as(a.owner(), a, json(post("/api/v1/assemblies/polls/{id}/vote", livePollHidden.getId()), vote(livePollHidden, a.neighbourHome().getId())))
                .andExpect(status().isForbidden());
        assertThat(voteRepository.countByPollId(livePollHidden.getId())).isZero();
    }

    @Test
    @DisplayName("Propietario con la relacion INACTIVE ya no vota aunque su asistencia siga registrada")
    void inactiveOwnerCannotVote() throws Exception {
        // A-8: con la unica relacion o la membresia revocadas el JWT ya no autentica en esta comunidad
        // (ResidentAccessGate): la denegacion llega como 401 antes que el 403/404 del endpoint.
        deactivate(a.ownerResident());
        as(a.owner(), a, json(post("/api/v1/assemblies/polls/{id}/vote", livePollHidden.getId()), vote(livePollHidden, a.home().getId())))
                .andExpect(status().is(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
        assertThat(voteRepository.countByPollId(livePollHidden.getId())).isZero();
    }

    @Test
    @DisplayName("El propietario vota por su unidad")
    void ownerVotes() throws Exception {
        as(a.owner(), a, json(post("/api/v1/assemblies/polls/{id}/vote", livePollHidden.getId()), vote(livePollHidden, a.home().getId())))
                .andExpect(status().isOk());
        assertThat(voteRepository.countByPollId(livePollHidden.getId())).isEqualTo(1);
    }

    // ── Delegacion de voto ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Delegacion: un residente no la consulta para otra unidad ni otra comunidad (403); su unidad y el admin si")
    void delegationIsUnitScoped() throws Exception {
        as(a.owner(), a, get("/api/v1/properties/{id}/voting-delegation", a.neighbourHome().getId())).andExpect(status().isForbidden());
        as(a.owner(), a, get("/api/v1/properties/{id}/voting-delegation", b.home().getId())).andExpect(status().isForbidden());
        as(a.owner(), a, get("/api/v1/properties/{id}/voting-delegation", UUID.randomUUID())).andExpect(status().isForbidden());
        as(a.owner(), a, get("/api/v1/properties/{id}/voting-delegation", a.home().getId())).andExpect(status().isOk());
        as(a.admin(), a, get("/api/v1/properties/{id}/voting-delegation", a.neighbourHome().getId())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Delegacion legitima: el propietario delega en su arrendatario y ambos la consultan; el vecino no")
    void ownerDelegatesToTenant() throws Exception {
        // Cierre E3: la fixture registra la asistencia de esta unidad en una asamblea EN CURSO, lo que ahora bloquea la
        // delegacion (DelegationAttendanceIT). Esta prueba cubre la privacidad de la lectura: se finaliza esa asamblea.
        assemblyA.finish();
        assemblyRepository.save(assemblyA);
        as(a.owner(), a, post("/api/v1/properties/{id}/voting-delegation", a.home().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delegateUserId").value(tenantOfHome.getId().toString()));
        as(tenantOfHome, a, get("/api/v1/properties/{id}/voting-delegation", a.home().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delegateUserId").value(tenantOfHome.getId().toString()));
        as(a.neighbour(), a, get("/api/v1/properties/{id}/voting-delegation", a.home().getId())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Delegacion: un propietario con la relacion INACTIVE no puede delegar")
    void inactiveOwnerCannotDelegate() throws Exception {
        // A-8: con la unica relacion o la membresia revocadas el JWT ya no autentica en esta comunidad
        // (ResidentAccessGate): la denegacion llega como 401 antes que el 403/404 del endpoint.
        deactivate(a.ownerResident());
        as(a.owner(), a, post("/api/v1/properties/{id}/voting-delegation", a.home().getId())).andExpect(status().is(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(403))));
        assertThat(delegationRepository.findByPropertyIdAndStatusAndTenantId(a.home().getId(),
                com.havita.modules.assembly.domain.VotingDelegationStatus.ACTIVE, a.tenantId())).isEmpty();
    }
}
