package com.havita.security;

import com.havita.support.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** SEC-9-9: con el perfil de desarrollo "local", la documentacion OpenAPI sigue disponible para las herramientas. */
@ActiveProfiles({"test", "local"})
class OpenApiLocalProfileIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;

    @Test
    @DisplayName("perfil local: /v3/api-docs y Swagger UI disponibles; Actuator sigue limitado a health")
    void openApiAvailableInLocalProfile() throws Exception {
        assertThat(mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus()).isIn(200, 302);
        assertThat(mvc.perform(get("/actuator/metrics")).andReturn().getResponse().getStatus()).isNotEqualTo(200);
    }
}
