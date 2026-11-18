package com.havita.modules.marketplace.infrastructure;

import com.havita.modules.marketplace.domain.MarketplaceReport;
import com.havita.modules.marketplace.domain.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MarketplaceReportRepository extends JpaRepository<MarketplaceReport, UUID> {

    @EntityGraph(attributePaths = {"listing", "listing.createdByUser"})
    Page<MarketplaceReport> findByTenantId(UUID tenantId, Pageable pageable);

    @EntityGraph(attributePaths = {"listing", "listing.createdByUser"})
    Page<MarketplaceReport> findByTenantIdAndStatus(UUID tenantId, ReportStatus status, Pageable pageable);

    Optional<MarketplaceReport> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByListing_IdAndReportedByUserId(UUID listingId, UUID reportedByUserId);
}
