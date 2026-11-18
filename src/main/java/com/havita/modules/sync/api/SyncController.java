package com.havita.modules.sync.api;

import com.havita.modules.sync.application.SseAccessResolver;
import com.havita.modules.sync.application.SseService;
import com.havita.modules.sync.application.SseTicketRegistry;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sync")
@RequiredArgsConstructor
public class SyncController {

    private static final Logger log = LoggerFactory.getLogger(SyncController.class);

    private final SseService sseService;
    private final SseTicketRegistry ticketRegistry;
    private final SseAccessResolver accessResolver;

    // Secured endpoint: Generates a short-lived ticket
    @PostMapping("/stream-ticket")
    public ResponseEntity<Map<String, String>> generateStreamTicket() {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID userId = UUID.fromString(auth.getName()); // Subject should be User UUID

        // El JWT puede conservar una membresia ya revocada: se exige que siga ACTIVE en la base de datos.
        if (accessResolver.resolve(tenantId, userId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String ticket = ticketRegistry.generateTicket(tenantId, userId);
        return ResponseEntity.ok(Map.of("ticket", ticket));
    }

    // Public endpoint in security rules (but requires a valid one-time ticket).
    // Los rechazos no llevan cuerpo: no indican si el ticket existio ni a que comunidad pertenecia.
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestParam("ticket") String ticket) {
        SseTicketRegistry.TicketInfo info = ticketRegistry.consumeTicket(ticket);

        if (info == null) {
            log.warn("Invalid or expired SSE ticket");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        return sseService.subscribe(info.tenantId(), info.userId())
                .map(emitter -> {
                    log.info("SSE Connection established for User {} in Tenant {}", info.userId(), info.tenantId());
                    return ResponseEntity.ok(emitter);
                })
                .orElseGet(() -> {
                    log.warn("SSE connection refused for User {} in Tenant {}: membership is not active", info.userId(), info.tenantId());
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
                });
    }
}
