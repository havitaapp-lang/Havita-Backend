package com.havita.modules.pqrs.api;

import com.havita.modules.pqrs.domain.PqrsRequestStatus;
import com.havita.common.api.ApiResponse;
import com.havita.common.api.PageResponse;
import com.havita.modules.pqrs.api.dto.AddPqrsCommentRequest;
import com.havita.modules.pqrs.api.dto.CreatePqrsRequest;
import com.havita.modules.pqrs.api.dto.PqrsResponse;
import com.havita.modules.pqrs.application.PqrsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pqrs")
@RequiredArgsConstructor
@Tag(name = "PQRS & Maintenance", description = "Gestión de solicitudes, peticiones, quejas y mantenimientos")
public class PqrsController {

    private final PqrsService pqrsService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'STAFF', 'SECURITY')")
    @Operation(summary = "Listar todas las PQRS", description = "Lista paginada de solicitudes con búsqueda y filtro de estado")
    public ResponseEntity<ApiResponse<PageResponse<PqrsResponse>>> findAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) PqrsRequestStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<PqrsResponse> page = pqrsService.findAll(search, status, pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'STAFF', 'SECURITY', 'RESIDENT')")
    @Operation(summary = "Obtener detalle de PQRS por ID")
    public ResponseEntity<ApiResponse<PqrsResponse>> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(pqrsService.findById(id)));
    }

    @GetMapping("/by-property/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'STAFF', 'SECURITY') or (hasRole('RESIDENT') and @accessValidator.isActiveResidentOfProperty(#propertyId))")
    @Operation(summary = "Listar PQRS de una propiedad")
    public ResponseEntity<ApiResponse<List<PqrsResponse>>> findByProperty(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(ApiResponse.ok(pqrsService.findByProperty(propertyId)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'RESIDENT', 'STAFF')")
    @Operation(summary = "Radicar nueva PQRS o reporte de mantenimiento")
    public ResponseEntity<ApiResponse<PqrsResponse>> create(
            @Valid @RequestBody CreatePqrsRequest request,
            Authentication authentication) {
        UUID userId = null;
        try {
            userId = UUID.fromString(authentication.getName());
        } catch (Exception ignored) {}

        PqrsResponse response = pqrsService.create(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Solicitud radicada exitosamente"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'STAFF')")
    @Operation(summary = "Actualizar PQRS")
    public ResponseEntity<ApiResponse<PqrsResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody com.havita.modules.pqrs.api.dto.UpdatePqrsRequest request,
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.ok(pqrsService.update(id, request, UUID.fromString(userId)), "Solicitud actualizada exitosamente"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Eliminar PQRS")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        pqrsService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Solicitud eliminada exitosamente"));
    }

    @PostMapping("/{id}/assign/{technicianUserId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Asignar técnico a la PQRS")
    public ResponseEntity<ApiResponse<PqrsResponse>> assignTechnician(
            @PathVariable UUID id,
            @PathVariable UUID technicianUserId) {
        return ResponseEntity.ok(ApiResponse.ok(pqrsService.assignTechnician(id, technicianUserId), "Técnico asignado exitosamente"));
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'STAFF')")
    @Operation(summary = "Marcar PQRS como resuelta")
    public ResponseEntity<ApiResponse<PqrsResponse>> resolve(
            @PathVariable UUID id,
            @RequestParam(required = false) String responseNotes,
            // La app envia la nota en el cuerpo JSON; el parametro de URL se mantiene por compatibilidad.
            @Valid @RequestBody(required = false) com.havita.modules.pqrs.api.dto.ResolvePqrsRequest body,
            Authentication authentication) {
        UUID userId = null;
        try {
            userId = UUID.fromString(authentication.getName());
        } catch (Exception ignored) {}

        String notes = body != null && body.responseNotes() != null ? body.responseNotes() : responseNotes;
        return ResponseEntity.ok(ApiResponse.ok(pqrsService.resolve(id, notes, userId), "Solicitud marcada como resuelta"));
    }

    @PostMapping("/{id}/comments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'STAFF', 'RESIDENT')")
    @Operation(summary = "Agregar comentario o seguimiento a la PQRS")
    public ResponseEntity<ApiResponse<PqrsResponse>> addComment(
            @PathVariable UUID id,
            @Valid @RequestBody AddPqrsCommentRequest request,
            Authentication authentication) {
        UUID userId = null;
        try {
            userId = UUID.fromString(authentication.getName());
        } catch (Exception ignored) {}

        String role = authentication.getAuthorities().stream()
                .findFirst()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .orElse("USER");

        return ResponseEntity.ok(ApiResponse.ok(pqrsService.addComment(id, request, userId, role), "Comentario agregado exitosamente"));
    }
}
