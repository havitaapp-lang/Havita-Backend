package com.havita.modules.organization.infrastructure;

import com.havita.modules.organization.domain.OrganizationStatus;

import com.havita.modules.organization.domain.Organization;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    boolean existsByNit(String nit);

    Optional<Organization> findByNit(String nit);

    @Query("SELECT o FROM Organization o WHERE " +
           "LOWER(o.name) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(o.nit) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Organization> searchByNameOrNit(String search, Pageable pageable);

    Page<Organization> findByStatus(OrganizationStatus status, Pageable pageable);
}
