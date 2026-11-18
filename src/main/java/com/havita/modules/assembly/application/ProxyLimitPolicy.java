package com.havita.modules.assembly.application;

import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.InvalidRequestException;
import com.havita.modules.assembly.infrastructure.VoteLedgerSchema;
import com.havita.security.AccessValidator;
import com.havita.security.TenantContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Fase 2 / P5-D — limite de unidades que un mismo apoderado puede representar en una asamblea, POR COMUNIDAD
 * ({@code community_proxy_settings}, V106) con el valor global como respaldo
 * ({@code havita.assembly-proxies.max-units-per-proxy}; por defecto no existe = sin limite).
 *
 * <ul>
 *   <li>Sin fila de la comunidad: el valor global. Con fila: su valor ({@code null} = sin limite en esa comunidad).</li>
 *   <li>Solo enteros positivos; no se inventan limites juridicos (cada comunidad configura el de su reglamento).</li>
 *   <li>Se evalua al APROBAR y al USAR un poder, leyendo la fila con FOR SHARE: un cambio concurrente del limite
 *       (FOR UPDATE) espera o ya se ve; nunca se mezcla. No se aplica retroactivamente a poderes ya utilizados ni a
 *       votos.</li>
 *   <li>Sin V106 (bases sin migrar): solo el valor global; la configuracion por comunidad responde 409 controlado.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "havita.assembly-proxies.enabled", havingValue = "true")
public class ProxyLimitPolicy {

    private final NamedParameterJdbcTemplate jdbc;
    private final VoteLedgerSchema schema;
    private final AccessValidator accessValidator;
    private final Integer globalLimit;

    public ProxyLimitPolicy(NamedParameterJdbcTemplate jdbc, VoteLedgerSchema schema, AccessValidator accessValidator,
                            @Value("${havita.assembly-proxies.max-units-per-proxy:#{null}}") Integer globalLimit) {
        this.jdbc = jdbc;
        this.schema = schema;
        this.accessValidator = accessValidator;
        this.globalLimit = globalLimit;
    }

    public record Setting(boolean configured, Integer communityLimit, Integer globalLimit, Integer effectiveLimit) {}

    /** Limite vigente para la comunidad (null = sin limite), con la fila de configuracion bloqueada FOR SHARE. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Integer resolveLocked(UUID tenantId) {
        if (!schema.communityLimitsSupported()) {
            return globalLimit;
        }
        List<Integer[]> row = jdbc.query("SELECT max_units_per_proxy FROM community_proxy_settings WHERE tenant_id = :t FOR SHARE",
                new MapSqlParameterSource("t", tenantId), (rs, i) -> new Integer[]{(Integer) rs.getObject(1)});
        return row.isEmpty() ? globalLimit : row.get(0)[0];
    }

    /** Configuracion de la comunidad actual (administracion vigente). */
    @Transactional(readOnly = true)
    public Setting current() {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.requireCommunityAdministrator();
        if (!schema.communityLimitsSupported()) {
            return new Setting(false, null, globalLimit, globalLimit);
        }
        List<Integer[]> row = jdbc.query("SELECT max_units_per_proxy FROM community_proxy_settings WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenantId), (rs, i) -> new Integer[]{(Integer) rs.getObject(1)});
        return row.isEmpty() ? new Setting(false, null, globalLimit, globalLimit)
                : new Setting(true, row.get(0)[0], globalLimit, row.get(0)[0]);
    }

    /** Fase 2 / P7 (G4): un cambio del limite. El historial no guarda motivo (V106 no lo tiene): {@code reason} es null. */
    public record HistoryEntry(UUID id, boolean previousConfigured, Integer previousMaxUnits, Integer newMaxUnits,
                               OffsetDateTime occurredAt, UUID actorUserId, String actorName, String reason) {}

    public record HistoryPage(UUID communityId, String communityName, List<HistoryEntry> items, int page, int size, long total) {}

    public static final int MAX_HISTORY_PAGE_SIZE = 100;

    /**
     * Fase 2 / P7 (G4): historial de cambios del limite de la comunidad ACTUAL, del mas reciente al mas antiguo, paginado.
     * Solo la administracion vigente (permisos resueltos contra PostgreSQL en cada peticion). Lectura pura de
     * {@code community_proxy_settings_events} (solo insercion por disparador, V106); nunca de otra comunidad.
     */
    @Transactional(readOnly = true)
    public HistoryPage history(int page, int size) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.requireCommunityAdministrator();
        if (page < 0 || size < 1 || size > MAX_HISTORY_PAGE_SIZE) {
            throw new InvalidRequestException("INVALID_PAGE", "Página o tamaño no válidos (tamaño entre 1 y " + MAX_HISTORY_PAGE_SIZE + ").");
        }
        String name = jdbc.query("SELECT name FROM tenants WHERE id = :t", new MapSqlParameterSource("t", tenantId),
                (rs, i) -> rs.getString(1)).stream().findFirst().orElse(null);
        if (!schema.communityLimitsSupported()) {
            return new HistoryPage(tenantId, name, List.of(), page, size, 0);
        }
        MapSqlParameterSource args = new MapSqlParameterSource("t", tenantId).addValue("limit", size)
                .addValue("offset", (long) page * size);
        Long total = jdbc.queryForObject("SELECT count(*) FROM community_proxy_settings_events WHERE tenant_id = :t", args, Long.class);
        List<HistoryEntry> items = jdbc.query("SELECT e.id, e.previous_configured, e.previous_max_units, e.new_max_units, "
                        + "e.occurred_at, e.actor_user_id, u.full_name FROM community_proxy_settings_events e "
                        + "LEFT JOIN users u ON u.id = e.actor_user_id WHERE e.tenant_id = :t "
                        + "ORDER BY e.occurred_at DESC, e.id DESC LIMIT :limit OFFSET :offset", args,
                (rs, i) -> new HistoryEntry(rs.getObject(1, UUID.class), rs.getBoolean(2), (Integer) rs.getObject(3),
                        (Integer) rs.getObject(4), rs.getObject(5, OffsetDateTime.class), rs.getObject(6, UUID.class),
                        rs.getString(7), null));
        return new HistoryPage(tenantId, name, items, page, size, total != null ? total : 0);
    }

    /**
     * Fija el limite de la comunidad actual ({@code null} = sin limite). Membresia administrativa FOR SHARE y despues la
     * fila de la comunidad (INSERT ... ON CONFLICT bloquea la fila): una aprobacion concurrente espera o ve el valor
     * anterior completo. Queda en el historial con el valor anterior y el nuevo. No toca poderes existentes.
     */
    @Transactional
    public Setting update(Integer maxUnitsPerProxy) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        accessValidator.lockCommunityAdministrator();
        if (!schema.communityLimitsSupported()) {
            throw new BusinessRuleConflictException("PROXY_SETTINGS_SCHEMA_REQUIRED",
                    "La configuración por comunidad requiere el esquema V106, que no está aplicado.");
        }
        if (maxUnitsPerProxy != null && maxUnitsPerProxy < 1) {
            throw new InvalidRequestException("INVALID_PROXY_LIMIT", "El límite debe ser un entero positivo o vacío (sin límite).");
        }
        UUID actorId = accessValidator.currentUserId();
        // Cambios del limite de una comunidad, uno detras de otro (el historial registra el valor anterior exacto).
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))",
                new MapSqlParameterSource("k", "proxy-settings:" + tenantId));
        List<Integer[]> before = jdbc.query("SELECT max_units_per_proxy FROM community_proxy_settings WHERE tenant_id = :t FOR UPDATE",
                new MapSqlParameterSource("t", tenantId), (rs, i) -> new Integer[]{(Integer) rs.getObject(1)});
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO community_proxy_settings (tenant_id, max_units_per_proxy, updated_by_user_id, updated_at, version) "
                        + "VALUES (:t, :max, :actor, :at, 0) ON CONFLICT (tenant_id) DO UPDATE SET max_units_per_proxy = :max, "
                        + "updated_by_user_id = :actor, updated_at = :at, version = community_proxy_settings.version + 1",
                new MapSqlParameterSource("t", tenantId).addValue("max", maxUnitsPerProxy).addValue("actor", actorId).addValue("at", now));
        jdbc.update("INSERT INTO community_proxy_settings_events (id, tenant_id, actor_user_id, previous_configured, "
                        + "previous_max_units, new_max_units, occurred_at) VALUES (:id, :t, :actor, :prevConf, :prev, :max, :at)",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("t", tenantId).addValue("actor", actorId)
                        .addValue("prevConf", !before.isEmpty()).addValue("prev", before.isEmpty() ? null : before.get(0)[0])
                        .addValue("max", maxUnitsPerProxy).addValue("at", now));
        return new Setting(true, maxUnitsPerProxy, globalLimit, maxUnitsPerProxy);
    }
}
