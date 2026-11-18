package com.havita.modules.property.infrastructure;

import com.havita.modules.property.domain.Structure;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StructureRepository extends JpaRepository<Structure, UUID> {

    List<Structure> findByTenantId(UUID tenantId);

    Page<Structure> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<Structure> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndNameIgnoreCase(UUID tenantId, String name);

    Optional<Structure> findByTenantIdAndNameIgnoreCase(UUID tenantId, String name);

    long countByTenantId(UUID tenantId);
}
