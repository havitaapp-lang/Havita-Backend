package com.havita.modules.assembly.proxy;

import com.havita.modules.assembly.application.AssemblyProxyService;
import com.havita.modules.assembly.infrastructure.AssemblyProxyJdbcRepository;
import com.havita.support.PostgresIntegrationTest;
import com.havita.support.TwoTenantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Fase 2 / P1: sin {@code havita.assembly-proxies.enabled=true} (configuracion por defecto, la de todos los perfiles
 * existentes) el modelo de poderes no existe en la aplicacion: ni rutas, ni servicio, ni acceso a la tabla de V102.
 */
class AssemblyProxyDisabledByDefaultIT extends PostgresIntegrationTest {

    @Autowired private ApplicationContext context;
    @Autowired private MockMvc mvc;
    @Autowired private TwoTenantFixture fixture;

    @Test
    @DisplayName("desactivado por defecto: sin beans de poderes y sus rutas no existen (404)")
    void disabledByDefault() throws Exception {
        assertThat(context.getBeanNamesForType(AssemblyProxyService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AssemblyProxyJdbcRepository.class)).isEmpty();
        // P2: delegacion y asistencia usan la guardia sin efecto (ninguna consulta a assembly_proxies, que aqui no existe).
        assertThat(context.getBean(com.havita.modules.assembly.application.ProxyRepresentationGuard.class))
                .isInstanceOf(com.havita.modules.assembly.application.NoOpProxyRepresentationGuard.class);
        var side = fixture.create().a();
        int create = mvc.perform(multipart("/api/v1/assemblies/{a}/proxies/admin", UUID.randomUUID())
                        .file(new MockMultipartFile("document", "p.pdf", "application/pdf", "%PDF-1.7".getBytes()))
                        .header("Authorization", fixture.bearer(side.admin())).header("X-Tenant-ID", side.tenantId().toString()))
                .andReturn().getResponse().getStatus();
        int approve = mvc.perform(post("/api/v1/assemblies/{a}/proxies/{p}/approve", UUID.randomUUID(), UUID.randomUUID())
                        .header("Authorization", fixture.bearer(side.admin())).header("X-Tenant-ID", side.tenantId().toString()))
                .andReturn().getResponse().getStatus();
        assertThat(create).isEqualTo(404);
        assertThat(approve).isEqualTo(404);
    }

    @Autowired private com.havita.modules.assembly.infrastructure.VoteLedgerSchema ledgerSchema;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    @DisplayName("P4 · bandera desactivada: sin voto por poder, asistido ni anulacion (404); el modo de voto sigue al esquema real")
    void proxyVotingDisabledByDefault() throws Exception {
        assertThat(context.getBeanNamesForType(com.havita.modules.assembly.application.ProxyVotingService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(com.havita.modules.assembly.api.ProxyVotingController.class)).isEmpty();
        var side = fixture.create().a();
        String bearer = fixture.bearer(side.admin());
        for (var req : java.util.List.of(
                post("/api/v1/assemblies/polls/{p}/proxy-vote", UUID.randomUUID()),
                post("/api/v1/assemblies/polls/{p}/assisted-vote", UUID.randomUUID()),
                post("/api/v1/assemblies/polls/{p}/participations/{x}/void", UUID.randomUUID(), UUID.randomUUID()))) {
            int status = mvc.perform(req.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}")
                            .header("Authorization", bearer).header("X-Tenant-ID", side.tenantId().toString()))
                    .andReturn().getResponse().getStatus();
            assertThat(status).isEqualTo(404);
        }
        // Esquema habitual (Hibernate, como las bases existentes): sin tablas de V104 y el voto de siempre. Esquema Flyway
        // con V104: participaciones activas aunque la bandera de poderes este desactivada (V-1/V-2 no dependen de ella).
        boolean v104 = Boolean.TRUE.equals(jdbc.queryForObject(
                "select to_regclass('public.poll_participations') is not null", Boolean.class));
        assertThat(ledgerSchema.state()).isEqualTo(v104 ? com.havita.modules.assembly.infrastructure.VoteLedgerSchema.State.COMPLETE
                : com.havita.modules.assembly.infrastructure.VoteLedgerSchema.State.ABSENT);
        if (!v104) {
            assertThat(jdbc.queryForObject("select to_regclass('public.assembly_vote_events') is null", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'poll_votes' "
                    + "and column_name = 'participation_id'", Long.class)).isZero();
        }
    }
}
