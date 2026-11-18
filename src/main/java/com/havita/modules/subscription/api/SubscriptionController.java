package com.havita.modules.subscription.api;

import com.havita.common.api.ApiResponse;
import com.havita.common.api.PageResponse;
import com.havita.modules.subscription.api.dto.CreatePlanRequest;
import com.havita.modules.subscription.api.dto.PlanResponse;
import com.havita.modules.subscription.api.dto.SaaSInvoiceResponse;
import com.havita.modules.subscription.api.dto.SubscribeRequest;
import com.havita.modules.subscription.api.dto.SubscriptionResponse;
import com.havita.modules.subscription.application.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/saas")
@RequiredArgsConstructor
@Tag(name = "SaaS Subscriptions & Billing", description = "Planes de suscripción de la plataforma, cobro recurrente e invoice B2B")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    // --- Planes (Plans) ---

    @GetMapping("/plans")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN', 'READ_ONLY', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Listar planes SaaS disponibles")
    public ResponseEntity<ApiResponse<List<PlanResponse>>> findAllPlans() {
        return ResponseEntity.ok(ApiResponse.ok(subscriptionService.findAllPlans()));
    }

    @GetMapping("/plans/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'PLATFORM_ADMIN', 'READ_ONLY', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Obtener plan SaaS por ID")
    public ResponseEntity<ApiResponse<PlanResponse>> findPlanById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(subscriptionService.findPlanById(id)));
    }

    @PostMapping("/plans")
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Crear nuevo plan SaaS")
    public ResponseEntity<ApiResponse<PlanResponse>> createPlan(
            @Valid @RequestBody CreatePlanRequest request) {
        PlanResponse response = subscriptionService.createPlan(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Plan SaaS creado exitosamente"));
    }

    // --- Suscripción del Tenant ---

    @GetMapping("/subscription/current")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Consultar suscripción actual de la copropiedad")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> getCurrentSubscription() {
        return ResponseEntity.ok(ApiResponse.ok(subscriptionService.getCurrentSubscription()));
    }

    @PostMapping("/tenants/{tenantId}/subscribe")
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Asignar o actualizar suscripción a un tenant")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> subscribeTenant(
            @PathVariable UUID tenantId,
            @Valid @RequestBody SubscribeRequest request) {
        SubscriptionResponse response = subscriptionService.subscribeTenant(tenantId, request);
        return ResponseEntity.ok(ApiResponse.ok(response, "Suscripción actualizada exitosamente"));
    }

    @PostMapping("/subscription/change-plan")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Cambiar plan de la suscripción activa (Upgrade/Downgrade)")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> changePlan(
            @Valid @RequestBody com.havita.modules.subscription.api.dto.ChangePlanRequest request) {
        SubscriptionResponse response = subscriptionService.changePlan(request.planCode());
        return ResponseEntity.ok(ApiResponse.ok(response, "Plan cambiado exitosamente"));
    }

    // --- Super Admin Global Subscriptions ---

    @GetMapping("/subscriptions")
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Listar todas las suscripciones (Global Backoffice)")
    public ResponseEntity<ApiResponse<PageResponse<SubscriptionResponse>>> findAllSubscriptions(
            @PageableDefault(size = 100) Pageable pageable) {
        Page<SubscriptionResponse> page = subscriptionService.findAllSubscriptions(pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @PostMapping("/subscriptions/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Cambiar estado de una suscripción")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> updateSubscriptionStatus(
            @PathVariable UUID id,
            @RequestParam com.havita.modules.subscription.domain.SaaSSubscriptionStatus status) {
        SubscriptionResponse response = subscriptionService.updateSubscriptionStatus(id, status);
        return ResponseEntity.ok(ApiResponse.ok(response, "Estado actualizado exitosamente"));
    }

    @PostMapping("/subscriptions/{id}/change-plan")
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Forzar cambio de plan de una suscripción específica")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> adminChangeSubscriptionPlan(
            @PathVariable UUID id,
            @RequestParam UUID planId) {
        SubscriptionResponse response = subscriptionService.adminChangeSubscriptionPlan(id, planId);
        return ResponseEntity.ok(ApiResponse.ok(response, "Plan cambiado exitosamente"));
    }

    // --- Facturas SaaS (B2B Invoices) ---

    @GetMapping("/invoices")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Listar facturas B2B de la suscripción")
    public ResponseEntity<ApiResponse<PageResponse<SaaSInvoiceResponse>>> findInvoices(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<SaaSInvoiceResponse> page = subscriptionService.findInvoices(pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @PostMapping("/tenants/{tenantId}/invoices/generate")
    @PreAuthorize("hasRole('SUPER_ADMIN') and hasAuthority('SCOPE_GLOBAL')")
    @Operation(summary = "Emitir factura SaaS a una copropiedad")
    public ResponseEntity<ApiResponse<SaaSInvoiceResponse>> generateInvoice(
            @PathVariable UUID tenantId,
            @RequestParam String period) {
        SaaSInvoiceResponse response = subscriptionService.generateInvoice(tenantId, period);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Factura SaaS emitida exitosamente"));
    }

    @PostMapping("/invoices/{invoiceId}/pay")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Registrar pago de factura SaaS")
    public ResponseEntity<ApiResponse<SaaSInvoiceResponse>> payInvoice(
            @PathVariable UUID invoiceId,
            @RequestParam(required = false) String paymentReference) {
        return ResponseEntity.ok(ApiResponse.ok(subscriptionService.markInvoicePaid(invoiceId, paymentReference), "Factura marcada como pagada"));
    }
}
