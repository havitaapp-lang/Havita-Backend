package com.havita.modules.subscription.infrastructure;

import com.havita.modules.subscription.domain.SaaSPlanStatus;

import com.havita.modules.subscription.domain.SaaSPlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SaaSPlanRepository extends JpaRepository<SaaSPlan, UUID> {

    List<SaaSPlan> findByStatus(SaaSPlanStatus status);

    Page<SaaSPlan> findByStatus(SaaSPlanStatus status, Pageable pageable);

    Optional<SaaSPlan> findByTierIgnoreCase(String tier);

    boolean existsByTierIgnoreCase(String tier);
}
