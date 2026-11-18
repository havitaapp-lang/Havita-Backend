package com.havita.modules.identity.domain;

import java.util.UUID;

/**
 * Cierre SEC-9 (R3): una membresia gano, cambio o perdio su rol. Lo publica la capa de persistencia justo antes del
 * commit de la transaccion que hizo el cambio, venga del flujo que venga, para que se audite en esa misma transaccion.
 * {@code previousRole}/{@code previousTenantId} son null en una asignacion nueva; {@code newRole} es null al retirarla.
 */
public record MembershipRoleChangedEvent(
        Kind kind,
        UUID membershipId,
        UUID userId,
        UUID previousTenantId,
        UUID tenantId,
        RoleType previousRole,
        RoleType newRole) {

    public enum Kind { ASSIGNED, CHANGED, REMOVED }
}
