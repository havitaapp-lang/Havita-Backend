package com.havita.modules.communication.api;

import com.havita.common.api.ApiResponse;
import com.havita.common.api.PageResponse;
import com.havita.modules.communication.api.dto.CommunicationResponse;
import com.havita.modules.communication.api.dto.CommunityDocumentResponse;
import com.havita.modules.communication.api.dto.CreateCommunicationRequest;
import com.havita.modules.communication.api.dto.CreateDocumentRequest;
import com.havita.modules.communication.application.CommunicationService;
import com.havita.modules.communication.application.CommunityDocumentService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/communications")
@RequiredArgsConstructor
@Tag(name = "Communications & Documents", description = "Gestión de circulares, avisos comunitarios y repositorio documental")
public class CommunicationController {

    private final CommunicationService communicationService;
    private final CommunityDocumentService communityDocumentService;

    // --- Comunicados (Communications) ---

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF', 'RESIDENT')")
    @Operation(summary = "Listar comunicados", description = "Lista comunicados con filtro opcional de búsqueda o audiencia")
    public ResponseEntity<ApiResponse<PageResponse<CommunicationResponse>>> findAll(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String audience,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<CommunicationResponse> page = communicationService.findAll(search, audience, pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF', 'RESIDENT')")
    @Operation(summary = "Obtener comunicado por ID")
    public ResponseEntity<ApiResponse<CommunicationResponse>> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(communicationService.findById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Crear nuevo comunicado")
    public ResponseEntity<ApiResponse<CommunicationResponse>> create(
            @Valid @RequestBody CreateCommunicationRequest request,
            Authentication authentication) {
        UUID userId = null;
        try {
            userId = UUID.fromString(authentication.getName());
        } catch (Exception ignored) {}

        CommunicationResponse response = communicationService.create(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Comunicado creado exitosamente"));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Publicar comunicado borrador")
    public ResponseEntity<ApiResponse<CommunicationResponse>> publish(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(communicationService.publish(id), "Comunicado publicado"));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Archivar comunicado")
    public ResponseEntity<ApiResponse<CommunicationResponse>> archive(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(communicationService.archive(id), "Comunicado archivado"));
    }

    @org.springframework.web.bind.annotation.PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Actualizar comunicado")
    public ResponseEntity<ApiResponse<CommunicationResponse>> updateCommunication(
            @PathVariable UUID id,
            @Valid @RequestBody com.havita.modules.communication.api.dto.UpdateCommunicationRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(communicationService.update(id, request), "Comunicado actualizado"));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Eliminar comunicado")
    public ResponseEntity<ApiResponse<Void>> deleteCommunication(@PathVariable UUID id) {
        communicationService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Comunicado eliminado"));
    }

    // --- Documentos Comunitarios (Community Documents) ---

    @GetMapping("/documents")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF', 'RESIDENT')")
    @Operation(summary = "Listar documentos comunitarios")
    public ResponseEntity<ApiResponse<PageResponse<CommunityDocumentResponse>>> findAllDocuments(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<CommunityDocumentResponse> page = communityDocumentService.findAll(search, category, pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @GetMapping("/documents/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF', 'RESIDENT')")
    @Operation(summary = "Obtener documento por ID")
    public ResponseEntity<ApiResponse<CommunityDocumentResponse>> findDocumentById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(communityDocumentService.findById(id)));
    }

    @PostMapping("/documents")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Registrar nuevo documento en el repositorio")
    public ResponseEntity<ApiResponse<CommunityDocumentResponse>> createDocument(
            @Valid @RequestBody CreateDocumentRequest request,
            Authentication authentication) {
        UUID userId = null;
        try {
            userId = UUID.fromString(authentication.getName());
        } catch (Exception ignored) {}

        CommunityDocumentResponse response = communityDocumentService.create(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Documento registrado exitosamente"));
    }

    @PostMapping("/documents/{id}/download")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF', 'RESIDENT')")
    @Operation(summary = "Registrar descarga de documento")
    public ResponseEntity<ApiResponse<CommunityDocumentResponse>> recordDownload(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(communityDocumentService.recordDownload(id), "Descarga registrada"));
    }

    @org.springframework.web.bind.annotation.PutMapping("/documents/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Actualizar documento")
    public ResponseEntity<ApiResponse<CommunityDocumentResponse>> updateDocument(
            @PathVariable UUID id,
            @Valid @RequestBody com.havita.modules.communication.api.dto.UpdateDocumentRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(communityDocumentService.update(id, request), "Documento actualizado"));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/documents/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Eliminar documento")
    public ResponseEntity<ApiResponse<Void>> deleteDocument(@PathVariable UUID id) {
        communityDocumentService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Documento eliminado"));
    }
}
