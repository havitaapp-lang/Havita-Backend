package com.havita.modules.assembly.infrastructure;

import com.havita.modules.assembly.domain.AssemblyProxy;
import com.havita.modules.assembly.domain.AssemblyProxyChannel;
import com.havita.modules.assembly.domain.AssemblyProxyStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fase 2 / P1: acceso JDBC a {@code assembly_proxies} (V102). Sin entidad JPA para que Hibernate (ddl-auto=update) no
 * cree la tabla en ninguna base existente; solo existe donde se aplico la migracion. Toda consulta filtra por tenant.
 * Participa en la transaccion JPA en curso (misma conexion).
 */
@Repository
@ConditionalOnProperty(name = "havita.assembly-proxies.enabled", havingValue = "true")
public class AssemblyProxyJdbcRepository {

    private static final String COLUMNS = "id, tenant_id, assembly_id, property_id, grantor_resident_id, grantor_user_id, "
            + "proxy_user_id, proxy_full_name, proxy_document_type, proxy_document_number, status, document_key, "
            + "document_content_type, document_size_bytes, document_sha256, document_registered_at, "
            + "document_registered_by_user_id, created_by_user_id, created_channel, reviewed_by_user_id, reviewed_at, "
            + "review_decision, rejection_reason, revoked_by_user_id, revoked_at, revocation_reason, used_at, expired_at, "
            + "created_at, updated_at, version";

    private final NamedParameterJdbcTemplate jdbc;

    public AssemblyProxyJdbcRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AssemblyProxy> MAPPER = AssemblyProxyJdbcRepository::map;

    private static AssemblyProxy map(ResultSet rs, int i) throws SQLException {
        return new AssemblyProxy(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("assembly_id", UUID.class), rs.getObject("property_id", UUID.class),
                rs.getObject("grantor_resident_id", UUID.class), rs.getObject("grantor_user_id", UUID.class),
                rs.getObject("proxy_user_id", UUID.class), rs.getString("proxy_full_name"),
                rs.getString("proxy_document_type"), rs.getString("proxy_document_number"),
                AssemblyProxyStatus.valueOf(rs.getString("status")), rs.getString("document_key"),
                rs.getString("document_content_type"), rs.getLong("document_size_bytes"), rs.getString("document_sha256"),
                rs.getObject("document_registered_at", OffsetDateTime.class),
                rs.getObject("document_registered_by_user_id", UUID.class), rs.getObject("created_by_user_id", UUID.class),
                AssemblyProxyChannel.valueOf(rs.getString("created_channel")), rs.getObject("reviewed_by_user_id", UUID.class),
                rs.getObject("reviewed_at", OffsetDateTime.class), rs.getString("review_decision"),
                rs.getString("rejection_reason"), rs.getObject("revoked_by_user_id", UUID.class),
                rs.getObject("revoked_at", OffsetDateTime.class), rs.getString("revocation_reason"),
                rs.getObject("used_at", OffsetDateTime.class), rs.getObject("expired_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                rs.getLong("version"));
    }

    public void insert(AssemblyProxy p) {
        jdbc.update("INSERT INTO assembly_proxies (" + COLUMNS + ") VALUES (:id, :tenantId, :assemblyId, :propertyId, "
                + ":grantorResidentId, :grantorUserId, :proxyUserId, :proxyFullName, :proxyDocumentType, :proxyDocumentNumber, "
                + ":status, :documentKey, :documentContentType, :documentSizeBytes, :documentSha256, :documentRegisteredAt, "
                + ":documentRegisteredBy, :createdBy, :createdChannel, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, "
                + ":createdAt, :createdAt, 0)",
                new MapSqlParameterSource()
                        .addValue("id", p.id()).addValue("tenantId", p.tenantId()).addValue("assemblyId", p.assemblyId())
                        .addValue("propertyId", p.propertyId()).addValue("grantorResidentId", p.grantorResidentId())
                        .addValue("grantorUserId", p.grantorUserId()).addValue("proxyUserId", p.proxyUserId())
                        .addValue("proxyFullName", p.proxyFullName()).addValue("proxyDocumentType", p.proxyDocumentType())
                        .addValue("proxyDocumentNumber", p.proxyDocumentNumber()).addValue("status", p.status().name())
                        .addValue("documentKey", p.documentKey()).addValue("documentContentType", p.documentContentType())
                        .addValue("documentSizeBytes", p.documentSizeBytes()).addValue("documentSha256", p.documentSha256())
                        .addValue("documentRegisteredAt", p.documentRegisteredAt())
                        .addValue("documentRegisteredBy", p.documentRegisteredByUserId())
                        .addValue("createdBy", p.createdByUserId()).addValue("createdChannel", p.createdChannel().name())
                        .addValue("createdAt", p.createdAt()));
    }

    public Optional<AssemblyProxy> find(UUID id, UUID assemblyId, UUID tenantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM assembly_proxies WHERE id = :id AND assembly_id = :assemblyId "
                        + "AND tenant_id = :tenantId", params(id, assemblyId, tenantId), MAPPER).stream().findFirst();
    }

    /** Fila del poder bloqueada (FOR NO KEY UPDATE: compatible con el FOR KEY SHARE de los eventos que la referencian). */
    public Optional<AssemblyProxy> lock(UUID id, UUID assemblyId, UUID tenantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM assembly_proxies WHERE id = :id AND assembly_id = :assemblyId "
                        + "AND tenant_id = :tenantId FOR NO KEY UPDATE", params(id, assemblyId, tenantId), MAPPER)
                .stream().findFirst();
    }

    public List<AssemblyProxy> findByAssembly(UUID assemblyId, UUID tenantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM assembly_proxies WHERE assembly_id = :assemblyId AND tenant_id = :tenantId "
                + "ORDER BY created_at, id", params(null, assemblyId, tenantId), MAPPER);
    }

    /** Poderes de la asamblea que pueden vencer (en revision o aprobados), bloqueados, en orden estable. */
    public List<AssemblyProxy> lockExpirable(UUID assemblyId, UUID tenantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM assembly_proxies WHERE assembly_id = :assemblyId AND tenant_id = :tenantId "
                + "AND status IN ('PENDING_REVIEW', 'APPROVED') ORDER BY id FOR NO KEY UPDATE", params(null, assemblyId, tenantId), MAPPER);
    }

    public boolean existsLive(UUID assemblyId, UUID propertyId, UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM assembly_proxies WHERE assembly_id = :assemblyId "
                        + "AND property_id = :propertyId AND tenant_id = :tenantId AND status IN ('PENDING_REVIEW', 'APPROVED', 'USED'))",
                params(null, assemblyId, tenantId).addValue("propertyId", propertyId), Boolean.class));
    }

    /**
     * Unidades que el mismo apoderado (por cuenta o por documento) representa ya con poderes aprobados o utilizados en
     * la asamblea, sin contar el poder indicado.
     */
    public long countApprovedForProxy(UUID assemblyId, UUID tenantId, UUID excludeId, UUID proxyUserId, String docType, String docNumber) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM assembly_proxies WHERE assembly_id = :assemblyId AND tenant_id = :tenantId "
                        + "AND id <> :excludeId AND status IN ('APPROVED', 'USED') AND ((CAST(:proxyUserId AS uuid) IS NOT NULL "
                        + "AND proxy_user_id = :proxyUserId) OR (proxy_document_type = :docType AND upper(proxy_document_number) = upper(:docNumber)))",
                params(null, assemblyId, tenantId).addValue("excludeId", excludeId).addValue("proxyUserId", proxyUserId)
                        .addValue("docType", docType).addValue("docNumber", docNumber), Long.class);
        return n == null ? 0 : n;
    }

    /** Serializa las aprobaciones que cuentan unidades del mismo apoderado (bloqueo de transaccion, sin filas). */
    public void lockProxyIdentity(UUID assemblyId, String docType, String docNumber) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))",
                new MapSqlParameterSource("key", "assembly-proxy:" + assemblyId + ":" + docType + ":" + docNumber.toUpperCase(java.util.Locale.ROOT)));
    }

    public void approve(UUID id, UUID reviewerId, OffsetDateTime at) {
        update("status = 'APPROVED', reviewed_by_user_id = :actor, reviewed_at = :at, review_decision = 'APPROVED'", id, reviewerId, at, null);
    }

    public void reject(UUID id, UUID reviewerId, OffsetDateTime at, String reason) {
        update("status = 'REJECTED', reviewed_by_user_id = :actor, reviewed_at = :at, review_decision = 'REJECTED', rejection_reason = :reason",
                id, reviewerId, at, reason);
    }

    public void revoke(UUID id, UUID actorId, OffsetDateTime at, String reason) {
        update("status = 'REVOKED', revoked_by_user_id = :actor, revoked_at = :at, revocation_reason = :reason", id, actorId, at, reason);
    }

    public void expire(UUID id, OffsetDateTime at) {
        update("status = 'EXPIRED', expired_at = :at", id, null, at, null);
    }

    public void replaceDocument(UUID id, String key, String contentType, long size, String sha256, UUID actorId, OffsetDateTime at) {
        jdbc.update("UPDATE assembly_proxies SET document_key = :key, document_content_type = :ct, document_size_bytes = :size, "
                        + "document_sha256 = :sha, document_registered_at = :at, document_registered_by_user_id = :actor, "
                        + "updated_at = :at, version = version + 1 WHERE id = :id AND status = 'PENDING_REVIEW'",
                new MapSqlParameterSource("id", id).addValue("key", key).addValue("ct", contentType).addValue("size", size)
                        .addValue("sha", sha256).addValue("actor", actorId).addValue("at", at));
    }

    private void update(String set, UUID id, UUID actor, OffsetDateTime at, String reason) {
        int n = jdbc.update("UPDATE assembly_proxies SET " + set + ", updated_at = :at, version = version + 1 WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("actor", actor).addValue("at", at).addValue("reason", reason));
        if (n != 1) {
            throw new IllegalStateException("assembly_proxies: fila no actualizada");
        }
    }

    private static MapSqlParameterSource params(UUID id, UUID assemblyId, UUID tenantId) {
        return new MapSqlParameterSource("id", id).addValue("assemblyId", assemblyId).addValue("tenantId", tenantId);
    }

    // ── lecturas auxiliares con bloqueo, en el orden global (unidad → otorgante → poder → asamblea) ──────────

    /**
     * Relacion de propietario ACTIVA (censo) de la unidad, bloqueada FOR SHARE: una baja concurrente de esa relacion
     * espera a que termine la operacion, o ya la ve inactiva. Vacio si no existe, no es de esta unidad y comunidad o
     * no es un propietario activo.
     */
    public Optional<UUID[]> lockActiveOwnerRelation(UUID residentId, UUID propertyId, UUID tenantId) {
        return jdbc.query("SELECT id, user_id FROM residents WHERE id = :id AND property_id = :propertyId AND tenant_id = :tenantId "
                        + "AND status = 'ACTIVE' AND type = 'Propietario' FOR SHARE",
                new MapSqlParameterSource("id", residentId).addValue("propertyId", propertyId).addValue("tenantId", tenantId),
                (rs, i) -> new UUID[]{rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class)}).stream().findFirst();
    }

    /** Relacion de propietario ACTIVA de la cuenta en la unidad (la primera por id), bloqueada FOR SHARE. */
    public Optional<UUID> lockActiveOwnerRelationOfUser(UUID userId, UUID propertyId, UUID tenantId) {
        return jdbc.query("SELECT id FROM residents WHERE user_id = :userId AND property_id = :propertyId AND tenant_id = :tenantId "
                        + "AND status = 'ACTIVE' AND type = 'Propietario' ORDER BY id LIMIT 1 FOR SHARE",
                new MapSqlParameterSource("userId", userId).addValue("propertyId", propertyId).addValue("tenantId", tenantId),
                (rs, i) -> rs.getObject("id", UUID.class)).stream().findFirst();
    }

    /** Estado de la asamblea leido con FOR SHARE (ultimo bloqueo del orden; choca con finalizar). Vacio si no es del tenant. */
    public Optional<String> lockAssemblyStatus(UUID assemblyId, UUID tenantId) {
        return jdbc.query("SELECT status FROM assemblies WHERE id = :id AND tenant_id = :tenantId FOR SHARE",
                new MapSqlParameterSource("id", assemblyId).addValue("tenantId", tenantId), (rs, i) -> rs.getString(1)).stream().findFirst();
    }

    public boolean existsActiveDelegation(UUID propertyId, UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM voting_delegations WHERE property_id = :p "
                + "AND tenant_id = :t AND status = 'ACTIVE')", new MapSqlParameterSource("p", propertyId).addValue("t", tenantId), Boolean.class));
    }

    public boolean existsAttendance(UUID assemblyId, UUID propertyId, UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM assembly_attendees WHERE assembly_id = :a "
                        + "AND property_id = :p AND tenant_id = :t)",
                new MapSqlParameterSource("a", assemblyId).addValue("p", propertyId).addValue("t", tenantId), Boolean.class));
    }

    // ── Fase 2 / P2: delegacion, asistencia y uso del poder ──────────────────────

    /**
     * Membresia ACTIVA de la cuenta en la comunidad (y cuenta ACTIVA), bloqueada FOR SHARE: una desactivacion concurrente
     * espera a que termine la operacion o ya se ve. Contra PostgreSQL, nunca contra el JWT.
     */
    public boolean lockActiveMembership(UUID userId, UUID tenantId) {
        return !jdbc.queryForList("SELECT m.id FROM memberships m JOIN users u ON u.id = m.user_id WHERE m.user_id = :u "
                        + "AND m.tenant_id = :t AND m.status = 'ACTIVE' AND u.status = 'ACTIVE' FOR SHARE OF m, u",
                new MapSqlParameterSource("u", userId).addValue("t", tenantId)).isEmpty();
    }

    /** Un poder que impide delegar (R1): APPROVED o USED de la unidad en una asamblea NO finalizada. [id, asamblea]. */
    public Optional<UUID[]> findProxyBlockingDelegation(UUID propertyId, UUID tenantId) {
        return jdbc.query("SELECT p.id, p.assembly_id FROM assembly_proxies p JOIN assemblies a ON a.id = p.assembly_id "
                        + "AND a.tenant_id = p.tenant_id WHERE p.property_id = :p AND p.tenant_id = :t "
                        + "AND p.status IN ('APPROVED', 'USED') AND a.status <> 'ENDED' ORDER BY p.created_at, p.id LIMIT 1",
                new MapSqlParameterSource("p", propertyId).addValue("t", tenantId),
                (rs, i) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}).stream().findFirst();
    }

    /** Poder APROBADO (todavia no usado) de la unidad para la asamblea: impide la asistencia tradicional (R6). */
    public Optional<UUID> findApprovedProxy(UUID assemblyId, UUID propertyId, UUID tenantId) {
        return jdbc.query("SELECT id FROM assembly_proxies WHERE assembly_id = :a AND property_id = :p AND tenant_id = :t "
                        + "AND status = 'APPROVED' LIMIT 1",
                new MapSqlParameterSource("a", assemblyId).addValue("p", propertyId).addValue("t", tenantId),
                (rs, i) -> rs.getObject(1, UUID.class)).stream().findFirst();
    }

    // ── Fase 2 / P6: lecturas para el frontend ───────────────────────────────

    /** Eventos de un poder de esta comunidad, en orden cronologico. */
    public List<com.havita.modules.assembly.api.dto.AssemblyProxyEventResponse> findEvents(UUID proxyId, UUID tenantId) {
        return jdbc.query("SELECT action, result, actor_channel, actor_user_id, from_status, to_status, reason_code, occurred_at "
                        + "FROM assembly_proxy_events WHERE proxy_id = :p AND tenant_id = :t ORDER BY occurred_at, id",
                new MapSqlParameterSource("p", proxyId).addValue("t", tenantId),
                (rs, i) -> new com.havita.modules.assembly.api.dto.AssemblyProxyEventResponse(rs.getString("action"),
                        rs.getString("result"), rs.getString("actor_channel"), rs.getObject("actor_user_id", UUID.class),
                        rs.getString("from_status"), rs.getString("to_status"), rs.getString("reason_code"),
                        rs.getObject("occurred_at", OffsetDateTime.class)));
    }

    // ── Fase 2 / P4: voto mediante poder ─────────────────────────────────────

    /** Asistencia con la que se utilizo el poder (V103); vacio si el poder no esta USED. */
    public Optional<UUID> findUsedAttendee(UUID proxyId, UUID tenantId) {
        return jdbc.query("SELECT attendee_id FROM assembly_proxies WHERE id = :id AND tenant_id = :t AND status = 'USED'",
                new MapSqlParameterSource("id", proxyId).addValue("t", tenantId),
                (rs, i) -> rs.getObject(1, UUID.class)).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    /**
     * ¿La relacion otorgante sigue siendo propietaria ACTIVA de la unidad? Lectura SIN bloqueo: el voto ya tiene la
     * asistencia bloqueada y bloquear despues filas del censo invertiria el orden de las bajas de residentes.
     */
    public boolean isActiveOwnerRelation(UUID residentId, UUID propertyId, UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM residents WHERE id = :id "
                        + "AND property_id = :p AND tenant_id = :t AND status = 'ACTIVE' AND type = 'Propietario')",
                new MapSqlParameterSource("id", residentId).addValue("p", propertyId).addValue("t", tenantId), Boolean.class));
    }

    /** APPROVED -> USED con su asistencia (y, si la registro la administracion, quien verifico la identidad y cuando). */
    public void markUsed(UUID id, UUID attendeeId, String usedChannel, UUID verifiedBy, OffsetDateTime verifiedAt, OffsetDateTime at) {
        int n = jdbc.update("UPDATE assembly_proxies SET status = 'USED', used_at = :at, attendee_id = :att, used_channel = :ch, "
                        + "identity_verified_by_user_id = :vb, identity_verified_at = :va, updated_at = :at, version = version + 1 "
                        + "WHERE id = :id AND status = 'APPROVED'",
                new MapSqlParameterSource("id", id).addValue("att", attendeeId).addValue("ch", usedChannel)
                        .addValue("vb", verifiedBy).addValue("va", verifiedAt).addValue("at", at));
        if (n != 1) {
            throw new IllegalStateException("assembly_proxies: el poder no estaba aprobado");
        }
    }
}
