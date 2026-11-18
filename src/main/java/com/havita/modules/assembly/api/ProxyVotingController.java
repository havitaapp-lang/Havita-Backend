package com.havita.modules.assembly.api;

import com.havita.common.api.ApiResponse;
import com.havita.modules.assembly.api.dto.AssistedVoteRequest;
import com.havita.modules.assembly.api.dto.ParticipationResponse;
import com.havita.modules.assembly.api.dto.ProxyVoteRequest;
import com.havita.modules.assembly.api.dto.VoidParticipationRequest;
import com.havita.modules.assembly.application.ProxyVotingService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Fase 2 / P4 — voto mediante poder, voto asistido y anulacion de un voto asistido (PREPARADO, NO ACTIVADO). Solo existe
 * con {@code havita.assembly-proxies.enabled=true}; por defecto estas rutas no estan registradas. {@code @PreAuthorize}
 * es el primer filtro por rol; las comprobaciones reales (membresia vigente, poder, asistencia, comunidad, pregunta y
 * asamblea) las hace el servicio contra PostgreSQL con los bloqueos tomados.
 */
@RestController
@RequestMapping("/api/v1/assemblies/polls/{pollId}")
@ConditionalOnProperty(name = "havita.assembly-proxies.enabled", havingValue = "true")
public class ProxyVotingController {

    private final ProxyVotingService service;

    public ProxyVotingController(ProxyVotingService service) {
        this.service = service;
    }

    @PostMapping("/proxy-vote")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "El apoderado con cuenta vota por la unidad de su poder utilizado")
    public ResponseEntity<ApiResponse<ParticipationResponse>> proxyVote(@PathVariable UUID pollId,
                                                                       @Valid @RequestBody ProxyVoteRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.castAsAccountProxy(pollId, body)));
    }

    @PostMapping("/assisted-vote")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "La administracion registra el voto que expresa en persona un apoderado externo (sin cuenta)")
    public ResponseEntity<ApiResponse<ParticipationResponse>> assistedVote(@PathVariable UUID pollId,
                                                                          @Valid @RequestBody AssistedVoteRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.castAssisted(pollId, body)));
    }

    @org.springframework.web.bind.annotation.GetMapping("/participations")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Historial de participaciones de la pregunta (vigentes, anuladas y nuevas emisiones)")
    public ResponseEntity<ApiResponse<java.util.List<com.havita.modules.assembly.api.dto.ParticipationAuditResponse>>> history(
            @PathVariable UUID pollId) {
        return ResponseEntity.ok(ApiResponse.ok(service.history(pollId)));
    }

    @PostMapping("/participations/{participationId}/void")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Anular un voto asistido (pregunta abierta, motivo obligatorio; el voto se conserva)")
    public ResponseEntity<ApiResponse<ParticipationResponse>> voidAssisted(@PathVariable UUID pollId,
                                                                          @PathVariable UUID participationId,
                                                                          @Valid @RequestBody VoidParticipationRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(service.voidAssisted(pollId, participationId, body.reason())));
    }
}
