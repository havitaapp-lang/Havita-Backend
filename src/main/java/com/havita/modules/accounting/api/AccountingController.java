package com.havita.modules.accounting.api;

import com.havita.modules.accounting.domain.PropertyInvoiceStatus;
import com.havita.common.api.ApiResponse;
import com.havita.common.api.PageResponse;
import com.havita.modules.accounting.api.dto.BillingConfigResponse;
import com.havita.modules.accounting.api.dto.CreatePropertyInvoiceRequest;
import com.havita.modules.accounting.api.dto.PropertyInvoiceResponse;
import com.havita.modules.accounting.api.dto.PropertyPaymentResponse;
import com.havita.modules.accounting.api.dto.RecordPropertyPaymentRequest;
import com.havita.modules.accounting.api.dto.UpdateBillingConfigRequest;
import com.havita.modules.accounting.application.AccountingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounting")
@RequiredArgsConstructor
@Tag(name = "Accounting & Billing", description = "Gestión de facturación, cuotas de administración y recaudos a residentes")
public class AccountingController {

    private final AccountingService accountingService;

    @GetMapping("/config")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'ACCOUNTANT')")
    @Operation(summary = "Obtener configuración de cobro", description = "Obtiene los parámetros presupuestales y reglas de liquidación")
    public ResponseEntity<ApiResponse<BillingConfigResponse>> getBillingConfig() {
        return ResponseEntity.ok(ApiResponse.ok(accountingService.getBillingConfig()));
    }

    @PutMapping("/config")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Actualizar configuración de cobro")
    public ResponseEntity<ApiResponse<BillingConfigResponse>> updateBillingConfig(
            @Valid @RequestBody UpdateBillingConfigRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                accountingService.updateBillingConfig(request),
                "Configuración de facturación actualizada exitosamente"
        ));
    }

    @GetMapping("/invoices")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'ACCOUNTANT', 'RESIDENT')")
    @Operation(summary = "Listar cuentas de cobro", description = "Lista paginada con filtro opcional por estado")
    public ResponseEntity<ApiResponse<PageResponse<PropertyInvoiceResponse>>> findInvoices(
            @RequestParam(required = false) PropertyInvoiceStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<PropertyInvoiceResponse> page = accountingService.findInvoices(status, pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @GetMapping("/invoices/by-property/{propertyId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'ACCOUNTANT') or (hasRole('RESIDENT') and @accessValidator.isActiveResidentOfProperty(#propertyId))")
    @Operation(summary = "Listar cuentas de cobro de un inmueble")
    public ResponseEntity<ApiResponse<List<PropertyInvoiceResponse>>> findInvoicesByProperty(
            @PathVariable UUID propertyId) {
        return ResponseEntity.ok(ApiResponse.ok(accountingService.findInvoicesByProperty(propertyId)));
    }

    @PostMapping("/invoices")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'ACCOUNTANT')")
    @Operation(summary = "Crear cuenta de cobro individual")
    public ResponseEntity<ApiResponse<PropertyInvoiceResponse>> createInvoice(
            @Valid @RequestBody CreatePropertyInvoiceRequest request) {
        PropertyInvoiceResponse response = accountingService.createInvoice(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Cuenta de cobro generada exitosamente"));
    }

    @PostMapping("/invoices/generate-monthly")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'ACCOUNTANT')")
    @Operation(summary = "Generación masiva mensual de cuentas de cobro")
    public ResponseEntity<ApiResponse<List<PropertyInvoiceResponse>>> generateMonthlyInvoices(
            @RequestParam String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate) {
        List<PropertyInvoiceResponse> responses = accountingService.generateMonthlyInvoices(period, dueDate);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(responses, "Facturación masiva mensual generada exitosamente"));
    }

    @PostMapping("/payments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'ACCOUNTANT')")
    @Operation(summary = "Registrar y conciliar pago a una cuenta de cobro")
    public ResponseEntity<ApiResponse<PropertyPaymentResponse>> recordPayment(
            @Valid @RequestBody RecordPropertyPaymentRequest request) {
        PropertyPaymentResponse response = accountingService.recordPayment(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Pago registrado y aplicado a la cuenta de cobro exitosamente"));
    }

    @GetMapping("/properties/{propertyId}/balance")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'ACCOUNTANT') or (hasRole('RESIDENT') and @accessValidator.isActiveResidentOfProperty(#propertyId))")
    @Operation(summary = "Consultar saldo pendiente acumulado de un inmueble")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPropertyBalance(
            @PathVariable UUID propertyId) {
        BigDecimal balance = accountingService.getPropertyBalance(propertyId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "propertyId", propertyId,
                "balanceDue", balance != null ? balance : BigDecimal.ZERO
        )));
    }
}
