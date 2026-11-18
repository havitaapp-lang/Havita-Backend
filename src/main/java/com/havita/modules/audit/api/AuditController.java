package com.havita.modules.audit.api;

import com.havita.common.api.ApiResponse;
import com.havita.common.api.PageResponse;
import com.havita.modules.audit.api.dto.AuditEventResponse;
import com.havita.modules.audit.api.dto.RecordAuditEventRequest;
import com.havita.modules.audit.application.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
@Tag(name = "Security Hardening & Audit Logs", description = "Trazabilidad forense, registro inmutable de eventos de seguridad y bitácora")
public class AuditController {

    private final AuditService auditService;
    private final com.havita.modules.identity.infrastructure.UserRepository userRepository;

    @GetMapping("/logs")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN', 'READ_ONLY', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Consultar bitácora de eventos de auditoría")
    public ResponseEntity<ApiResponse<PageResponse<AuditEventResponse>>> findAll(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "timestamp", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<AuditEventResponse> page = tenantId != null
                ? auditService.findAll(tenantId, search, pageable)
                : auditService.findAll(search, pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @PostMapping("/events")
    // SEC-9: operacion interna del panel de administracion global; la identidad, el rol y la IP los fija el servidor.
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Registrar evento de auditoría o incidente de seguridad")
    public ResponseEntity<ApiResponse<AuditEventResponse>> recordEvent(
            @Valid @RequestBody com.havita.modules.audit.api.dto.ClientAuditEventRequest request,
            org.springframework.security.core.Authentication authentication,
            HttpServletRequest servletRequest) {
        // SEC-9: actor, rol e IP salen del servidor; los campos de identidad del cliente se ignoran.
        com.havita.modules.identity.domain.User actor = userRepository.findById(UUID.fromString(authentication.getName()))
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("Actor no valido"));
        String actorRole = com.havita.security.AuthorizationScope.current().map(a -> a.role().name())
                .orElseGet(() -> authentication.getAuthorities().stream().map(a -> a.getAuthority())
                        .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).findFirst().orElse("SYSTEM"));
        String remoteIp = servletRequest.getRemoteAddr();
        RecordAuditEventRequest enrichedRequest = new RecordAuditEventRequest(
                actor.getFullName(),
                actor.getEmail(),
                actorRole,
                request.action(),
                request.resource(),
                request.tenantId(),
                request.tenantName(),
                request.result(),
                maskSecrets(request.details()),
                remoteIp
        );

        AuditEventResponse response = auditService.recordEvent(enrichedRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Evento de auditoría registrado exitosamente"));
    }

    private static final java.util.regex.Pattern SECRETS = java.util.regex.Pattern.compile(
            "(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+"                       // credencial Bearer
                    + "|eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*" // JWT suelto
                    + "|(/resident-invitations/|/register/invite/)[A-Za-z0-9_-]{20,}"); // enlace de invitacion

    /** SEC-9 (P1): el detalle lo escribe un cliente; no debe guardar tokens ni credenciales. */
    static String maskSecrets(String details) {
        if (details == null) {
            return null;
        }
        java.util.regex.Matcher m = SECRETS.matcher(details);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String prefix = m.group(1) != null ? m.group(1) : (m.group(2) != null ? m.group(2) : "");
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(prefix + "***"));
        }
        m.appendTail(out);
        return out.toString();
    }
}
