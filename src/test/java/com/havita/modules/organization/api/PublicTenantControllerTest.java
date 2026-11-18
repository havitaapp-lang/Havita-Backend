package com.havita.modules.organization.api;

import com.havita.modules.organization.domain.Tenant;
import com.havita.modules.organization.domain.TenantStatus;
import com.havita.modules.organization.infrastructure.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;

@WebMvcTest(PublicTenantController.class)
@AutoConfigureMockMvc(addFilters = false)
public class PublicTenantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantRepository tenantRepository;

    @MockBean
    private com.havita.security.JwtService jwtService;

    private Tenant activeTenant = new Tenant();

    @BeforeEach
    void setUp() {
        activeTenant = new Tenant();
        activeTenant.setId(UUID.randomUUID());
        activeTenant.setName("Limonar Parque Residencial");
        activeTenant.setSlug("limonar");
        activeTenant.setSubdomain("limonar");
        activeTenant.setStatus(TenantStatus.ACTIVE);
        activeTenant.setLogoUrl("LPR");
        activeTenant.setPrimaryColor("#ff0000");
        activeTenant.setSecondaryColor("#18B89A");
        activeTenant.setCity("Cali");
        activeTenant.setAddress("Cra. 50");
    }

    @Test
    void shouldReturnPublicConfigWhenValidSubdomainInHost() throws Exception {
        when(tenantRepository.findBySubdomain("limonar")).thenReturn(Optional.of(activeTenant));

        mockMvc.perform(get("/api/v1/public/tenant-config")
                .header("Host", "limonar.havita.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Limonar Parque Residencial"))
                .andExpect(jsonPath("$.data.logoUrl").value("LPR"))
                .andExpect(jsonPath("$.data.primaryColor").value("#ff0000"))
                .andExpect(jsonPath("$.data.secondaryColor").value("#18B89A"))
                .andExpect(jsonPath("$.data.city").value("Cali"))
                .andExpect(jsonPath("$.data.address").value("Cra. 50"))
                .andExpect(jsonPath("$.data.status").doesNotExist())
                .andExpect(jsonPath("$.data.storageLimitMb").doesNotExist())
                .andExpect(jsonPath("$.data.usersCount").doesNotExist());
    }

    @Test
    void shouldReturnNotFoundWhenTenantIsSuspended() throws Exception {
        activeTenant.setStatus(TenantStatus.SUSPENDED);
        when(tenantRepository.findBySubdomain("limonar")).thenReturn(Optional.of(activeTenant));

        mockMvc.perform(get("/api/v1/public/tenant-config")
                .header("Host", "limonar.havita.com"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnNotFoundWhenSubdomainDoesNotExist() throws Exception {
        when(tenantRepository.findBySubdomain(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/public/tenant-config")
                .header("Host", "unknown.havita.com"))
                .andExpect(status().isNotFound());
    }
}

