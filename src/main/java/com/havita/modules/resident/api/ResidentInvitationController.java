package com.havita.modules.resident.api;

import com.havita.common.api.ApiResponse;
import com.havita.modules.resident.api.dto.ResidentInvitationResponse;
import com.havita.modules.resident.application.ResidentInvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.havita.modules.resident.api.dto.GenerateInvitationRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Resident Invitations (Admin)", description = "Administracion de invitaciones de registro para residentes")
public class ResidentInvitationController {

    private final ResidentInvitationService invitationService;


    @PostMapping("/properties/{propertyId}/resident-invitations")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Generar una nueva invitacion para una propiedad")
    public ResponseEntity<ApiResponse<ResidentInvitationResponse>> generateInvitation(
            @PathVariable UUID propertyId,
            @RequestBody(required = false) GenerateInvitationRequest request) {
        
        List<UUID> additionalIds = (request != null && request.additionalPropertyIds() != null) 
                ? request.additionalPropertyIds() : List.of();
                
        return ResponseEntity.ok(ApiResponse.ok(invitationService.generateInvitation(propertyId, additionalIds), "Invitacion generada con exito"));
    }

    @GetMapping("/properties/{propertyId}/resident-invitations")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'STAFF')")
    @Operation(summary = "Listar invitaciones de una propiedad")
    public ResponseEntity<ApiResponse<List<ResidentInvitationResponse>>> listInvitations(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(ApiResponse.ok(invitationService.listByProperty(propertyId)));
    }

    @PostMapping("/resident-invitations/{id}/revoke")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Revocar una invitacion")
    public ResponseEntity<ApiResponse<Void>> revokeInvitation(@PathVariable UUID id) {
        invitationService.revokeInvitation(id);
        return ResponseEntity.ok(ApiResponse.ok(null, "Invitacion revocada con exito"));
    }

    @PostMapping("/resident-invitations/{id}/regenerate")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Regenerar una invitacion (revoca la actual y crea una nueva)")
    public ResponseEntity<ApiResponse<ResidentInvitationResponse>> regenerateInvitation(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(invitationService.regenerateInvitation(id), "Nuevo enlace de invitacion generado"));
    }
}
