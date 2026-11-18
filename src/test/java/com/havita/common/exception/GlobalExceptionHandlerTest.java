package com.havita.common.exception;

import com.havita.common.config.BaseSecurityConfig;
import com.havita.modules.organization.infrastructure.TenantRepository;
import com.havita.security.JwtAuthenticationFilter;
import com.havita.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestProbeController.class)
@Import({BaseSecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandlerTest.TestProbeController.class})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantRepository tenantRepository;

    @MockBean
    private JwtService jwtService;

    @RestController
    @RequestMapping("/api/v1/test-probe")
    static class TestProbeController {

        @GetMapping("/runtime-error")
        public void throwRuntimeError() {
            throw new RuntimeException("marcador-sintetico-error");
        }

        @GetMapping("/resource-not-found")
        public void throwResourceNotFound() {
            throw new ResourceNotFoundException("Widget", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        }
    }

    @Test
    @WithMockUser
    void shouldReturn404ForNonExistentAuthenticatedRoute() throws Exception {
        mockMvc.perform(get("/api/v1/test-probe/ruta-inexistente"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/test-probe/ruta-inexistente"))
                .andExpect(jsonPath("$.errors").value(empty()));
    }

    @Test
    void shouldReturn404ForNonExistentPublicRouteWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/public/ruta-inexistente"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));
    }

    @Test
    void shouldReturn401ForNonExistentRouteWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/test-probe/ruta-inexistente"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void shouldReturn500ForUnexpectedExceptionWithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/api/v1/test-probe/runtime-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.title").value("Error Interno del Servidor"))
                .andExpect(content().string(not(containsString("marcador-sintetico-error"))));
    }

    @Test
    @WithMockUser
    void shouldReturn404ForResourceNotFoundException() throws Exception {
        mockMvc.perform(get("/api/v1/test-probe/resource-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WIDGET_NOT_FOUND"));
    }
}
