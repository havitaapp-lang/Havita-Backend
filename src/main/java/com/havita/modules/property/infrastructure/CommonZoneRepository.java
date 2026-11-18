package com.havita.modules.property.infrastructure;

import com.havita.modules.property.domain.CommonZone;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommonZoneRepository extends JpaRepository<CommonZone, UUID> {

    List<CommonZone> findByTenantId(UUID tenantId);

    Page<CommonZone> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<CommonZone> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndNameIgnoreCase(UUID tenantId, String name);

    long countByTenantId(UUID tenantId);
}
