package com.havita.security;

import com.havita.common.api.HealthController;
import com.havita.common.config.BaseSecurityConfig;
import com.havita.modules.organization.infrastructure.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import({BaseSecurityConfig.class, JwtAuthenticationFilter.class})
class DebugRouteSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantRepository tenantRepository;

    @MockBean
    private JwtService jwtService;

    @Test
    void shouldReturn401ForDebugLastVisitorWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/debug/last-visitor"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn401ForDebugQueryWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/debug/query"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn401ForDebugAnyRouteWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/debug/cualquier-ruta"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn404ForRemovedPublicDebugResidents() throws Exception {
        mockMvc.perform(get("/api/v1/public/debug/residents"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));
    }
}
