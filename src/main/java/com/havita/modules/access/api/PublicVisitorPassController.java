package com.havita.modules.access.api;

import com.havita.common.api.ApiResponse;
import com.havita.modules.access.api.dto.VisitorResponse;
import com.havita.modules.access.domain.Visitor;
import com.havita.modules.access.infrastructure.VisitorRepository;
import com.havita.security.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public/visitor-passes")
@RequiredArgsConstructor
@Tag(name = "Public Visitor Passes", description = "Endpoints públicos para consulta de pases de acceso y códigos QR")
public class PublicVisitorPassController {

    private final VisitorRepository visitorRepository;

    @GetMapping("/{token}")
    @Transactional(readOnly = true)
    @Operation(summary = "Obtener pase de visita público por token QR, ID o Group ID")
    public ResponseEntity<ApiResponse<List<VisitorResponse>>> getPassByToken(@PathVariable String token) {
        UUID tenantId = TenantContextHolder.getTenantId();
        List<Visitor> matchedVisitors = new ArrayList<>();

        if (tenantId != null) {
            Optional<Visitor> byQr = visitorRepository.findByTenantIdAndQrCode(tenantId, token);
            if (byQr.isPresent()) {
                Visitor v = byQr.get();
                if (v.getGroupId() != null && !v.getGroupId().isBlank()) {
                    matchedVisitors = visitorRepository.findByTenantIdAndGroupId(tenantId, v.getGroupId());
                } else {
                    matchedVisitors = List.of(v);
                }
            } else {
                List<Visitor> byGroup = visitorRepository.findByTenantIdAndGroupId(tenantId, token);
                if (!byGroup.isEmpty()) {
                    matchedVisitors = byGroup;
                } else {
                    try {
                        UUID id = UUID.fromString(token);
                        visitorRepository.findByIdAndTenantId(id, tenantId).ifPresent(matchedVisitors::add);
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        } else {
            Optional<Visitor> byQr = visitorRepository.findByQrCode(token);
            if (byQr.isPresent()) {
                Visitor v = byQr.get();
                if (v.getGroupId() != null && !v.getGroupId().isBlank()) {
                    matchedVisitors = visitorRepository.findByGroupId(v.getGroupId());
                } else {
                    matchedVisitors = List.of(v);
                }
            } else {
                List<Visitor> byGroup = visitorRepository.findByGroupId(token);
                if (!byGroup.isEmpty()) {
                    matchedVisitors = byGroup;
                } else {
                    try {
                        UUID id = UUID.fromString(token);
                        visitorRepository.findById(id).ifPresent(matchedVisitors::add);
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }

        if (matchedVisitors.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<VisitorResponse> responses = matchedVisitors.stream().map(v -> new VisitorResponse(
                v.getId(),
                v.getTenantId(),
                v.getProperty().getId(),
                v.getProperty().getNumber(),
                (v.getProperty().getPath() != null && !v.getProperty().getPath().isBlank() ? v.getProperty().getPath() : v.getProperty().getName()),
                v.getResident() != null ? v.getResident().getId() : null,
                v.getCreatedByUserId(),
                v.getResident() != null ? v.getResident().getName() : null,
                v.getName(),
                v.getDocumentId(),
                v.getStatus(),
                v.getExpectedAt(),
                v.getEnteredAt(),
                v.getExitedAt(),
                v.getVehiclePlate(),
                v.getObservations(),
                v.getQrCode(),
                v.getGroupId(),
                v.getGroupName(),
                v.getVisitPurpose(),
                v.getCreatedAt(),
                v.getUpdatedAt()
        )).toList();

        return ResponseEntity.ok(ApiResponse.ok(responses));
    }
}

