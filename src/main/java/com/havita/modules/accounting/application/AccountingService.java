package com.havita.modules.accounting.application;
import com.havita.modules.accounting.domain.PropertyPaymentStatus;

import com.havita.modules.accounting.domain.PropertyInvoiceStatus;
import com.havita.common.exception.BusinessRuleConflictException;
import com.havita.common.exception.ResourceNotFoundException;
import com.havita.modules.accounting.api.dto.BillingConfigResponse;
import com.havita.modules.accounting.api.dto.CreatePropertyInvoiceRequest;
import com.havita.modules.accounting.api.dto.PropertyInvoiceResponse;
import com.havita.modules.accounting.api.dto.PropertyPaymentResponse;
import com.havita.modules.accounting.api.dto.RecordPropertyPaymentRequest;
import com.havita.modules.accounting.api.dto.UpdateBillingConfigRequest;
import com.havita.modules.accounting.domain.BillingConfig;
import com.havita.modules.accounting.domain.PropertyInvoice;
import com.havita.modules.accounting.domain.PropertyPayment;
import com.havita.modules.accounting.infrastructure.BillingConfigRepository;
import com.havita.modules.accounting.infrastructure.PropertyInvoiceRepository;
import com.havita.modules.accounting.infrastructure.PropertyPaymentRepository;
import com.havita.modules.property.domain.Property;
import com.havita.modules.property.infrastructure.PropertyRepository;
import com.havita.modules.resident.domain.Resident;
import com.havita.modules.resident.infrastructure.ResidentRepository;
import com.havita.security.AccessValidator;
import com.havita.security.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountingService {

    private final BillingConfigRepository billingConfigRepository;
    private final PropertyInvoiceRepository propertyInvoiceRepository;
    private final PropertyPaymentRepository propertyPaymentRepository;
    private final PropertyRepository propertyRepository;
    private final ResidentRepository residentRepository;
    private final AccessValidator accessValidator;

    @Transactional(readOnly = true)
    public BillingConfigResponse getBillingConfig() {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        BillingConfig config = billingConfigRepository.findByTenantId(tenantId)
                .orElseGet(() -> BillingConfig.builder().build());
        return toBillingConfigResponse(config);
    }

    @Transactional
    public BillingConfigResponse updateBillingConfig(UpdateBillingConfigRequest request) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockCommunityAdministrator();
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        BillingConfig config = billingConfigRepository.findByTenantId(tenantId)
                .orElseGet(() -> BillingConfig.builder().build());

        if (request.calculationMethod() != null) config.setCalculationMethod(request.calculationMethod());
        if (request.baseBudgetAmount() != null) config.setBaseBudgetAmount(request.baseBudgetAmount());
        if (request.fixedFeeAmount() != null) config.setFixedFeeAmount(request.fixedFeeAmount());
        if (request.lateFeePercentage() != null) config.setLateFeePercentage(request.lateFeePercentage());
        if (request.discountEarlyPaymentPercentage() != null) config.setDiscountEarlyPaymentPercentage(request.discountEarlyPaymentPercentage());
        if (request.dueDayOfMonth() != null) config.setDueDayOfMonth(request.dueDayOfMonth());
        if (request.discountDayOfMonth() != null) config.setDiscountDayOfMonth(request.discountDayOfMonth());

        config = billingConfigRepository.save(config);
        log.info("Billing config updated for tenant {}", tenantId);
        return toBillingConfigResponse(config);
    }

    @Transactional
    public List<PropertyInvoiceResponse> generateMonthlyInvoices(String period, LocalDate dueDate) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockAuthorization(java.util.Set.of(com.havita.modules.identity.domain.RoleType.COMMUNITY_ADMIN, com.havita.modules.identity.domain.RoleType.ACCOUNTANT, com.havita.modules.identity.domain.RoleType.SUPER_ADMIN), java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN));
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        BillingConfig config = billingConfigRepository.findByTenantId(tenantId)
                .orElseGet(() -> BillingConfig.builder().build());

        List<Property> properties = propertyRepository.findByTenantId(tenantId);
        List<PropertyInvoiceResponse> responses = new ArrayList<>();

        for (Property property : properties) {
            if (propertyInvoiceRepository.existsByPropertyIdAndPeriod(property.getId(), period)) {
                log.warn("Invoice already exists for property {} and period {}", property.getId(), period);
                continue;
            }

            BigDecimal baseAmount = BigDecimal.ZERO;
            if ("FIXED_FEE".equalsIgnoreCase(config.getCalculationMethod())) {
                baseAmount = config.getFixedFeeAmount();
            } else {
                BigDecimal coefficient = property.getCoefficient() != null ? property.getCoefficient() : BigDecimal.ZERO;
                baseAmount = config.getBaseBudgetAmount().multiply(coefficient).setScale(2, RoundingMode.HALF_UP);
            }

            BigDecimal previousBalance = propertyInvoiceRepository.sumPendingBalanceByProperty(tenantId, property.getId());
            BigDecimal totalAmount = baseAmount.add(previousBalance != null ? previousBalance : BigDecimal.ZERO);

            String invoiceNumber = "INV-" + period.replaceAll("\\s+", "-").toUpperCase() + "-" + property.getNumber();

            PropertyInvoice invoice = PropertyInvoice.builder()
                    .property(property)
                    .invoiceNumber(invoiceNumber)
                    .period(period)
                    .concept("Cuota de Administración - " + period)
                    .baseAmount(baseAmount)
                    .previousBalance(previousBalance != null ? previousBalance : BigDecimal.ZERO)
                    .totalAmount(totalAmount)
                    .balanceDue(totalAmount)
                    .issuedDate(LocalDate.now())
                    .dueDate(dueDate != null ? dueDate : LocalDate.now().plusDays(15))
                    .status(PropertyInvoiceStatus.PENDING)
                    .build();
            invoice.setTenantId(tenantId);

            invoice = propertyInvoiceRepository.save(invoice);
            responses.add(toInvoiceResponse(invoice));
        }

        log.info("Generated {} invoices for period {} in tenant {}", responses.size(), period, tenantId);
        return responses;
    }

    @Transactional
    public PropertyInvoiceResponse createInvoice(CreatePropertyInvoiceRequest request) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockAuthorization(java.util.Set.of(com.havita.modules.identity.domain.RoleType.COMMUNITY_ADMIN, com.havita.modules.identity.domain.RoleType.ACCOUNTANT, com.havita.modules.identity.domain.RoleType.SUPER_ADMIN), java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN));
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        Property property = propertyRepository.findByIdAndTenantId(request.propertyId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", request.propertyId()));

        if (propertyInvoiceRepository.existsByPropertyIdAndPeriod(property.getId(), request.period())) {
            throw new BusinessRuleConflictException("INVOICE_ALREADY_EXISTS",
                    "Ya existe una cuenta de cobro para el inmueble " + property.getNumber() + " en el período " + request.period());
        }

        Resident resident = null;
        if (request.residentId() != null) {
            resident = residentRepository.findByIdAndTenantId(request.residentId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Resident", request.residentId()));
        }

        BigDecimal baseAmount = request.baseAmount();
        BigDecimal lateFee = request.lateFeeAmount() != null ? request.lateFeeAmount() : BigDecimal.ZERO;
        BigDecimal discount = request.discountAmount() != null ? request.discountAmount() : BigDecimal.ZERO;
        BigDecimal previousBalance = request.previousBalance() != null ? request.previousBalance() : BigDecimal.ZERO;

        BigDecimal total = baseAmount.add(lateFee).add(previousBalance).subtract(discount);
        String invoiceNumber = "INV-" + request.period().replaceAll("\\s+", "-").toUpperCase() + "-" + property.getNumber();

        PropertyInvoice invoice = PropertyInvoice.builder()
                .property(property)
                .resident(resident)
                .invoiceNumber(invoiceNumber)
                .period(request.period())
                .concept(request.concept() != null ? request.concept() : "Cuota Ordinaria de Administración")
                .baseAmount(baseAmount)
                .lateFeeAmount(lateFee)
                .discountAmount(discount)
                .previousBalance(previousBalance)
                .totalAmount(total)
                .balanceDue(total)
                .issuedDate(LocalDate.now())
                .dueDate(request.dueDate())
                .discountUntilDate(request.discountUntilDate())
                .status(PropertyInvoiceStatus.PENDING)
                .build();
        invoice.setTenantId(tenantId);

        invoice = propertyInvoiceRepository.save(invoice);
        log.info("Invoice created: {} for property {}", invoice.getInvoiceNumber(), property.getNumber());
        return toInvoiceResponse(invoice);
    }

    @Transactional(readOnly = true)
    public Page<PropertyInvoiceResponse> findInvoices(PropertyInvoiceStatus status, Pageable pageable) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        Page<PropertyInvoice> page;
        if (accessValidator.isResidentCaller()) {
            // Un residente solo ve las cuentas de cobro de sus unidades activas.
            Set<UUID> ownProperties = accessValidator.activePropertyIds();
            if (ownProperties.isEmpty()) {
                return Page.empty(pageable);
            }
            page = status != null
                    ? propertyInvoiceRepository.findByTenantIdAndStatusAndPropertyIdIn(tenantId, status, ownProperties, pageable)
                    : propertyInvoiceRepository.findByTenantIdAndPropertyIdIn(tenantId, ownProperties, pageable);
        } else if (status != null) {
            page = propertyInvoiceRepository.findByTenantIdAndStatus(tenantId, status, pageable);
        } else {
            page = propertyInvoiceRepository.findByTenantId(tenantId, pageable);
        }
        return page.map(this::toInvoiceResponse);
    }

    @Transactional(readOnly = true)
    public List<PropertyInvoiceResponse> findInvoicesByProperty(UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        return propertyInvoiceRepository.findByTenantIdAndPropertyId(tenantId, propertyId)
                .stream()
                .map(this::toInvoiceResponse)
                .toList();
    }

    @Transactional
    public PropertyPaymentResponse recordPayment(RecordPropertyPaymentRequest request) {
        // SEC-9 (P1): permiso comprobado de nuevo dentro de la transaccion (FOR SHARE), antes de tocar nada.
        accessValidator.lockAuthorization(java.util.Set.of(com.havita.modules.identity.domain.RoleType.COMMUNITY_ADMIN, com.havita.modules.identity.domain.RoleType.ACCOUNTANT, com.havita.modules.identity.domain.RoleType.SUPER_ADMIN), java.util.Set.of(com.havita.modules.identity.domain.RoleType.SUPER_ADMIN));
        UUID tenantId = TenantContextHolder.getRequiredTenantId();

        PropertyInvoice invoice = propertyInvoiceRepository.findByIdAndTenantId(request.invoiceId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("PropertyInvoice", request.invoiceId()));

        PropertyPayment payment = PropertyPayment.builder()
                .invoice(invoice)
                .property(invoice.getProperty())
                .paymentMethod(request.paymentMethod() != null ? request.paymentMethod() : "TRANSFERENCIA")
                .referenceNumber(request.referenceNumber())
                .amountPaid(request.amountPaid())
                .paymentDate(request.paymentDate() != null ? request.paymentDate() : LocalDate.now())
                .status(PropertyPaymentStatus.CONFIRMED)
                .receiptUrl(request.receiptUrl())
                .notes(request.notes())
                .build();
        payment.setTenantId(tenantId);

        invoice.registerPayment(request.amountPaid());
        propertyInvoiceRepository.save(invoice);
        payment = propertyPaymentRepository.save(payment);

        log.info("Payment recorded: {} for invoice {}", payment.getAmountPaid(), invoice.getInvoiceNumber());
        return toPaymentResponse(payment);
    }

    @Transactional(readOnly = true)
    public BigDecimal getPropertyBalance(UUID propertyId) {
        UUID tenantId = TenantContextHolder.getRequiredTenantId();
        propertyRepository.findByIdAndTenantId(propertyId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Property", propertyId));
        return propertyInvoiceRepository.sumPendingBalanceByProperty(tenantId, propertyId);
    }

    private BillingConfigResponse toBillingConfigResponse(BillingConfig config) {
        return new BillingConfigResponse(
                config.getId(),
                config.getTenantId(),
                config.getCalculationMethod(),
                config.getBaseBudgetAmount(),
                config.getFixedFeeAmount(),
                config.getLateFeePercentage(),
                config.getDiscountEarlyPaymentPercentage(),
                config.getDueDayOfMonth(),
                config.getDiscountDayOfMonth()
        );
    }

    private PropertyInvoiceResponse toInvoiceResponse(PropertyInvoice invoice) {
        return new PropertyInvoiceResponse(
                invoice.getId(),
                invoice.getTenantId(),
                invoice.getProperty() != null ? invoice.getProperty().getId() : null,
                invoice.getProperty() != null ? invoice.getProperty().getNumber() : null,
                invoice.getProperty() != null ? invoice.getProperty().getName() : null,
                invoice.getResident() != null ? invoice.getResident().getId() : null,
                invoice.getResident() != null ? invoice.getResident().getName() : null,
                invoice.getInvoiceNumber(),
                invoice.getPeriod(),
                invoice.getConcept(),
                invoice.getBaseAmount(),
                invoice.getLateFeeAmount(),
                invoice.getDiscountAmount(),
                invoice.getPreviousBalance(),
                invoice.getTotalAmount(),
                invoice.getBalanceDue(),
                invoice.getStatus(),
                invoice.getIssuedDate(),
                invoice.getDueDate(),
                invoice.getDiscountUntilDate(),
                invoice.getCreatedAt(),
                invoice.getUpdatedAt()
        );
    }

    private PropertyPaymentResponse toPaymentResponse(PropertyPayment payment) {
        return new PropertyPaymentResponse(
                payment.getId(),
                payment.getTenantId(),
                payment.getInvoice() != null ? payment.getInvoice().getId() : null,
                payment.getInvoice() != null ? payment.getInvoice().getInvoiceNumber() : null,
                payment.getProperty() != null ? payment.getProperty().getId() : null,
                payment.getProperty() != null ? payment.getProperty().getNumber() : null,
                payment.getPaymentMethod(),
                payment.getReferenceNumber(),
                payment.getAmountPaid(),
                payment.getPaymentDate(),
                payment.getStatus(),
                payment.getReceiptUrl(),
                payment.getNotes(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}
