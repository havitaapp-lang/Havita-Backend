package com.havita.modules.marketplace.api;

import com.havita.common.api.ApiResponse;
import com.havita.common.api.PageResponse;
import com.havita.security.TenantContextHolder;
import com.havita.modules.marketplace.api.dto.*;
import com.havita.modules.marketplace.application.MarketplaceListingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/marketplace")
@RequiredArgsConstructor
public class MarketplaceListingController {

    private final MarketplaceListingService listingService;

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF', 'RESIDENT')")
    @GetMapping("/listings")
    public ApiResponse<PageResponse<MarketplaceListingResponse>> getListings(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String search,
            Pageable pageable) {
        return ApiResponse.ok(listingService.getPublishedListings(TenantContextHolder.getRequiredTenantId(), type, search, pageable));
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'SECURITY', 'STAFF', 'RESIDENT')")
    @GetMapping("/listings/{id}")
    public ApiResponse<MarketplaceListingResponse> getListing(@PathVariable UUID id) {
        return ApiResponse.ok(listingService.getListingById(TenantContextHolder.getRequiredTenantId(), id));
    }

    @PreAuthorize("hasRole('RESIDENT')")
    @GetMapping("/my-listings")
    public ApiResponse<PageResponse<MarketplaceListingResponse>> getMyListings(
            @AuthenticationPrincipal String userIdStr, Pageable pageable) {
        return ApiResponse.ok(listingService.getMyListings(TenantContextHolder.getRequiredTenantId(), UUID.fromString(userIdStr), pageable));
    }

    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping("/listings")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MarketplaceListingResponse> createListing(
            @AuthenticationPrincipal String userIdStr,
            @Valid @RequestBody CreateListingRequest request) {
        return ApiResponse.ok(listingService.createListing(TenantContextHolder.getRequiredTenantId(), UUID.fromString(userIdStr), request), "Publicación creada");
    }

    @PreAuthorize("hasRole('RESIDENT')")
    @PatchMapping("/listings/{id}")
    public ApiResponse<MarketplaceListingResponse> updateListing(
            @PathVariable UUID id,
            @AuthenticationPrincipal String userIdStr,
            @Valid @RequestBody UpdateListingRequest request) {
        return ApiResponse.ok(listingService.updateListing(TenantContextHolder.getRequiredTenantId(), UUID.fromString(userIdStr), id, request), "Publicación actualizada");
    }

    @PreAuthorize("hasRole('RESIDENT')")
    @DeleteMapping("/listings/{id}")
    public ApiResponse<Void> deleteListing(
            @PathVariable UUID id,
            @AuthenticationPrincipal String userIdStr) {
        listingService.deleteListing(TenantContextHolder.getRequiredTenantId(), UUID.fromString(userIdStr), id);
        return ApiResponse.ok(null, "Publicación ocultada");
    }

    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping("/listings/{id}/hide")
    public ApiResponse<Void> hideListing(
            @PathVariable UUID id,
            @AuthenticationPrincipal String userIdStr) {
        listingService.deleteListing(TenantContextHolder.getRequiredTenantId(), UUID.fromString(userIdStr), id);
        return ApiResponse.ok(null, "Publicación ocultada");
    }

    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping("/listings/{id}/unhide")
    public ApiResponse<MarketplaceListingResponse> unhideListing(
            @PathVariable UUID id,
            @AuthenticationPrincipal String userIdStr) {
        return ApiResponse.ok(listingService.unhideListing(TenantContextHolder.getRequiredTenantId(), UUID.fromString(userIdStr), id), "Publicación visible nuevamente");
    }

    @PreAuthorize("hasRole('RESIDENT')")
    @DeleteMapping("/listings/{id}/permanent")
    public ApiResponse<Void> purgeListing(
            @PathVariable UUID id,
            @AuthenticationPrincipal String userIdStr) {
        listingService.purgeListing(TenantContextHolder.getRequiredTenantId(), UUID.fromString(userIdStr), id);
        return ApiResponse.ok(null, "Publicación eliminada definitivamente");
    }

    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping("/listings/{id}/report")
    public ApiResponse<Void> reportListing(
            @PathVariable UUID id,
            @AuthenticationPrincipal String userIdStr,
            @Valid @RequestBody ReportListingRequest request) {
        listingService.reportListing(TenantContextHolder.getRequiredTenantId(), UUID.fromString(userIdStr), id, request);
        return ApiResponse.ok(null, "Publicación reportada");
    }
}
