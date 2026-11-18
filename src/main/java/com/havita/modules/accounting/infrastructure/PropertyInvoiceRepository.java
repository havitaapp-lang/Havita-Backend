package com.havita.modules.accounting.infrastructure;

import com.havita.modules.accounting.domain.PropertyInvoiceStatus;

import com.havita.modules.accounting.domain.PropertyInvoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PropertyInvoiceRepository extends JpaRepository<PropertyInvoice, UUID> {

    List<PropertyInvoice> findByTenantId(UUID tenantId);

    Page<PropertyInvoice> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<PropertyInvoice> findByIdAndTenantId(UUID id, UUID tenantId);

    List<PropertyInvoice> findByTenantIdAndPropertyId(UUID tenantId, UUID propertyId);

    Page<PropertyInvoice> findByTenantIdAndStatus(UUID tenantId, PropertyInvoiceStatus status, Pageable pageable);

    Page<PropertyInvoice> findByTenantIdAndPropertyIdIn(UUID tenantId, Collection<UUID> propertyIds, Pageable pageable);

    Page<PropertyInvoice> findByTenantIdAndStatusAndPropertyIdIn(UUID tenantId, PropertyInvoiceStatus status, Collection<UUID> propertyIds, Pageable pageable);

    Optional<PropertyInvoice> findByPropertyIdAndPeriod(UUID propertyId, String period);

    boolean existsByPropertyIdAndPeriod(UUID propertyId, String period);

    long countByTenantIdAndStatus(UUID tenantId, PropertyInvoiceStatus status);

    @Query("SELECT COALESCE(SUM(i.balanceDue), 0) FROM PropertyInvoice i WHERE i.tenantId = :tenantId AND i.status IN ('PENDING', 'PARTIALLY_PAID', 'OVERDUE')")
    BigDecimal sumTotalPendingReceivables(UUID tenantId);

    @Query("SELECT COALESCE(SUM(i.balanceDue), 0) FROM PropertyInvoice i WHERE i.tenantId = :tenantId AND i.property.id = :propertyId AND i.status IN ('PENDING', 'PARTIALLY_PAID', 'OVERDUE')")
    BigDecimal sumPendingBalanceByProperty(UUID tenantId, UUID propertyId);
}
