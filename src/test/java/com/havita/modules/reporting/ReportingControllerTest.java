package com.havita.modules.reporting;

import com.havita.modules.organization.infrastructure.TenantRepository;

import com.havita.modules.reporting.api.ReportingController;
import com.havita.modules.reporting.application.ReportingService;
import com.havita.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReportingController.class)
@Import({com.havita.common.config.BaseSecurityConfig.class, com.havita.security.JwtAuthenticationFilter.class})
class ReportingControllerTest {

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


    @MockBean
    private JwtService jwtService;

    @MockBean
    private ReportingService reportingService;

    /** SEC-3: la regla de unidad de @PreAuthorize se simula; su comportamiento real lo cubren UnitScoped*IT. */
    @MockBean(name = "accessValidator")
    private com.havita.security.AccessValidator accessValidator;

    @Test
    @WithMockUser(roles = "RESIDENT")
    void shouldRejectPazYSalvoOfAnotherUnitForResident() throws Exception {
        UUID propertyId = UUID.randomUUID();
        when(accessValidator.isActiveResidentOfProperty(propertyId)).thenReturn(false);

        mockMvc.perform(get("/api/v1/reporting/properties/" + propertyId + "/paz-y-salvo"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(reportingService);
    }

    @Test
    @WithMockUser(roles = "RESIDENT")
    void shouldDownloadPazYSalvoPdf() throws Exception {
        UUID propertyId = UUID.randomUUID();
        when(accessValidator.isActiveResidentOfProperty(propertyId)).thenReturn(true);
        byte[] fakePdf = "%PDF-1.4 simulated pdf document".getBytes();
        when(reportingService.generatePazYSalvoPdf(propertyId)).thenReturn(fakePdf);

        mockMvc.perform(get("/api/v1/reporting/properties/" + propertyId + "/paz-y-salvo"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().exists("Content-Disposition"))
                .andExpect(content().bytes(fakePdf));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldExportPortfolioExcel() throws Exception {
        byte[] fakeExcel = new byte[]{0x50, 0x4B, 0x03, 0x04}; // zip header
        when(reportingService.exportPortfolioReportExcel()).thenReturn(fakeExcel);

        mockMvc.perform(get("/api/v1/reporting/portfolio/excel"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().exists("Content-Disposition"))
                .andExpect(content().bytes(fakeExcel));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldExportResidentsCensusCsv() throws Exception {
        String csvData = "Inmueble,Nombre,Tipo Documento\n\"101\",\"Juan Perez\",\"CC\"\n";
        when(reportingService.exportResidentsCensusCsv()).thenReturn(csvData.getBytes());

        mockMvc.perform(get("/api/v1/reporting/residents/census/csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv"))
                .andExpect(header().exists("Content-Disposition"))
                .andExpect(content().string(csvData));
    }

    @Test
    void shouldRejectUnauthenticatedExport() throws Exception {
        mockMvc.perform(get("/api/v1/reporting/portfolio/excel"))
                .andExpect(status().isUnauthorized());
    }
}




