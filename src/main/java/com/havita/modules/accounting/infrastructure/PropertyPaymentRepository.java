package com.havita.modules.accounting.infrastructure;

import com.havita.modules.accounting.domain.PropertyPayment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface PropertyPaymentRepository extends JpaRepository<PropertyPayment, UUID> {

    List<PropertyPayment> findByTenantId(UUID tenantId);

    Page<PropertyPayment> findByTenantId(UUID tenantId, Pageable pageable);

    List<PropertyPayment> findByInvoiceId(UUID invoiceId);

    List<PropertyPayment> findByPropertyId(UUID propertyId);

    @Query("SELECT COALESCE(SUM(p.amountPaid), 0) FROM PropertyPayment p WHERE p.tenantId = :tenantId AND p.status = 'CONFIRMED'")
    BigDecimal sumTotalCollected(UUID tenantId);
}
