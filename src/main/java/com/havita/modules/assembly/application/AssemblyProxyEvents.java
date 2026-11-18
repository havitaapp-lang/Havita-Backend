package com.havita.modules.assembly.application;

import com.havita.modules.assembly.domain.AssemblyProxyChannel;
import com.havita.modules.assembly.domain.AssemblyProxyStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Fase 2 / P1: historial de eventos de los poderes ({@code assembly_proxy_events}, V102). Identifica actor, canal,
 * comunidad, asamblea, unidad, poder, accion, resultado y estados anterior y nuevo. No guarda documentos, claves de
 * almacenamiento, tokens, nombres ni numeros de documento.
 *
 * Los eventos de exito se escriben en la misma transaccion que el cambio (o los dos o ninguno). Los rechazos de una
 * transicion se escriben en una transaccion propia: la operacion rechazada se revierte, pero el intento queda.
 *
 * Garantia real: la tabla es de solo insercion para la aplicacion (trigger que rechaza UPDATE, DELETE y TRUNCATE).
 * NO es inmutable frente al propietario de la tabla o un superusuario, que pueden desactivar los triggers.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "havita.assembly-proxies.enabled", havingValue = "true")
public class AssemblyProxyEvents {

    private final NamedParameterJdbcTemplate jdbc;

    public AssemblyProxyEvents(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Evento de una operacion realizada, dentro de la transaccion de la propia operacion. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void success(UUID tenantId, UUID assemblyId, UUID propertyId, UUID proxyId, UUID actorUserId,
                        AssemblyProxyChannel channel, String action, AssemblyProxyStatus from, AssemblyProxyStatus to) {
        insert(tenantId, assemblyId, propertyId, proxyId, actorUserId, channel, action, "SUCCESS", from, to, null);
    }

    /** Fase 2 / P7 (G3): como {@link #success} con un codigo descriptivo (p. ej. el tipo de evidencia). Sin datos personales. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void successWithReason(UUID tenantId, UUID assemblyId, UUID propertyId, UUID proxyId, UUID actorUserId,
                                  AssemblyProxyChannel channel, String action, AssemblyProxyStatus from, AssemblyProxyStatus to,
                                  String reasonCode) {
        insert(tenantId, assemblyId, propertyId, proxyId, actorUserId, channel, action, "SUCCESS", from, to, reasonCode);
    }

    /** Intento de transicion no permitida: transaccion propia, sobrevive al rollback de la operacion. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void denied(UUID tenantId, UUID assemblyId, UUID propertyId, UUID proxyId, UUID actorUserId,
                       AssemblyProxyChannel channel, AssemblyProxyStatus from, AssemblyProxyStatus attempted, String reasonCode) {
        insert(tenantId, assemblyId, propertyId, proxyId, actorUserId, channel, "PROXY_TRANSITION_DENIED", "DENIED",
                from, attempted, reasonCode);
    }

    /**
     * Fase 2 / P2: rechazo de una operacion por un poder (delegacion o asistencia bloqueadas, reutilizacion, identidad
     * que no coincide...). Transaccion propia: queda aunque la operacion se revierta.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deniedAction(UUID tenantId, UUID assemblyId, UUID propertyId, UUID proxyId, UUID actorUserId,
                             AssemblyProxyChannel channel, String action, AssemblyProxyStatus status, String reasonCode) {
        insert(tenantId, assemblyId, propertyId, proxyId, actorUserId, channel, action, "DENIED", status, status, reasonCode);
    }

    private void insert(UUID tenantId, UUID assemblyId, UUID propertyId, UUID proxyId, UUID actorUserId,
                        AssemblyProxyChannel channel, String action, String result, AssemblyProxyStatus from,
                        AssemblyProxyStatus to, String reasonCode) {
        jdbc.update("INSERT INTO assembly_proxy_events (id, tenant_id, proxy_id, assembly_id, property_id, actor_user_id, "
                        + "actor_channel, action, result, from_status, to_status, reason_code, occurred_at) VALUES (:id, :t, :proxy, "
                        + ":a, :p, :actor, :channel, :action, :result, :from, :to, :reason, :at)",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("t", tenantId).addValue("proxy", proxyId)
                        .addValue("a", assemblyId).addValue("p", propertyId).addValue("actor", actorUserId)
                        .addValue("channel", channel.name()).addValue("action", action).addValue("result", result)
                        .addValue("from", from != null ? from.name() : null).addValue("to", to != null ? to.name() : null)
                        .addValue("reason", reasonCode).addValue("at", OffsetDateTime.now()));
        // Sin datos personales en el log: solo identificadores y la accion.
        log.info("Poder {} [{}] {} en asamblea {} (tenant {})", proxyId, action, result, assemblyId, tenantId);
    }
}
