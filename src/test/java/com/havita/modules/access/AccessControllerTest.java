package com.havita.modules.access;

import com.havita.modules.organization.infrastructure.TenantRepository;

import com.havita.modules.access.api.AccessController;
import com.havita.modules.access.api.dto.PackageResponse;
import com.havita.modules.access.api.dto.VisitorResponse;
import com.havita.modules.access.application.PackageService;
import com.havita.modules.access.application.VisitorService;
import com.havita.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccessController.class)
@Import({com.havita.common.config.BaseSecurityConfig.class, com.havita.security.JwtAuthenticationFilter.class})
class AccessControllerTest {

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
    private VisitorService visitorService;

    @MockBean
    private PackageService packageService;

    @Test
    @WithMockUser(roles = "SECURITY")
    void shouldRegisterVisitorSuccessfully() throws Exception {
        UUID propertyId = UUID.randomUUID();
        VisitorResponse response = new VisitorResponse(
                UUID.randomUUID(), UUID.randomUUID(), propertyId, "101", "Apto 101",
                null, null, null, "Pedro Perez", "79123456", com.havita.modules.access.domain.VisitorStatus.PENDING,
                null, null, null, "XYZ789", "Visita técnica",
                "QR-12345678", null, null, "Mantenimiento",
                OffsetDateTime.now(), OffsetDateTime.now());

        when(visitorService.create(any(), anyBoolean())).thenReturn(response);

        String body = String.format("""
                {
                    "propertyId": "%s",
                    "name": "Pedro Perez",
                    "documentId": "79123456",
                    "vehiclePlate": "XYZ789",
                    "visitPurpose": "Mantenimiento"
                }
                """, propertyId);

        mockMvc.perform(post("/api/v1/access/visitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Pedro Perez"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    @WithMockUser(roles = "SECURITY")
    void shouldRegisterVisitorEntrySuccessfully() throws Exception {
        UUID visitorId = UUID.randomUUID();
        VisitorResponse response = new VisitorResponse(
                visitorId, UUID.randomUUID(), UUID.randomUUID(), "101", "Apto 101",
                null, null, null, "Pedro Perez", "79123456", com.havita.modules.access.domain.VisitorStatus.ENTERED,
                null, OffsetDateTime.now(), null, "XYZ789", null,
                "QR-12345678", null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now());

        when(visitorService.registerEntry(visitorId)).thenReturn(response);

        mockMvc.perform(post("/api/v1/access/visitors/" + visitorId + "/entry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("ENTERED"));
    }

    @Test
    @WithMockUser(roles = "SECURITY")
    void shouldReceivePackageSuccessfully() throws Exception {
        UUID propertyId = UUID.randomUUID();
        PackageResponse response = new PackageResponse(
                UUID.randomUUID(), UUID.randomUUID(), propertyId, "101", "Apto 101",
                "Ana Maria", "TRACK-9876", "Servientrega", com.havita.modules.access.domain.PackageStatus.RECEIVED,
                OffsetDateTime.now(), null, null, null, "Caja mediana",
                OffsetDateTime.now(), OffsetDateTime.now());

        when(packageService.receivePackage(any(), any())).thenReturn(response);

        String body = String.format("""
                {
                    "propertyId": "%s",
                    "recipientName": "Ana Maria",
                    "trackingNumber": "TRACK-9876",
                    "company": "Servientrega",
                    "observations": "Caja mediana"
                }
                """, propertyId);

        mockMvc.perform(post("/api/v1/access/packages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.recipientName").value("Ana Maria"))
                .andExpect(jsonPath("$.data.company").value("Servientrega"));
    }

    @Test
    @WithMockUser(roles = "RESIDENT")
    void shouldDenyResidentToDeliverPackage() throws Exception {
        UUID packageId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/access/packages/" + packageId + "/deliver"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/v1/access/visitors"))
                .andExpect(status().isUnauthorized());
    }
}




