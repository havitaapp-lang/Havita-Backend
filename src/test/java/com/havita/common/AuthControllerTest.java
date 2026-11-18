package com.havita.modules.identity;

import com.havita.modules.organization.infrastructure.TenantRepository;

import com.havita.modules.identity.api.AuthController;
import com.havita.modules.identity.api.dto.AuthResponse;
import com.havita.modules.identity.api.dto.LoginRequest;
import com.havita.modules.identity.application.AuthService;
import com.havita.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({com.havita.common.config.BaseSecurityConfig.class, com.havita.security.JwtAuthenticationFilter.class})
class AuthControllerTest {

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
    private AuthService authService;

    @MockBean
    private JwtService jwtService;

    @Test
    void shouldLoginSuccessfullyAndReturnJwt() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        AuthResponse mockResponse = new AuthResponse(
                "mock.access.jwt.token",
                "mock-refresh-uuid",
                "Bearer",
                3600,
                new AuthResponse.UserDto(
                        userId,
                        "sebas@ejemplo.com",
                        "Sebastián Gómez",
                        "RESIDENT",
                        tenantId,
                        "Torre 2 - 504",
                        null,
                        true,
                        java.util.List.of()
                )
        );

        when(authService.login(any(LoginRequest.class), any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn(mockResponse);

        LoginRequest request = new LoginRequest("sebas@ejemplo.com", "password123", tenantId);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("mock.access.jwt.token"))
                .andExpect(jsonPath("$.data.user.role").value("RESIDENT"))
                .andExpect(jsonPath("$.data.user.propertyUnit").value("Torre 2 - 504"));
    }

    @Test
    void shouldRejectInvalidEmailOnLogin() throws Exception {
        LoginRequest request = new LoginRequest("correo-no-valido", "pass", null);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").isArray());
    }
}





