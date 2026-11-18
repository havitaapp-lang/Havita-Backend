package com.havita.modules.assembly;

import com.havita.modules.organization.infrastructure.TenantRepository;

import com.havita.modules.assembly.api.AssemblyController;
import com.havita.modules.assembly.api.dto.AssemblyResponse;
import com.havita.modules.assembly.api.dto.AttendeeResponse;
import com.havita.modules.assembly.api.dto.PollResponse;
import com.havita.modules.assembly.application.AssemblyService;
import com.havita.modules.assembly.application.PollService;
import com.havita.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AssemblyController.class)
@Import({com.havita.common.config.BaseSecurityConfig.class, com.havita.security.JwtAuthenticationFilter.class})
class AssemblyControllerTest {

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
    private AssemblyService assemblyService;

    @MockBean
    private PollService pollService;

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldCreateAssemblySuccessfully() throws Exception {
        AssemblyResponse response = new AssemblyResponse(
                UUID.randomUUID(), UUID.randomUUID(), "Asamblea General Ordinaria 2026",
                "ORDINARIA", "MIXTA", LocalDate.now().plusDays(15), LocalTime.of(9, 0),
                com.havita.modules.assembly.domain.AssemblyStatus.SCHEDULED, 100, 0, BigDecimal.ZERO, 0,
                "Asamblea anual ordinaria", OffsetDateTime.now(), OffsetDateTime.now());

        when(assemblyService.create(any())).thenReturn(response);

        String body = String.format("""
                {
                    "title": "Asamblea General Ordinaria 2026",
                    "type": "ORDINARIA",
                    "modality": "MIXTA",
                    "assemblyDate": "%s",
                    "startTime": "09:00:00",
                    "description": "Asamblea anual ordinaria"
                }
                """, LocalDate.now().plusDays(15));

        mockMvc.perform(post("/api/v1/assemblies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.title").value("Asamblea General Ordinaria 2026"));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldRegisterAttendeeSuccessfully() throws Exception {
        UUID assemblyId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();
        AttendeeResponse response = new AttendeeResponse(
                UUID.randomUUID(), assemblyId, propertyId, "101", "Apto 101",
                null, "Juan Perez", new BigDecimal("0.025000"), false, null,
                OffsetDateTime.now());

        when(assemblyService.registerAttendee(any(), any())).thenReturn(response);

        String body = String.format("""
                {
                    "propertyId": "%s",
                    "isPowerOfAttorney": false
                }
                """, propertyId);

        mockMvc.perform(post("/api/v1/assemblies/" + assemblyId + "/attendees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.propertyNumber").value("101"));
    }

    @Test
    @WithMockUser(roles = "RESIDENT")
    void shouldCastVoteSuccessfully() throws Exception {
        UUID pollId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();

        PollResponse response = new PollResponse(
                pollId, UUID.randomUUID(), UUID.randomUUID(), "Asamblea 2026",
                "¿Aprueba el presupuesto 2026?", "SINGLE_CHOICE",
                List.of(new PollResponse.PollOptionDto(optionId, "Sí", 1, new BigDecimal("0.025000"), new BigDecimal("100.00"))),
                com.havita.modules.assembly.domain.PollStatus.LIVE, true, 1, new BigDecimal("0.025000"),
                OffsetDateTime.now(), null, 15,
                OffsetDateTime.now(), OffsetDateTime.now());

        when(pollService.castVote(any(), any())).thenReturn(response);

        String body = String.format("""
                {
                    "propertyId": "%s",
                    "optionIds": ["%s"]
                }
                """, propertyId, optionId);

        mockMvc.perform(post("/api/v1/assemblies/polls/" + pollId + "/vote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalVotes").value(1));
    }

    @Test
    @WithMockUser(roles = "RESIDENT")
    void shouldDenyResidentToCreatePoll() throws Exception {
        String body = """
                {
                    "assemblyId": "d3b07384-d113-4a44-9988-abcdef123456",
                    "question": "¿Pregunta no autorizada?",
                    "options": ["A", "B"]
                }
                """;

        mockMvc.perform(post("/api/v1/assemblies/polls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/v1/assemblies"))
                .andExpect(status().isUnauthorized());
    }
}




