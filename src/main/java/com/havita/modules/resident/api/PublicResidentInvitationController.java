package com.havita.modules.resident.api;

import com.havita.common.api.ApiResponse;
import com.havita.modules.resident.api.dto.PublicInvitationResponse;
import com.havita.modules.resident.api.dto.RegisterFromInvitationRequest;
import com.havita.modules.resident.application.ResidentInvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/resident-invitations")
@RequiredArgsConstructor
@Tag(name = "Resident Invitations (Public)", description = "Endpoints publicos para el registro de residentes por invitacion")
public class PublicResidentInvitationController {

    private final ResidentInvitationService invitationService;

    @GetMapping("/{token}")
    @Operation(summary = "Validar token de invitacion y obtener informacion publica de la propiedad")
    public ResponseEntity<ApiResponse<PublicInvitationResponse>> validateToken(@PathVariable String token) {
        return ResponseEntity.ok(ApiResponse.ok(invitationService.validatePublicToken(token)));
    }

    @PostMapping("/{token}/register")
    @Operation(summary = "Registrarse como residente usando un token de invitacion")
    public ResponseEntity<ApiResponse<Void>> registerResident(
            @PathVariable String token,
            @Valid @RequestBody RegisterFromInvitationRequest request,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        invitationService.registerResident(token, request, httpRequest);
        return ResponseEntity.ok(ApiResponse.ok(null, "Registro completado exitosamente"));
    }
}
