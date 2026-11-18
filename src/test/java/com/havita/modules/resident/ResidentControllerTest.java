package com.havita.modules.resident;

import com.havita.modules.organization.infrastructure.TenantRepository;

import com.havita.modules.resident.api.ResidentController;
import com.havita.modules.resident.api.dto.PetResponse;
import com.havita.modules.resident.api.dto.ResidentResponse;
import com.havita.modules.resident.api.dto.UpdateResidentRequest;
import org.mockito.ArgumentCaptor;
import com.havita.modules.resident.api.dto.VehicleResponse;
import com.havita.modules.resident.application.ResidentService;
import com.havita.modules.resident.application.VehicleAndPetService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResidentController.class)
@Import({com.havita.common.config.BaseSecurityConfig.class, com.havita.security.JwtAuthenticationFilter.class})
class ResidentControllerTest {

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
    private ResidentService residentService;

    @MockBean
    private VehicleAndPetService vehicleAndPetService;

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldUpdateResidentStatusWithPut() throws Exception {
        // TASK-007 (#2/#3): editar usa PUT /residents/{id} con el enum ACTIVE/INACTIVE del backend.
        UUID id = UUID.randomUUID();
        ResidentResponse response = new ResidentResponse(
                id, UUID.randomUUID(), UUID.randomUUID(), "101", "Apto 101",
                null, "Carlos Gomez", "CC", "123456789", "Propietario",
                "+573001234567", "carlos@gmail.com", true,
                null, null, com.havita.modules.resident.domain.ResidentStatus.INACTIVE,
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());
        when(residentService.update(eq(id), any())).thenReturn(response);

        mockMvc.perform(put("/api/v1/residents/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\",\"name\":\"Carlos Gomez\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));

        ArgumentCaptor<UpdateResidentRequest> captor = ArgumentCaptor.forClass(UpdateResidentRequest.class);
        verify(residentService).update(eq(id), captor.capture());
        assertEquals(com.havita.modules.resident.domain.ResidentStatus.INACTIVE, captor.getValue().status());
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldRejectStatusOutsideTheEnum() throws Exception {
        // Lo que enviaba el formulario antiguo ("Activo"/"Inactivo") no es un valor del enum: 400 y sin llamar al servicio.
        mockMvc.perform(put("/api/v1/residents/{id}", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Inactivo\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(residentService);
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldCreateResidentSuccessfully() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID propertyId = UUID.randomUUID();
        ResidentResponse response = new ResidentResponse(
                UUID.randomUUID(), tenantId, propertyId, "101", "Apto 101",
                null, "Carlos Gomez", "CC", "123456789", "Propietario",
                "+573001234567", "carlos@gmail.com", true,
                "Maria Gomez", "+573007654321", com.havita.modules.resident.domain.ResidentStatus.ACTIVE,
                OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now());

        when(residentService.create(any())).thenReturn(response);

        String body = String.format("""
                {
                    "propertyId": "%s",
                    "name": "Carlos Gomez",
                    "documentType": "CC",
                    "documentId": "123456789",
                    "type": "Propietario",
                    "phone": "+573001234567",
                    "email": "carlos@gmail.com",
                    "isMainContact": true
                }
                """, propertyId);

        mockMvc.perform(post("/api/v1/residents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Carlos Gomez"))
                .andExpect(jsonPath("$.data.type").value("Propietario"));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldCreateVehicleSuccessfully() throws Exception {
        UUID propertyId = UUID.randomUUID();
        VehicleResponse response = new VehicleResponse(
                UUID.randomUUID(), UUID.randomUUID(), propertyId, "101",
                null, null, "ABC123", "Automóvil", "Mazda", "3", "Rojo", "P-12",
                OffsetDateTime.now(), OffsetDateTime.now());

        when(vehicleAndPetService.createVehicle(any())).thenReturn(response);

        String body = String.format("""
                {
                    "propertyId": "%s",
                    "plate": "ABC123",
                    "type": "Automóvil",
                    "brand": "Mazda",
                    "model": "3",
                    "color": "Rojo",
                    "parkingSpot": "P-12"
                }
                """, propertyId);

        mockMvc.perform(post("/api/v1/residents/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.plate").value("ABC123"));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldCreatePetSuccessfully() throws Exception {
        UUID propertyId = UUID.randomUUID();
        PetResponse response = new PetResponse(
                UUID.randomUUID(), UUID.randomUUID(), propertyId, "101",
                null, null, "Max", "Perro", "Golden Retriever", "Dorado", false,
                null, null, OffsetDateTime.now(), OffsetDateTime.now());

        when(vehicleAndPetService.createPet(any())).thenReturn(response);

        String body = String.format("""
                {
                    "propertyId": "%s",
                    "name": "Max",
                    "type": "Perro",
                    "breed": "Golden Retriever",
                    "color": "Dorado"
                }
                """, propertyId);

        mockMvc.perform(post("/api/v1/residents/pets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Max"));
    }

    @Test
    @WithMockUser(roles = "RESIDENT")
    void shouldDenyResidentToCreateResidentDirectly() throws Exception {
        UUID propertyId = UUID.randomUUID();
        String body = String.format("""
                {
                    "propertyId": "%s",
                    "name": "Test Residente"
                }
                """, propertyId);

        mockMvc.perform(post("/api/v1/residents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/v1/residents"))
                .andExpect(status().isUnauthorized());
    }
}





