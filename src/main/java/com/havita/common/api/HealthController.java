package com.havita.common.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "Health", description = "Verificación de operatividad del backend y dependencias")
public class HealthController {

    @GetMapping
    @Operation(summary = "Verifica el estado del servicio Core")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkHealth() {
        Map<String, Object> healthInfo = Map.of(
                "status", "UP",
                "service", "havita-backend",
                "version", "1.0.0-SNAPSHOT",
                "timestamp", OffsetDateTime.now(),
                "environment", "modular-monolith"
        );
        return ResponseEntity.ok(ApiResponse.ok(healthInfo, "Havita Backend Core está operativo"));
    }
}
