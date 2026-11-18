package com.havita.modules.assembly.application;

import com.havita.modules.assembly.domain.PollParticipation;
import com.havita.modules.assembly.domain.RepresentationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Fase 2 / P4 (P4-R5) — auditoria propia de la votacion ({@code assembly_vote_events}, V104); no reutiliza
 * {@code assembly_proxy_events}. Distingue el actor real (cuenta autenticada), la persona representada, la unidad, la
 * asamblea, la pregunta, el mecanismo de representacion y el titulo (poder o delegacion).
 *
 * Los exitos se escriben en la MISMA transaccion que el voto o la anulacion (o los dos o ninguno). Los rechazos, en
 * una transaccion propia: la operacion se revierte, el intento queda. Solo se registran rechazos sobre recursos ya
 * resueltos dentro de la comunidad (nunca identificadores de otra comunidad). Sin documentos, numeros de identificacion,
 * tokens ni nombres.
 *
 * Los llamadores solo lo usan si {@code VoteLedgerSchema.active()}: en las bases existentes la tabla no existe.
 * Garantia real: solo insercion para la aplicacion (triggers contra UPDATE, DELETE y TRUNCATE); el propietario de la
 * tabla o un superusuario pueden desactivarlos.
 */
@Slf4j
@Component
public class AssemblyVoteEvents {

    public static final String VOTE_CAST = "VOTE_CAST";
    public static final String ASSISTED_VOTE_CAST = "ASSISTED_VOTE_CAST";
    public static final String ASSISTED_VOTE_VOIDED = "ASSISTED_VOTE_VOIDED";
    /** Fase 2 / P5-A (V105): nuevo voto asistido que sustituye a uno anulado (continua la cadena). */
    public static final String ASSISTED_VOTE_REISSUED = "ASSISTED_VOTE_REISSUED";
    public static final String DUPLICATE_VOTE_REJECTED = "DUPLICATE_VOTE_REJECTED";
    public static final String UNAUTHORIZED_VOTE_REJECTED = "UNAUTHORIZED_VOTE_REJECTED";
    public static final String VOID_REJECTED = "VOID_REJECTED";
    public static final String ASSEMBLY_TRANSITION_REJECTED = "ASSEMBLY_TRANSITION_REJECTED";

    private final NamedParameterJdbcTemplate jdbc;

    public AssemblyVoteEvents(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Voto emitido, voto asistido emitido o voto asistido anulado: dentro de la transaccion de la operacion. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void success(PollParticipation p, String action, String reason) {
        insert(p.tenantId(), p.assemblyId(), p.pollId(), p.propertyId(), p.id(),
                ASSISTED_VOTE_VOIDED.equals(action) ? p.voidedByUserId() : p.actorUserId(),
                p.representedResidentId(), p.representationType(), p.proxyId(), p.delegationId(), action, "SUCCESS", null, reason);
    }

    /** Rechazo de un voto o de una anulacion (transaccion propia: queda aunque la operacion se revierta). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void denied(UUID tenantId, UUID assemblyId, UUID pollId, UUID propertyId, UUID participationId, UUID actorUserId,
                       RepresentationType type, UUID proxyId, String action, String reasonCode) {
        insert(tenantId, assemblyId, pollId, propertyId, participationId, actorUserId, null, type, proxyId, null,
                action, "DENIED", reasonCode, null);
    }

    /** V-4: intento de transicion de asamblea no permitida (p. ej. reabrir una finalizada). Transaccion propia. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transitionDenied(UUID tenantId, UUID assemblyId, UUID actorUserId, String reasonCode) {
        insert(tenantId, assemblyId, null, null, null, actorUserId, null, null, null, null,
                ASSEMBLY_TRANSITION_REJECTED, "DENIED", reasonCode, null);
    }

    private void insert(UUID tenantId, UUID assemblyId, UUID pollId, UUID propertyId, UUID participationId, UUID actorUserId,
                        UUID representedResidentId, RepresentationType type, UUID proxyId, UUID delegationId, String action,
                        String result, String reasonCode, String reason) {
        jdbc.update("INSERT INTO assembly_vote_events (id, tenant_id, assembly_id, poll_id, property_id, participation_id, "
                        + "actor_user_id, represented_resident_id, representation_type, proxy_id, delegation_id, action, result, "
                        + "reason_code, reason, occurred_at) VALUES (:id, :t, :a, :poll, :p, :part, :actor, :represented, :type, "
                        + ":proxy, :delegation, :action, :result, :code, :reason, :at)",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("t", tenantId).addValue("a", assemblyId)
                        .addValue("poll", pollId).addValue("p", propertyId).addValue("part", participationId)
                        .addValue("actor", actorUserId).addValue("represented", representedResidentId)
                        .addValue("type", type != null ? type.name() : null).addValue("proxy", proxyId)
                        .addValue("delegation", delegationId).addValue("action", action).addValue("result", result)
                        .addValue("code", reasonCode).addValue("reason", reason).addValue("at", OffsetDateTime.now()));
        // Sin datos personales en el log: identificadores, accion y resultado.
        log.info("Voto [{}] {} pregunta {} unidad {} (asamblea {}, tenant {}) {}", action, result, pollId, propertyId,
                assemblyId, tenantId, reasonCode != null ? reasonCode : "");
    }
}
