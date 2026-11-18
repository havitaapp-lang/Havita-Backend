package com.havita.modules.assembly.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Fase 2 / P1: estados de un poder de representacion para una asamblea. La misma tabla de transiciones la aplica
 * PostgreSQL (trigger {@code assembly_proxies_guard} de V102), asi que una ruta que la ignore tampoco puede saltarsela.
 *
 * <pre>
 * PENDING_REVIEW ──► APPROVED ──► USED      (USED: lo fijara la asistencia en una etapa posterior; no en P1)
 *       │               ├──────► REVOKED
 *       │               └──────► EXPIRED
 *       ├──► REJECTED
 *       ├──► REVOKED
 *       └──► EXPIRED
 * </pre>
 * REJECTED, REVOKED, USED y EXPIRED son finales: un poder rechazado, revocado o vencido solo se sustituye con una
 * solicitud nueva (otra fila, otro documento, otra revision).
 */
public enum AssemblyProxyStatus {
    PENDING_REVIEW, APPROVED, REJECTED, REVOKED, USED, EXPIRED;

    private static final Map<AssemblyProxyStatus, Set<AssemblyProxyStatus>> ALLOWED = Map.of(
            PENDING_REVIEW, EnumSet.of(APPROVED, REJECTED, REVOKED, EXPIRED),
            APPROVED, EnumSet.of(REVOKED, USED, EXPIRED),
            REJECTED, EnumSet.noneOf(AssemblyProxyStatus.class),
            REVOKED, EnumSet.noneOf(AssemblyProxyStatus.class),
            USED, EnumSet.noneOf(AssemblyProxyStatus.class),
            EXPIRED, EnumSet.noneOf(AssemblyProxyStatus.class));

    /** Estados que ocupan la unica representacion de la unidad en la asamblea (indice uq_assembly_proxy_live_per_unit). */
    public static final Set<AssemblyProxyStatus> LIVE = EnumSet.of(PENDING_REVIEW, APPROVED, USED);

    public boolean canTransitionTo(AssemblyProxyStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public boolean isFinal() {
        return ALLOWED.get(this).isEmpty();
    }
}
