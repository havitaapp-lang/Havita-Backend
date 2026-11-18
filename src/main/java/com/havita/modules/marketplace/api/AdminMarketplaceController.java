package com.havita.modules.marketplace.api;

import com.havita.common.api.ApiResponse;
import com.havita.common.api.PageResponse;
import com.havita.security.TenantContextHolder;
import com.havita.modules.marketplace.api.dto.*;
import com.havita.modules.marketplace.application.MarketplaceConfigService;
import com.havita.modules.marketplace.application.MarketplaceListingService;
import com.havita.modules.marketplace.domain.ListingStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/marketplace")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
@RequiredArgsConstructor
public class AdminMarketplaceController {

    private final MarketplaceListingService listingService;
    private final MarketplaceConfigService configService;

    @GetMapping("/listings")
    public ApiResponse<PageResponse<MarketplaceListingResponse>> getAllListings(
            @RequestParam(required = false) ListingStatus status, Pageable pageable) {
        return ApiResponse.ok(listingService.getAllListings(TenantContextHolder.getRequiredTenantId(), status, pageable));
    }

    @GetMapping("/listings/pending")
    public ApiResponse<PageResponse<MarketplaceListingResponse>> getPendingListings(Pageable pageable) {
        return ApiResponse.ok(listingService.getPendingListings(TenantContextHolder.getRequiredTenantId(), pageable));
    }

    @PostMapping("/listings/{id}/approve")
    public ApiResponse<MarketplaceListingResponse> approveListing(
            @PathVariable UUID id, @AuthenticationPrincipal String adminIdStr) {
        return ApiResponse.ok(listingService.approveListing(TenantContextHolder.getRequiredTenantId(), UUID.fromString(adminIdStr), id), "Publicación aprobada");
    }

    @PostMapping("/listings/{id}/reject")
    public ApiResponse<MarketplaceListingResponse> rejectListing(
            @PathVariable UUID id, @AuthenticationPrincipal String adminIdStr,
            @Valid @RequestBody RejectListingRequest request) {
        return ApiResponse.ok(listingService.rejectListing(TenantContextHolder.getRequiredTenantId(), UUID.fromString(adminIdStr), id, request), "Publicación rechazada");
    }

    @PostMapping("/listings/{id}/hide")
    public ApiResponse<MarketplaceListingResponse> hideListing(
            @PathVariable UUID id,
            @AuthenticationPrincipal String adminIdStr,
            @RequestBody(required = false) HideListingRequest request) {
        return ApiResponse.ok(listingService.hideListing(TenantContextHolder.getRequiredTenantId(), UUID.fromString(adminIdStr), id, request), "Publicación ocultada");
    }

    @PostMapping("/listings/{id}/unhide")
    public ApiResponse<MarketplaceListingResponse> unhideListing(
            @PathVariable UUID id,
            @AuthenticationPrincipal String adminIdStr) {
        return ApiResponse.ok(listingService.adminUnhideListing(TenantContextHolder.getRequiredTenantId(), UUID.fromString(adminIdStr), id), "Publicación visible nuevamente");
    }

    @GetMapping("/reports")
    public ApiResponse<PageResponse<MarketplaceReportResponse>> getReports(Pageable pageable) {
        return ApiResponse.ok(listingService.getReports(TenantContextHolder.getRequiredTenantId(), pageable));
    }

    @PostMapping("/reports/{id}/resolve")
    public ApiResponse<Void> resolveReport(
            @PathVariable UUID id, @AuthenticationPrincipal String adminIdStr,
            @Valid @RequestBody ResolveReportRequest request) {
        listingService.resolveReport(TenantContextHolder.getRequiredTenantId(), UUID.fromString(adminIdStr), id, request);
        return ApiResponse.ok(null, "Reporte resuelto");
    }

    @GetMapping("/config")
    public ApiResponse<MarketplaceConfigResponse> getConfig() {
        return ApiResponse.ok(configService.getConfig(TenantContextHolder.getRequiredTenantId()));
    }

    @PutMapping("/config")
    public ApiResponse<MarketplaceConfigResponse> updateConfig(
            @Valid @RequestBody UpdateMarketplaceConfigRequest request) {
        return ApiResponse.ok(configService.updateConfig(TenantContextHolder.getRequiredTenantId(), request), "Configuración actualizada");
    }

    @PostMapping("/config/toggle")
    public ApiResponse<Void> toggleMarketplace(@RequestBody ToggleMarketplaceRequest request) {
        configService.toggleFeature(TenantContextHolder.getRequiredTenantId(), request.enabled());
        return ApiResponse.ok(null, request.enabled() ? "Marketplace habilitado" : "Marketplace deshabilitado");
    }
}
