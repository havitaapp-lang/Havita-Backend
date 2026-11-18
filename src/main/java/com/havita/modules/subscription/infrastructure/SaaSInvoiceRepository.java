package com.havita.modules.subscription.infrastructure;

import com.havita.modules.subscription.domain.SaaSInvoiceStatus;

import com.havita.modules.subscription.domain.SaaSInvoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SaaSInvoiceRepository extends JpaRepository<SaaSInvoice, UUID> {

    List<SaaSInvoice> findByTenantId(UUID tenantId);

    Page<SaaSInvoice> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<SaaSInvoice> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<SaaSInvoice> findByInvoiceNumber(String invoiceNumber);

    Page<SaaSInvoice> findByStatus(SaaSInvoiceStatus status, Pageable pageable);

    long countByTenantIdAndStatus(UUID tenantId, SaaSInvoiceStatus status);
}
