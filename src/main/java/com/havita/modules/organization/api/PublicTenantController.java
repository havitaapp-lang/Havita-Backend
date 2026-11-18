package com.havita.modules.organization.api;

import com.havita.common.api.ApiResponse;
import com.havita.modules.organization.api.dto.TenantPublicConfigResponse;
import com.havita.modules.organization.domain.Tenant;
import com.havita.modules.organization.infrastructure.TenantRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
@Tag(name = "Public Tenants", description = "Endpoints públicos para resolución y personalización de copropiedades")
public class PublicTenantController {

    private final TenantRepository tenantRepository;

    @GetMapping("/tenant-config")
    @Operation(summary = "Obtener configuración pública del tenant basado en el hostname o parámetros", 
               description = "Extrae el subdominio del Host header, query param o X-Tenant-ID y retorna configuración pública mínima.")
    public ResponseEntity<ApiResponse<TenantPublicConfigResponse>> getTenantConfig(
            HttpServletRequest request,
            @RequestParam(required = false) String subdomain,
            @RequestParam(required = false) UUID tenantId) {

        if (tenantId != null) {
            return tenantRepository.findById(tenantId)
                    .filter(Tenant::isOperational)
                    .map(this::toConfigResponse)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        }

        String targetSubdomain = (subdomain != null && !subdomain.isBlank()) ? subdomain.trim() : null;

        if (targetSubdomain == null) {
            String serverName = request.getHeader("X-Forwarded-Host");
            if (serverName == null || serverName.isBlank()) {
                serverName = request.getServerName();
            } else {
                serverName = serverName.split(":")[0];
            }
            targetSubdomain = extractSubdomain(serverName);
        }

        if (targetSubdomain == null || targetSubdomain.isBlank()) {
            String xTenantId = request.getHeader("X-Tenant-ID");
            if (xTenantId != null && !xTenantId.isBlank()) {
                try {
                    UUID parsed = UUID.fromString(xTenantId.trim());
                    return tenantRepository.findById(parsed)
                            .filter(Tenant::isOperational)
                            .map(this::toConfigResponse)
                            .orElseGet(() -> ResponseEntity.notFound().build());
                } catch (IllegalArgumentException ignored) {}
            }
            return ResponseEntity.notFound().build();
        }

        return tenantRepository.findBySubdomain(targetSubdomain.toLowerCase().trim())
                .filter(Tenant::isOperational)
                .map(this::toConfigResponse)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<ApiResponse<TenantPublicConfigResponse>> toConfigResponse(Tenant t) {
        return ResponseEntity.ok(ApiResponse.ok(new TenantPublicConfigResponse(
                t.getId(),
                t.getName(),
                t.getLogoUrl(),
                t.getPrimaryColor(),
                t.getSecondaryColor(),
                t.getCity(),
                t.getAddress()
        )));
    }

    private String extractSubdomain(String serverName) {
        if (serverName == null || serverName.equals("localhost") || serverName.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            return null; 
        }
        
        if (serverName.endsWith(".localhost")) {
            return serverName.replace(".localhost", "");
        }

        // Support wildcard DNS for local IPs: e.g. limonar.192.168.1.35.sslip.io -> limonar
        if (serverName.matches(".+\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.(sslip|nip)\\.io$")) {
            return serverName.replaceAll("\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.(sslip|nip)\\.io$", "");
        }
        if (serverName.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.(sslip|nip)\\.io$")) {
            return null;
        }

        String[] parts = serverName.split("\\.");
        if (parts.length > 2) {
            return parts[0];
        }
        
        return null;
    }
}
