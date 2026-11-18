package com.havita.modules.organization.infrastructure;

import com.havita.modules.organization.domain.Tenant;
import com.havita.modules.organization.domain.TenantStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findBySlug(String slug);

    Optional<Tenant> findBySubdomain(String subdomain);

    boolean existsBySlug(String slug);

    boolean existsBySubdomain(String subdomain);

    boolean existsByNit(String nit);

    List<Tenant> findByOrganizationId(UUID organizationId);

    Page<Tenant> findByStatus(TenantStatus status, Pageable pageable);

    @Query("SELECT t FROM Tenant t WHERE " +
           "LOWER(t.name) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(t.slug) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(t.subdomain) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Tenant> searchByNameOrSlugOrSubdomain(String search, Pageable pageable);

    long countByOrganizationId(UUID organizationId);

    long countByStatus(TenantStatus status);
}
