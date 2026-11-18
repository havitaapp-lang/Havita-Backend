package com.havita.modules.identity.domain;

import java.util.UUID;

/**
 * P8-B: se revocaron TODAS las sesiones del usuario (cierre de sesion en el servidor: revoca todos sus refresh tokens).
 * Los modulos que mantienen canales hacia sus dispositivos (p. ej. Web Push) dejan de enviarles informacion.
 */
public record UserSessionsRevokedEvent(UUID userId) {
}
