package com.havita.modules.push.application;

import java.util.UUID;

/**
 * Peticion de aviso push a residentes, publicada por los modulos de negocio (paquetes, visitas, comunicados,
 * asambleas) DENTRO de su transaccion. {@link com.havita.modules.push.infrastructure.ResidentPushListener} la envia
 * DESPUES del commit: si la operacion se deshace, no se avisa.
 *
 * <p>Los destinatarios los decide el servidor: los residentes ACTIVOS de la unidad o de la comunidad (nunca datos del
 * cliente), y {@code PushRecipientPolicy} vuelve a comprobar el acceso de cada suscripcion al enviar. El texto es fijo y
 * sin datos personales: puede verse en la pantalla bloqueada.
 */
public record ResidentPushRequested(UUID tenantId, Scope scope, UUID propertyId, String residentType, String title,
                                    String body, String type, String url) {

    public enum Scope { UNIT, COMMUNITY }

    /** Residentes activos de una unidad. */
    public static ResidentPushRequested unit(UUID tenantId, UUID propertyId, String body, String type, String url) {
        return new ResidentPushRequested(tenantId, Scope.UNIT, propertyId, null, "Havita", body, type, url);
    }

    /** Residentes activos de la comunidad; {@code residentType} null = todos (si no, p. ej. "Propietario"). */
    public static ResidentPushRequested community(UUID tenantId, String residentType, String body, String type, String url) {
        return new ResidentPushRequested(tenantId, Scope.COMMUNITY, null, residentType, "Havita", body, type, url);
    }
}
