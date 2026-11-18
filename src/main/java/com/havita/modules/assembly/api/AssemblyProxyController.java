package com.havita.modules.assembly.api;

import com.havita.common.api.ApiResponse;
import com.havita.modules.assembly.api.dto.AssemblyProxyResponse;
import com.havita.modules.assembly.api.dto.CreateAssemblyProxyRequest;
import com.havita.modules.assembly.api.dto.ReviewAssemblyProxyRequest;
import com.havita.modules.assembly.application.AssemblyProxyService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fase 2 / P1 — poderes de asamblea (PREPARADO, NO ACTIVADO). El controlador solo existe con
 * {@code havita.assembly-proxies.enabled=true}; por defecto estas rutas no estan registradas (404). Los poderes no se
 * usan todavia en asistencia, quorum ni votacion. Las comprobaciones reales (membresia vigente, relacion de propietario,
 * comunidad, unidad, asamblea y estado) se hacen en el servicio contra PostgreSQL; {@code @PreAuthorize} es el primer
 * filtro por rol.
 */
@RestController
@RequestMapping("/api/v1/assemblies/{assemblyId}/proxies")
@ConditionalOnProperty(name = "havita.assembly-proxies.enabled", havingValue = "true")
public class AssemblyProxyController {

    private final AssemblyProxyService service;

    public AssemblyProxyController(AssemblyProxyService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Solicitar un poder para una asamblea (propietario activo de la unidad)")
    public ResponseEntity<ApiResponse<AssemblyProxyResponse>> request(@PathVariable UUID assemblyId,
            @Valid @RequestPart("data") CreateAssemblyProxyRequest data, @RequestPart("document") MultipartFile document) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.requestByOwner(assemblyId, data, document.getBytes())));
    }

    @PostMapping(path = "/admin", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Registrar un poder en nombre de un propietario (administracion)")
    public ResponseEntity<ApiResponse<AssemblyProxyResponse>> register(@PathVariable UUID assemblyId,
            @Valid @RequestPart("data") CreateAssemblyProxyRequest data, @RequestPart("document") MultipartFile document) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.registerByAdmin(assemblyId, data, document.getBytes())));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Poderes de una asamblea (administracion)")
    public ResponseEntity<ApiResponse<List<AssemblyProxyResponse>>> list(@PathVariable UUID assemblyId) {
        return ResponseEntity.ok(ApiResponse.ok(service.listForAdmin(assemblyId)));
    }

    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Poderes de la asamblea que otorgue (propietario activo) o en los que soy el apoderado con cuenta")
    public ResponseEntity<ApiResponse<List<com.havita.modules.assembly.api.dto.MyAssemblyProxyResponse>>> mine(@PathVariable UUID assemblyId) {
        return ResponseEntity.ok(ApiResponse.ok(service.listMine(assemblyId)));
    }

    @GetMapping("/{proxyId}/events")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Historial de eventos de un poder (administracion)")
    public ResponseEntity<ApiResponse<List<com.havita.modules.assembly.api.dto.AssemblyProxyEventResponse>>> events(
            @PathVariable UUID assemblyId, @PathVariable UUID proxyId) {
        return ResponseEntity.ok(ApiResponse.ok(service.events(assemblyId, proxyId)));
    }

    @PostMapping("/{proxyId}/approve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Aprobar un poder (revision documental manual)")
    public ResponseEntity<ApiResponse<AssemblyProxyResponse>> approve(@PathVariable UUID assemblyId, @PathVariable UUID proxyId) {
        return ResponseEntity.ok(ApiResponse.ok(service.approve(assemblyId, proxyId)));
    }

    @PostMapping("/{proxyId}/reject")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Rechazar un poder (motivo obligatorio)")
    public ResponseEntity<ApiResponse<AssemblyProxyResponse>> reject(@PathVariable UUID assemblyId, @PathVariable UUID proxyId,
            @Valid @RequestBody ReviewAssemblyProxyRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(service.reject(assemblyId, proxyId, body.reason())));
    }

    @PostMapping("/{proxyId}/revoke")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'RESIDENT')")
    @Operation(summary = "Revocar un poder no utilizado (otorgante o administracion)")
    public ResponseEntity<ApiResponse<AssemblyProxyResponse>> revoke(@PathVariable UUID assemblyId, @PathVariable UUID proxyId,
            @Valid @RequestBody(required = false) ReviewAssemblyProxyRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(service.revoke(assemblyId, proxyId, body != null ? body.reason() : null)));
    }

    @PostMapping(path = "/{proxyId}/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'RESIDENT')")
    @Operation(summary = "Reemplazar el documento de un poder en revision")
    public ResponseEntity<ApiResponse<AssemblyProxyResponse>> replaceDocument(@PathVariable UUID assemblyId, @PathVariable UUID proxyId,
            @RequestPart("document") MultipartFile document) throws IOException {
        return ResponseEntity.ok(ApiResponse.ok(service.replaceDocument(assemblyId, proxyId, document.getBytes())));
    }

    @GetMapping("/{proxyId}/document")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'RESIDENT')")
    @Operation(summary = "Descargar el documento del poder (administracion u otorgante)")
    public ResponseEntity<byte[]> document(@PathVariable UUID assemblyId, @PathVariable UUID proxyId) {
        AssemblyProxyService.DocumentContent doc = service.readDocument(assemblyId, proxyId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(doc.contentType()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"poder\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(doc.bytes());
    }

    // ── Fase 2 / P2: asistencia mediante poder ────────────────────────────────

    @PostMapping("/{proxyId}/attendance/self")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "El apoderado con cuenta registra su asistencia con un poder aprobado (asamblea en curso)")
    public ResponseEntity<ApiResponse<com.havita.modules.assembly.api.dto.AttendeeResponse>> attendAsProxy(
            @PathVariable UUID assemblyId, @PathVariable UUID proxyId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.registerAttendanceAsProxy(assemblyId, proxyId)));
    }

    @PostMapping("/{proxyId}/attendance")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "La administracion registra la asistencia presencial del apoderado tras verificar su identidad")
    public ResponseEntity<ApiResponse<com.havita.modules.assembly.api.dto.AttendeeResponse>> attendByAdmin(
            @PathVariable UUID assemblyId, @PathVariable UUID proxyId,
            @Valid @RequestBody com.havita.modules.assembly.api.dto.VerifyProxyIdentityRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.registerAttendanceByAdmin(assemblyId, proxyId,
                new AssemblyProxyService.PresentedIdentity(body.documentType(), body.documentNumber()))));
    }

    @PostMapping("/expire")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Vencer los poderes no utilizados de una asamblea finalizada")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> expire(@PathVariable UUID assemblyId) {
        return ResponseEntity.ok(ApiResponse.ok(Map.of("expired", service.expireForEndedAssembly(assemblyId))));
    }
}
