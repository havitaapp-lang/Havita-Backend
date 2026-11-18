package com.havita.modules.assembly.application;

import java.util.UUID;

/**
 * Fase 2 / P2: punto unico por el que los flujos TRADICIONALES (delegar el voto, registrar asistencia) respetan los
 * poderes de asamblea. Se llama siempre con la fila de la unidad ya bloqueada (FOR NO KEY UPDATE), la misma que toman
 * todas las operaciones sobre poderes: asi la exclusividad se cumple en los dos sentidos, tambien en carrera.
 *
 * Con {@code havita.assembly-proxies.enabled=false} (por defecto) la implementacion es {@link NoOpProxyRepresentationGuard}:
 * no hace ninguna consulta, de modo que los flujos tradicionales funcionan aunque las tablas de poderes no existan.
 */
public interface ProxyRepresentationGuard {

    /** Rechaza (409) delegar el voto de la unidad si un poder APPROVED o USED de una asamblea no finalizada lo impide. */
    void assertDelegationAllowed(UUID propertyId, UUID tenantId, UUID actorUserId);

    /** Rechaza (409) una asistencia tradicional si la unidad tiene un poder APPROVED para esa asamblea. */
    void assertTraditionalAttendanceAllowed(UUID assemblyId, UUID propertyId, UUID tenantId, UUID actorUserId, boolean byAdministration);
}
