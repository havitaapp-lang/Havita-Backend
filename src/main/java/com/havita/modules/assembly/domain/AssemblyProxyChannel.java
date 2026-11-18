package com.havita.modules.assembly.domain;

/**
 * Fase 2 / P1-P2: quien actua sobre un poder o queda bloqueado por el: el propietario otorgante, la administracion, el
 * sistema, el propio apoderado (P2, asistencia) o el arrendatario (P2). Solo OWNER y ADMIN registran poderes.
 */
public enum AssemblyProxyChannel {
    OWNER, ADMIN, SYSTEM, PROXY, TENANT
}
