package com.havita.modules.accounting;

import com.havita.modules.organization.infrastructure.TenantRepository;

import com.havita.modules.accounting.api.AccountingController;
import com.havita.modules.accounting.api.dto.BillingConfigResponse;
import com.havita.modules.accounting.api.dto.CreatePropertyInvoiceRequest;
import com.havita.modules.accounting.api.dto.PropertyInvoiceResponse;
import com.havita.modules.accounting.api.dto.PropertyPaymentResponse;
import com.havita.modules.accounting.api.dto.RecordPropertyPaymentRequest;
import com.havita.modules.accounting.application.AccountingService;
import com.havita.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountingController.class)
@Import({com.havita.common.config.BaseSecurityConfig.class, com.havita.security.JwtAuthenticationFilter.class})
class AccountingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantRepository tenantRepository;

    @org.junit.jupiter.api.BeforeEach
    void setUpTenant() {
        com.havita.modules.organization.domain.Tenant activeTenant = new com.havita.modules.organization.domain.Tenant();
        activeTenant.setStatus(com.havita.modules.organization.domain.TenantStatus.ACTIVE);
        org.mockito.Mockito.when(tenantRepository.findById(org.mockito.ArgumentMatchers.any(java.util.UUID.class))).thenReturn(java.util.Optional.of(activeTenant));
    }


    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private AccountingService accountingService;

    /** SEC-3: la regla de unidad de @PreAuthorize se simula; su comportamiento real lo cubren UnitScoped*IT. */
    @MockBean(name = "accessValidator")
    private com.havita.security.AccessValidator accessValidator;

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldGetBillingConfig() throws Exception {
        BillingConfigResponse response = new BillingConfigResponse(
                UUID.randomUUID(), UUID.randomUUID(), "COEFFICIENT",
                new BigDecimal("25000000.00"), BigDecimal.ZERO,
                new BigDecimal("1.50"), new BigDecimal("5.00"), 15, 5
        );
        when(accountingService.getBillingConfig()).thenReturn(response);

        mockMvc.perform(get("/api/v1/accounting/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.calculationMethod").value("COEFFICIENT"))
                .andExpect(jsonPath("$.data.dueDayOfMonth").value(15));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldCreatePropertyInvoice() throws Exception {
        UUID propertyId = UUID.randomUUID();
        CreatePropertyInvoiceRequest request = new CreatePropertyInvoiceRequest(
                propertyId, null, "Septiembre 2026",
                "Cuota Ordinaria", new BigDecimal("250000.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                LocalDate.now().plusDays(15), null
        );

        PropertyInvoiceResponse response = new PropertyInvoiceResponse(
                UUID.randomUUID(), UUID.randomUUID(), propertyId, "A-101", "Apto 101",
                null, null, "INV-SEP-2026-A-101", "Septiembre 2026",
                "Cuota Ordinaria", new BigDecimal("250000.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("250000.00"), new BigDecimal("250000.00"),
                com.havita.modules.accounting.domain.PropertyInvoiceStatus.PENDING, LocalDate.now(), LocalDate.now().plusDays(15), null,
                OffsetDateTime.now(), OffsetDateTime.now()
        );
        when(accountingService.createInvoice(any(CreatePropertyInvoiceRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/accounting/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.invoiceNumber").value("INV-SEP-2026-A-101"))
                .andExpect(jsonPath("$.data.balanceDue").value(250000.00));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldGenerateMonthlyInvoices() throws Exception {
        PropertyInvoiceResponse response = new PropertyInvoiceResponse(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "101", "Apto 101",
                null, null, "INV-SEP-2026-101", "Septiembre 2026",
                "Cuota de Administración - Septiembre 2026", new BigDecimal("220000.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("220000.00"), new BigDecimal("220000.00"),
                com.havita.modules.accounting.domain.PropertyInvoiceStatus.PENDING, LocalDate.now(), LocalDate.now().plusDays(15), null,
                OffsetDateTime.now(), OffsetDateTime.now()
        );
        when(accountingService.generateMonthlyInvoices(eq("Septiembre 2026"), any())).thenReturn(List.of(response));

        mockMvc.perform(post("/api/v1/accounting/invoices/generate-monthly")
                        .param("period", "Septiembre 2026"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].invoiceNumber").value("INV-SEP-2026-101"));
    }

    @Test
    @WithMockUser(roles = "ACCOUNTANT")
    void shouldRecordPropertyPayment() throws Exception {
        UUID invoiceId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();
        RecordPropertyPaymentRequest request = new RecordPropertyPaymentRequest(
                invoiceId, new BigDecimal("250000.00"), "TRANSFERENCIA",
                "REF-998877", LocalDate.now(), "https://s3.aws.com/receipt.pdf", "Pago completo"
        );

        PropertyPaymentResponse response = new PropertyPaymentResponse(
                UUID.randomUUID(), UUID.randomUUID(), invoiceId, "INV-SEP-2026-A-101",
                propertyId, "A-101", "TRANSFERENCIA", "REF-998877",
                new BigDecimal("250000.00"), LocalDate.now(), com.havita.modules.accounting.domain.PropertyPaymentStatus.CONFIRMED,
                "https://s3.aws.com/receipt.pdf", "Pago completo",
                OffsetDateTime.now(), OffsetDateTime.now()
        );
        when(accountingService.recordPayment(any(RecordPropertyPaymentRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/accounting/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.referenceNumber").value("REF-998877"));
    }

    @Test
    @WithMockUser(roles = "RESIDENT")
    void shouldRejectBalanceOfAnotherUnitForResident() throws Exception {
        UUID propertyId = UUID.randomUUID();
        when(accessValidator.isActiveResidentOfProperty(propertyId)).thenReturn(false);

        mockMvc.perform(get("/api/v1/accounting/properties/" + propertyId + "/balance"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(accountingService);
    }

    @Test
    @WithMockUser(roles = "RESIDENT")
    void shouldGetPropertyBalanceForResident() throws Exception {
        UUID propertyId = UUID.randomUUID();
        when(accessValidator.isActiveResidentOfProperty(propertyId)).thenReturn(true);
        when(accountingService.getPropertyBalance(propertyId)).thenReturn(new BigDecimal("450000.00"));

        mockMvc.perform(get("/api/v1/accounting/properties/" + propertyId + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.balanceDue").value(450000.00));
    }

    @Test
    void shouldRejectUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/accounting/config"))
                .andExpect(status().isUnauthorized());
    }
}




