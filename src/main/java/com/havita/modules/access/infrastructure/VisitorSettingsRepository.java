package com.havita.modules.access.infrastructure;

import com.havita.modules.access.domain.VisitorSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface VisitorSettingsRepository extends JpaRepository<VisitorSettings, UUID> {

    @EntityGraph(attributePaths = {"daySchedules"})
    Optional<VisitorSettings> findByTenantId(UUID tenantId);
}
