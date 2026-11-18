package com.havita.modules.property;

import com.havita.modules.organization.infrastructure.TenantRepository;

import com.havita.modules.property.api.PropertyController;
import com.havita.modules.property.api.dto.CommonZoneResponse;
import com.havita.modules.property.api.dto.PropertyResponse;
import com.havita.modules.property.api.dto.StructureResponse;
import com.havita.modules.property.application.CommonZoneService;
import com.havita.modules.property.application.PropertyService;
import com.havita.modules.property.application.StructureService;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PropertyController.class)
@Import({com.havita.common.config.BaseSecurityConfig.class, com.havita.security.JwtAuthenticationFilter.class})
class PropertyControllerTest {

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
    private PropertyService propertyService;

    @MockBean
    private StructureService structureService;

    @MockBean
    private CommonZoneService commonZoneService;

    @MockBean
    private com.havita.modules.property.application.PropertyTransferService propertyTransferService;

    @MockBean
    private com.havita.modules.property.application.BulkPropertyService bulkPropertyService;

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldCreatePropertySuccessfully() throws Exception {
        UUID tenantId = UUID.randomUUID();
        PropertyResponse response = new PropertyResponse(
                UUID.randomUUID(), tenantId, null, null, null,
                "101", "Apto 101", "Torre 1 -> Piso 1 -> Apto 101",
                "Apartamento", com.havita.modules.property.domain.PropertyStatus.VACANT, "1", new BigDecimal("75.50"),
                new BigDecimal("0.025000"), 3, 2, 1, 0,
                OffsetDateTime.now(), OffsetDateTime.now());

        when(propertyService.create(any())).thenReturn(response);

        String body = """
                {
                    "number": "101",
                    "name": "Apto 101",
                    "path": "Torre 1 -> Piso 1 -> Apto 101",
                    "type": "Apartamento",
                    "level": "1",
                    "area": 75.50,
                    "coefficient": 0.025000,
                    "bedrooms": 3,
                    "bathrooms": 2,
                    "vehiclesCount": 1,
                    "petsCount": 0
                }
                """;

        mockMvc.perform(post("/api/v1/properties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.number").value("101"))
                .andExpect(jsonPath("$.data.name").value("Apto 101"));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldCreateStructureSuccessfully() throws Exception {
        StructureResponse response = new StructureResponse(
                UUID.randomUUID(), UUID.randomUUID(), "Torre 1",
                "Torre", 12, "Torre principal",
                OffsetDateTime.now(), OffsetDateTime.now());

        when(structureService.create(any())).thenReturn(response);

        String body = """
                {
                    "name": "Torre 1",
                    "type": "Torre",
                    "totalLevels": 12,
                    "description": "Torre principal"
                }
                """;

        mockMvc.perform(post("/api/v1/properties/structures")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Torre 1"));
    }

    @Test
    @WithMockUser(roles = "COMMUNITY_ADMIN")
    void shouldCreateCommonZoneSuccessfully() throws Exception {
        CommonZoneResponse response = new CommonZoneResponse(
                UUID.randomUUID(), UUID.randomUUID(), "Piscina",
                "Piscina climatizada", 30, true, new BigDecimal("0.0"),
                "Uso exclusivo residentes con carnet", com.havita.modules.property.domain.CommonZoneStatus.ACTIVE,
                null, null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now());

        when(commonZoneService.create(any())).thenReturn(response);

        String body = """
                {
                    "name": "Piscina",
                    "description": "Piscina climatizada",
                    "capacity": 30,
                    "reservationRequired": true,
                    "costPerHour": 0.0,
                    "rules": "Uso exclusivo residentes con carnet"
                }
                """;

        mockMvc.perform(post("/api/v1/properties/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("Piscina"));
    }

    @Test
    @WithMockUser(roles = "RESIDENT")
    void shouldDenyResidentToCreateProperty() throws Exception {
        String body = """
                {
                    "number": "202",
                    "name": "Apto 202"
                }
                """;

        mockMvc.perform(post("/api/v1/properties")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldDenyUnauthenticatedAccess() throws Exception {
        mockMvc.perform(get("/api/v1/properties"))
                .andExpect(status().isUnauthorized());
    }
}




