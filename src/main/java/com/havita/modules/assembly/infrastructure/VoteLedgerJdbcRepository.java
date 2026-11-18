package com.havita.modules.assembly.infrastructure;

import com.havita.modules.assembly.domain.PollParticipation;
import com.havita.modules.assembly.domain.RepresentationType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Fase 2 / P4 — acceso JDBC a {@code poll_participations} y a la columna {@code poll_votes.participation_id} (V104). Sin
 * entidades JPA: Hibernate (ddl-auto=update) no crea nada de esto en las bases existentes. Solo debe usarse cuando
 * {@link VoteLedgerSchema#active()} es true. Participa en la transaccion JPA en curso (misma conexion).
 */
@Repository
public class VoteLedgerJdbcRepository {

    private static final String COLUMNS = "id, tenant_id, assembly_id, poll_id, property_id, attendee_id, representation_type, "
            + "actor_user_id, resident_id, delegation_id, proxy_id, grantor_resident_id, identity_verified_by_user_id, "
            + "identity_verified_at, identity_verification_method, expressed_by_proxy_confirmed, status, cast_at, "
            + "voided_by_user_id, voided_at, void_reason";

    private final NamedParameterJdbcTemplate jdbc;
    private final VoteLedgerSchema schema;

    public VoteLedgerJdbcRepository(NamedParameterJdbcTemplate jdbc, VoteLedgerSchema schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    /** Columnas leidas: con V105 tambien la participacion sustituida; sin V105 null (la columna no existe). */
    private String columns() {
        return COLUMNS + (schema.reissueSupported() ? ", supersedes_participation_id"
                : ", CAST(NULL AS uuid) AS supersedes_participation_id");
    }

    private static final RowMapper<PollParticipation> MAPPER = VoteLedgerJdbcRepository::map;

    private static PollParticipation map(ResultSet rs, int i) throws SQLException {
        return new PollParticipation(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getObject("assembly_id", UUID.class),
                rs.getObject("poll_id", UUID.class), rs.getObject("property_id", UUID.class), rs.getObject("attendee_id", UUID.class),
                RepresentationType.valueOf(rs.getString("representation_type")), rs.getObject("actor_user_id", UUID.class),
                rs.getObject("resident_id", UUID.class), rs.getObject("delegation_id", UUID.class), rs.getObject("proxy_id", UUID.class),
                rs.getObject("grantor_resident_id", UUID.class), rs.getObject("identity_verified_by_user_id", UUID.class),
                rs.getObject("identity_verified_at", OffsetDateTime.class), rs.getString("identity_verification_method"),
                (Boolean) rs.getObject("expressed_by_proxy_confirmed"), rs.getString("status"),
                rs.getObject("cast_at", OffsetDateTime.class), rs.getObject("voided_by_user_id", UUID.class),
                rs.getObject("voided_at", OffsetDateTime.class), rs.getString("void_reason"),
                rs.getObject("supersedes_participation_id", UUID.class));
    }

    public void insertParticipation(PollParticipation p) {
        boolean chain = p.supersedesParticipationId() != null;
        if (chain && !schema.reissueSupported()) {
            throw new IllegalStateException("poll_participations: nueva emision sin el esquema V105");
        }
        jdbc.update("INSERT INTO poll_participations (id, tenant_id, assembly_id, poll_id, property_id, attendee_id, "
                        + "representation_type, actor_user_id, resident_id, delegation_id, proxy_id, grantor_resident_id, "
                        + "identity_verified_by_user_id, identity_verified_at, identity_verification_method, "
                        + "expressed_by_proxy_confirmed, status, cast_at" + (chain ? ", supersedes_participation_id" : "")
                        + ") VALUES (:id, :t, :a, :poll, :p, :att, :type, :actor, :resident, :delegation, :proxy, :grantor, "
                        + ":vb, :va, :vm, :confirmed, 'VALID', :at" + (chain ? ", :supersedes" : "") + ")",
                new MapSqlParameterSource("id", p.id()).addValue("t", p.tenantId()).addValue("a", p.assemblyId())
                        .addValue("poll", p.pollId()).addValue("p", p.propertyId()).addValue("att", p.attendeeId())
                        .addValue("type", p.representationType().name()).addValue("actor", p.actorUserId())
                        .addValue("resident", p.residentId()).addValue("delegation", p.delegationId())
                        .addValue("proxy", p.proxyId()).addValue("grantor", p.grantorResidentId())
                        .addValue("vb", p.identityVerifiedByUserId()).addValue("va", p.identityVerifiedAt())
                        .addValue("vm", p.identityVerificationMethod()).addValue("confirmed", p.expressedByProxyConfirmed())
                        .addValue("at", p.castAt()).addValue("supersedes", p.supersedesParticipationId()));
    }

    /** Una opcion de la participacion (misma comunidad, pregunta y unidad: lo exige la FK compuesta de V104). */
    public void insertVote(PollParticipation p, UUID optionId, UUID residentId, BigDecimal coefficient) {
        jdbc.update("INSERT INTO poll_votes (id, tenant_id, poll_id, poll_option_id, property_id, resident_id, coefficient, "
                        + "voted_at, created_at, updated_at, participation_id) VALUES (:id, :t, :poll, :opt, :p, :resident, :coef, "
                        + ":at, :at, :at, :part)",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("t", p.tenantId()).addValue("poll", p.pollId())
                        .addValue("opt", optionId).addValue("p", p.propertyId()).addValue("resident", residentId)
                        .addValue("coef", coefficient).addValue("at", p.castAt()).addValue("part", p.id()));
    }

    /** Participacion de una pregunta de esta comunidad; vacio si no existe o es de otra pregunta o comunidad. */
    public Optional<PollParticipation> find(UUID id, UUID pollId, UUID tenantId) {
        return jdbc.query("SELECT " + columns() + " FROM poll_participations WHERE id = :id AND poll_id = :poll AND tenant_id = :t",
                new MapSqlParameterSource("id", id).addValue("poll", pollId).addValue("t", tenantId), MAPPER).stream().findFirst();
    }

    /** Fila bloqueada FOR NO KEY UPDATE (compatible con el FOR KEY SHARE de los eventos que la referencian). */
    public Optional<PollParticipation> lock(UUID id, UUID tenantId) {
        return jdbc.query("SELECT " + columns() + " FROM poll_participations WHERE id = :id AND tenant_id = :t FOR NO KEY UPDATE",
                new MapSqlParameterSource("id", id).addValue("t", tenantId), MAPPER).stream().findFirst();
    }

    /** ¿La unidad tiene en la pregunta solo participaciones anuladas (ninguna vigente ni voto historico)? */
    public boolean onlyVoidedParticipations(UUID pollId, UUID propertyId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM poll_participations WHERE poll_id = :poll "
                        + "AND property_id = :p AND status = 'VOIDED') AND NOT EXISTS (SELECT 1 FROM poll_votes v "
                        + "LEFT JOIN poll_participations pp ON pp.id = v.participation_id WHERE v.poll_id = :poll AND v.property_id = :p "
                        + "AND (v.participation_id IS NULL OR pp.status = 'VALID'))",
                new MapSqlParameterSource("poll", pollId).addValue("p", propertyId), Boolean.class));
    }

    /**
     * Fase 2 / P5-A: cabeza de la cadena de la unidad en la pregunta si es una participacion ANULADA todavia no
     * sustituida (la unica que una nueva emision puede continuar). Vacio si no hay participaciones, si la ultima esta
     * vigente o si el esquema no tiene V105.
     */
    public Optional<PollParticipation> findReissueHead(UUID pollId, UUID propertyId, UUID tenantId) {
        if (!schema.reissueSupported()) {
            return Optional.empty();
        }
        return jdbc.query("SELECT " + columns() + " FROM poll_participations p WHERE p.poll_id = :poll AND p.property_id = :p "
                        + "AND p.tenant_id = :t AND p.status = 'VOIDED' AND NOT EXISTS (SELECT 1 FROM poll_participations c "
                        + "WHERE c.supersedes_participation_id = p.id) ORDER BY p.cast_at DESC, p.id LIMIT 1",
                new MapSqlParameterSource("poll", pollId).addValue("p", propertyId).addValue("t", tenantId), MAPPER)
                .stream().findFirst();
    }

    /** Historial de participaciones de una pregunta de esta comunidad (vigentes y anuladas), en orden de emision. */
    public java.util.List<PollParticipation> history(UUID pollId, UUID tenantId) {
        return jdbc.query("SELECT " + columns() + " FROM poll_participations WHERE poll_id = :poll AND tenant_id = :t "
                        + "ORDER BY property_id, cast_at, id",
                new MapSqlParameterSource("poll", pollId).addValue("t", tenantId), MAPPER);
    }

    /** Opciones elegidas en una participacion (el voto anulado conserva su opcion). */
    public java.util.List<UUID> optionsOf(UUID participationId) {
        return jdbc.queryForList("SELECT poll_option_id FROM poll_votes WHERE participation_id = :id ORDER BY poll_option_id",
                new MapSqlParameterSource("id", participationId), UUID.class);
    }

    public void markVoided(UUID id, UUID adminId, OffsetDateTime at, String reason) {
        int n = jdbc.update("UPDATE poll_participations SET status = 'VOIDED', voided_by_user_id = :admin, voided_at = :at, "
                        + "void_reason = :reason WHERE id = :id AND status = 'VALID'",
                new MapSqlParameterSource("id", id).addValue("admin", adminId).addValue("at", at).addValue("reason", reason));
        if (n != 1) {
            throw new IllegalStateException("poll_participations: la participacion no estaba vigente");
        }
    }

    // ── resultados vigentes: votos sin participacion (historicos) + votos de participaciones VALID ────────────

    public record OptionTally(long votes, BigDecimal coefficient) {}

    public record Tally(long totalVotes, BigDecimal totalCoefficient, Map<UUID, OptionTally> byOption) {}

    private static final String EFFECTIVE = " FROM poll_votes v LEFT JOIN poll_participations pp ON pp.id = v.participation_id "
            + "WHERE v.poll_id = :poll AND (v.participation_id IS NULL OR pp.status = 'VALID')";

    public Tally tally(UUID pollId) {
        Map<UUID, OptionTally> byOption = new HashMap<>();
        long[] total = {0};
        BigDecimal[] coef = {BigDecimal.ZERO};
        jdbc.query("SELECT v.poll_option_id, count(*) AS n, COALESCE(SUM(v.coefficient), 0) AS c" + EFFECTIVE
                        + " GROUP BY v.poll_option_id", new MapSqlParameterSource("poll", pollId), rs -> {
                    long n = rs.getLong("n");
                    BigDecimal c = rs.getBigDecimal("c");
                    byOption.put(rs.getObject("poll_option_id", UUID.class), new OptionTally(n, c));
                    total[0] += n;
                    coef[0] = coef[0].add(c);
                });
        return new Tally(total[0], coef[0], byOption);
    }
}
