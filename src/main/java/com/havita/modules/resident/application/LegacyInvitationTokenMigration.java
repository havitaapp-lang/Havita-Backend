package com.havita.modules.resident.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * SEC-7: convierte los tokens de invitacion heredados (guardados en claro) a su SHA-256, en sitio.
 *
 * <ul>
 *   <li>No invalida ninguna invitacion: quien tiene el enlace presenta el token, su hash coincide con el valor
 *       convertido y la invitacion conserva estado, vencimiento y usos.</li>
 *   <li>Idempotente: solo toca filas cuyo valor no es ya un hash (64 hex); cada UPDATE exige el valor anterior, asi
 *       que dos ejecuciones simultaneas no convierten dos veces.</li>
 *   <li>No borra ni cambia otras columnas; los historicos (USED, REVOKED, EXPIRED) tambien se convierten para no
 *       dejar secretos en claro.</li>
 *   <li>Nunca escribe tokens en el log: solo conteos e ids.</li>
 * </ul>
 *
 * Se activa con {@code havita.invitations.legacy-token-migration}: {@code off} (por defecto), {@code dry-run}
 * (solo informa) o {@code apply}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyInvitationTokenMigration implements ApplicationRunner {

    static final String LEGACY = "token_hash !~ '^[0-9a-f]{64}$'";

    private final JdbcTemplate jdbc;

    @Value("${havita.invitations.legacy-token-migration:off}")
    private String mode;

    public record Plan(List<UUID> ids, Map<String, Long> byStatus) {
    }

    @Override
    public void run(ApplicationArguments args) {
        switch (mode == null ? "off" : mode.trim().toLowerCase()) {
            case "dry-run" -> {
                Plan plan = plan();
                log.warn("SEC-7 dry-run: {} invitaciones con token heredado en claro {}; ids={}",
                        plan.ids().size(), plan.byStatus(), plan.ids());
            }
            case "apply" -> {
                Plan plan = plan();
                int converted = apply();
                log.warn("SEC-7: {} de {} invitaciones heredadas convertidas a SHA-256 {}; ids={}",
                        converted, plan.ids().size(), plan.byStatus(), plan.ids());
            }
            default -> {
                Long pending = jdbc.queryForObject("select count(*) from resident_invitations where " + LEGACY, Long.class);
                if (pending != null && pending > 0) {
                    log.warn("SEC-7: {} invitaciones conservan el token en claro y no validaran hasta convertirlas "
                            + "(havita.invitations.legacy-token-migration=dry-run|apply)", pending);
                }
            }
        }
    }

    public Plan plan() {
        Map<String, Long> byStatus = new TreeMap<>();
        jdbc.query("select status, count(*) from resident_invitations where " + LEGACY + " group by status",
                rs -> { byStatus.put(rs.getString(1), rs.getLong(2)); });
        List<UUID> ids = jdbc.queryForList("select id from resident_invitations where " + LEGACY + " order by id", UUID.class);
        return new Plan(ids, byStatus);
    }

    @Transactional
    public int apply() {
        record Legacy(UUID id, String token) {
        }
        List<Legacy> rows = jdbc.query("select id, token_hash from resident_invitations where " + LEGACY,
                (rs, n) -> new Legacy(rs.getObject(1, UUID.class), rs.getString(2)));
        int converted = 0;
        for (Legacy row : rows) {
            converted += jdbc.update("update resident_invitations set token_hash = ? where id = ? and token_hash = ?",
                    InvitationTokens.hash(row.token()), row.id(), row.token());
        }
        return converted;
    }
}
