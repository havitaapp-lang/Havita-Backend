package com.havita.modules.identity.domain;

import java.util.UUID;

/**
 * SEC-9 (P1): los permisos de un usuario cambiaron (membresia modificada o eliminada, o cuenta desactivada). Se
 * procesa tras confirmar la transaccion, con el estado final de PostgreSQL.
 */
public record AccessChangedEvent(UUID userId) {
}
