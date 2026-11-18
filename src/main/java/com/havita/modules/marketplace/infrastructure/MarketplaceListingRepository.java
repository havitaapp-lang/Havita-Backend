package com.havita.modules.marketplace.infrastructure;

import com.havita.modules.marketplace.domain.ListingStatus;
import com.havita.modules.marketplace.domain.ListingType;
import com.havita.modules.marketplace.domain.MarketplaceListing;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MarketplaceListingRepository extends JpaRepository<MarketplaceListing, UUID> {

    @EntityGraph(attributePaths = {"createdByUser"})
    Page<MarketplaceListing> findByTenantId(UUID tenantId, Pageable pageable);

    @EntityGraph(attributePaths = {"createdByUser"})
    Page<MarketplaceListing> findByTenantIdAndStatus(UUID tenantId, ListingStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"createdByUser"})
    Page<MarketplaceListing> findByTenantIdAndStatusAndType(UUID tenantId, ListingStatus status, ListingType type, Pageable pageable);

    @EntityGraph(attributePaths = {"createdByUser"})
    @Query(value = "SELECT l FROM MarketplaceListing l WHERE l.tenantId = :tenantId AND l.status = :status " +
           "AND (LOWER(l.title) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(l.description) LIKE LOWER(CONCAT('%', :search, '%')))",
           countQuery = "SELECT count(l) FROM MarketplaceListing l WHERE l.tenantId = :tenantId AND l.status = :status " +
           "AND (LOWER(l.title) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(l.description) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<MarketplaceListing> findByTenantIdAndStatusAndSearch(
        @Param("tenantId") UUID tenantId,
        @Param("status") ListingStatus status,
        @Param("search") String search,
        Pageable pageable
    );

    @EntityGraph(attributePaths = {"createdByUser"})
    @Query(value = "SELECT l FROM MarketplaceListing l WHERE l.tenantId = :tenantId AND l.status = :status AND l.type = :type " +
           "AND (LOWER(l.title) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(l.description) LIKE LOWER(CONCAT('%', :search, '%')))",
           countQuery = "SELECT count(l) FROM MarketplaceListing l WHERE l.tenantId = :tenantId AND l.status = :status AND l.type = :type " +
           "AND (LOWER(l.title) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(l.description) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<MarketplaceListing> findByTenantIdAndStatusAndTypeAndSearch(
        @Param("tenantId") UUID tenantId,
        @Param("status") ListingStatus status,
        @Param("type") ListingType type,
        @Param("search") String search,
        Pageable pageable
    );

    @EntityGraph(attributePaths = {"createdByUser"})
    Page<MarketplaceListing> findByTenantIdAndCreatedByUser_Id(UUID tenantId, UUID userId, Pageable pageable);

    Optional<MarketplaceListing> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * SEC-9-9: publicaciones de la comunidad que referencian exactamente esta clave de imagen. image_keys es una lista
     * separada por comas; se compara el elemento completo con position() (no LIKE: '_' aparece en los nombres).
     */
    @Query(value = "select * from marketplace_listings l where l.tenant_id = :tenantId"
            + " and position(',' || :key || ',' in ',' || coalesce(l.image_keys, '') || ',') > 0", nativeQuery = true)
    java.util.List<MarketplaceListing> findByTenantIdReferencingImage(@org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
                                                                      @org.springframework.data.repository.query.Param("key") String key);
}
