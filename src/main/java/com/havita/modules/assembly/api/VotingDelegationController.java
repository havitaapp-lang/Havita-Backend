package com.havita.modules.assembly.api;

import com.havita.common.api.ApiResponse;
import com.havita.modules.assembly.api.dto.VotingDelegationResponse;
import com.havita.modules.assembly.application.VotingDelegationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/voting-delegation")
@RequiredArgsConstructor
@Tag(name = "Voting Delegation", description = "Gestión de delegación del derecho de voto para asambleas")
public class VotingDelegationController {

    private final VotingDelegationService votingDelegationService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN') or (hasRole('RESIDENT') and @accessValidator.isActiveResidentOfProperty(#propertyId))")
    @Operation(summary = "Obtener la delegación activa de una unidad")
    public ResponseEntity<ApiResponse<VotingDelegationResponse>> getActiveDelegation(@PathVariable UUID propertyId) {
        Optional<VotingDelegationResponse> opt = votingDelegationService.getActiveDelegation(propertyId);
        return opt.map(response -> ResponseEntity.ok(ApiResponse.ok(response)))
                .orElseGet(() -> ResponseEntity.ok(ApiResponse.ok(null, "No hay delegación activa")));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN') or (hasRole('RESIDENT') and @accessValidator.isActiveResidentOfProperty(#propertyId))")
    @Operation(summary = "Delegar el derecho de voto al arrendatario actual")
    public ResponseEntity<ApiResponse<VotingDelegationResponse>> delegateVote(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(ApiResponse.ok(
                votingDelegationService.delegateVote(propertyId),
                "Derecho de voto delegado exitosamente"
        ));
    }

    @DeleteMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN') or (hasRole('RESIDENT') and @accessValidator.isActiveResidentOfProperty(#propertyId))")
    @Operation(summary = "Revocar la delegación del derecho de voto")
    public ResponseEntity<ApiResponse<VotingDelegationResponse>> revokeVote(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(ApiResponse.ok(
                votingDelegationService.revokeVote(propertyId),
                "Derecho de voto revocado exitosamente"
        ));
    }
}
