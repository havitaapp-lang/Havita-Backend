package com.havita.modules.identity.api;

import com.havita.common.api.ApiResponse;
import com.havita.modules.identity.api.dto.LegalConsentRequest;
import com.havita.modules.identity.application.LegalConsentService;
import com.havita.modules.identity.domain.LegalDocumentType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/identity/consents")
@RequiredArgsConstructor
public class LegalConsentController {

    private final LegalConsentService legalConsentService;

    @PostMapping
    public ResponseEntity<ApiResponse<Void>> recordConsent(
            @Valid @RequestBody LegalConsentRequest request,
            HttpServletRequest httpRequest) {

        String userIdStr = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        UUID userId = UUID.fromString(userIdStr);

        String ipAddress = httpRequest.getRemoteAddr();
        
        String xForwardedFor = httpRequest.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            ipAddress = xForwardedFor.split(",")[0].trim();
        }
        
        String userAgent = httpRequest.getHeader("User-Agent");

        legalConsentService.recordConsent(userId, request.documentType(), request.documentVersion(), ipAddress, userAgent);

        return ResponseEntity.ok(ApiResponse.ok(null, "Consentimiento registrado exitosamente"));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> checkConsent(
            @RequestParam LegalDocumentType documentType,
            @RequestParam String documentVersion) {

        String userIdStr = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        UUID userId = UUID.fromString(userIdStr);

        boolean hasAccepted = legalConsentService.hasUserAcceptedVersion(userId, documentType, documentVersion);

        return ResponseEntity.ok(ApiResponse.ok(Map.of("hasAccepted", hasAccepted), "Verificación de consentimiento exitosa"));
    }
}
