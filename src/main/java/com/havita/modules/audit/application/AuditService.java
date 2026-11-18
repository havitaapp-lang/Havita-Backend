package com.havita.modules.audit.application;

import com.havita.modules.audit.api.dto.AuditEventResponse;
import com.havita.modules.audit.api.dto.RecordAuditEventRequest;
import com.havita.modules.audit.domain.AuditEvent;
import com.havita.modules.audit.infrastructure.AuditEventRepository;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> findAll(UUID requestedTenantId, String search, Pageable pageable) {
        // SEC-9: solo la administracion global elige comunidad; los demas ven siempre la de su peticion.
        UUID effectiveTenantId = com.havita.security.AuthorizationScope.isGlobal() && requestedTenantId != null
                ? requestedTenantId : TenantContextHolder.getTenantId();
        Page<AuditEvent> page;

        if (search != null && !search.isBlank()) {
            page = auditEventRepository.searchAuditEvents(effectiveTenantId, search.trim(), pageable);
        } else if (effectiveTenantId != null) {
            page = auditEventRepository.findByTenantId(effectiveTenantId, pageable);
        } else {
            page = auditEventRepository.findAll(pageable);
        }

        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> findAll(String search, Pageable pageable) {
        return findAll(null, search, pageable);
    }

    @Transactional
    public AuditEventResponse recordEvent(RecordAuditEventRequest request) {
        AuditEvent event = AuditEvent.builder()
                .timestamp(OffsetDateTime.now())
                .actorName(request.actorName() != null ? request.actorName().trim() : "Super Administrador Global")
                .actorEmail(request.actorEmail() != null ? request.actorEmail() : "superadmin@havita.com")
                .actorRole(request.actorRole() != null ? request.actorRole().trim() : "SUPER_ADMIN")
                .action(request.action() != null ? request.action().trim() : "EVENTO")
                .resource(request.resource() != null ? request.resource().trim() : "Comunidades")
                .tenantId(request.tenantId() != null ? request.tenantId() : TenantContextHolder.getTenantId())
                .tenantName(request.tenantName())
                .result(request.result() != null ? request.result().trim().toUpperCase() : "SUCCESS")
                .details(request.details())
                .ipAddress(request.ipAddress() != null ? request.ipAddress().trim() : "127.0.0.1")
                .build();

        event = auditEventRepository.save(event);
        log.info("Auditoría registrada: {} por {} ({}) en {}", event.getAction(), event.getActorName(), event.getActorRole(), event.getResource());
        return toResponse(event);
    }

    @Transactional
    public void recordSystemAudit(String action, String resource, String details, UUID tenantId, String tenantName, String result) {
        recordEvent(new RecordAuditEventRequest(
                "Super Administrador Global",
                "sistema@havita.com.co",
                "SUPER_ADMIN",
                action,
                resource,
                tenantId,
                tenantName,
                result != null ? result : "SUCCESS",
                details,
                "127.0.0.1"
        ));
    }

    private AuditEventResponse toResponse(AuditEvent e) {
        return new AuditEventResponse(
                e.getId(),
                e.getTimestamp(),
                e.getActorName(),
                e.getActorEmail(),
                e.getActorRole(),
                e.getAction(),
                e.getResource(),
                e.getTenantId(),
                e.getTenantName(),
                e.getResult(),
                e.getDetails(),
                e.getIpAddress(),
                e.getCreatedAt()
        );
    }
}
