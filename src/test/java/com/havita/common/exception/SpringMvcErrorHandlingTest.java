package com.havita.common.exception;

import com.havita.common.config.BaseSecurityConfig;
import com.havita.modules.organization.infrastructure.TenantRepository;
import com.havita.security.JwtAuthenticationFilter;
import com.havita.security.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SpringMvcErrorHandlingTest.ProbeController.class)
@Import({BaseSecurityConfig.class, JwtAuthenticationFilter.class, SpringMvcErrorHandlingTest.ProbeController.class})
class SpringMvcErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantRepository tenantRepository;

    @MockBean
    private JwtService jwtService;

    @RestController
    @RequestMapping("/api/v1/mvc-errors")
    static class ProbeController {

        record JsonBody(String name) {}

        record ValidatedBody(@NotBlank String name) {}

        @GetMapping("/get-only")
        public String getOnly() {
            return "ok";
        }

        @PostMapping(value = "/json-consumes", consumes = MediaType.APPLICATION_JSON_VALUE)
        public String jsonConsumes(@RequestBody JsonBody body) {
            return "ok";
        }

        @PostMapping("/validated")
        public String validated(@Valid @RequestBody ValidatedBody body) {
            return "ok";
        }

        @GetMapping("/param")
        public String param(@RequestParam("q") String q) {
            return "ok: " + q;
        }

        @GetMapping("/uuid/{id}")
        public String uuidParam(@PathVariable("id") UUID id) {
            return "ok: " + id;
        }

        @GetMapping("/int-param")
        public String intParam(@RequestParam("n") int n) {
            return "ok: " + n;
        }

        @PostMapping("/upload")
        public String upload(@RequestPart("file") MultipartFile file) {
            return "ok: " + (file != null ? file.getOriginalFilename() : "null");
        }

        @GetMapping("/max-upload")
        public void maxUpload() {
            throw new MaxUploadSizeExceededException(26214400L);
        }
    }

    @Test
    @WithMockUser
    void shouldReturn405WhenMethodNotSupported() throws Exception {
        mockMvc.perform(post("/api/v1/mvc-errors/get-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.title").value("Método No Permitido"))
                .andExpect(jsonPath("$.message").value("El método HTTP utilizado no está permitido para esta ruta."))
                .andExpect(jsonPath("$.errors").value(empty()));
    }

    @Test
    @WithMockUser
    void shouldReturn415WhenMediaTypeNotSupported() throws Exception {
        mockMvc.perform(post("/api/v1/mvc-errors/json-consumes")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().is(415))
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.title").value("Tipo de Contenido No Soportado"))
                .andExpect(jsonPath("$.message").value("El tipo de contenido de la solicitud no está soportado."))
                .andExpect(jsonPath("$.errors").value(empty()));
    }

    @Test
    @WithMockUser
    void shouldReturn400WhenRequestBodyMalformed() throws Exception {
        mockMvc.perform(post("/api/v1/mvc-errors/json-consumes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST_BODY"))
                .andExpect(jsonPath("$.title").value("Solicitud Mal Formada"))
                .andExpect(jsonPath("$.message").value("El cuerpo de la solicitud no es válido o no se pudo leer."))
                .andExpect(jsonPath("$.errors").value(empty()))
                .andExpect(content().string(not(containsString("JSON parse"))))
                .andExpect(content().string(not(containsString("com.fasterxml"))))
                .andExpect(content().string(not(containsString("{\"name\":"))));
    }

    @Test
    @WithMockUser
    void shouldReturn400WhenRequestBodyEmpty() throws Exception {
        mockMvc.perform(post("/api/v1/mvc-errors/json-consumes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST_BODY"))
                .andExpect(jsonPath("$.title").value("Solicitud Mal Formada"))
                .andExpect(jsonPath("$.message").value("El cuerpo de la solicitud no es válido o no se pudo leer."))
                .andExpect(jsonPath("$.errors").value(empty()));
    }

    @Test
    @WithMockUser
    void shouldReturn400WhenRequiredRequestParamMissing() throws Exception {
        mockMvc.perform(get("/api/v1/mvc-errors/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("MISSING_REQUEST_PARAMETER"))
                .andExpect(jsonPath("$.title").value("Parámetro Requerido Ausente"))
                .andExpect(jsonPath("$.message").value("Falta un parámetro obligatorio en la solicitud."))
                .andExpect(jsonPath("$.errors").value(empty()))
                .andExpect(jsonPath("$.message").value(not(containsString("q"))));
    }

    @Test
    @WithMockUser
    void shouldReturn400WhenPathVariableTypeMismatch() throws Exception {
        mockMvc.perform(get("/api/v1/mvc-errors/uuid/valor-sintetico-no-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_TYPE"))
                .andExpect(jsonPath("$.title").value("Parámetro con Formato Inválido"))
                .andExpect(jsonPath("$.message").value("Uno o más parámetros de la solicitud tienen un formato inválido."))
                .andExpect(jsonPath("$.errors").value(empty()))
                .andExpect(jsonPath("$.message").value(not(containsString("valor-sintetico-no-uuid"))))
                .andExpect(jsonPath("$.title").value(not(containsString("valor-sintetico-no-uuid"))));
    }

    @Test
    @WithMockUser
    void shouldReturn400WhenRequestParamTypeMismatch() throws Exception {
        mockMvc.perform(get("/api/v1/mvc-errors/int-param").param("n", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER_TYPE"))
                .andExpect(jsonPath("$.title").value("Parámetro con Formato Inválido"))
                .andExpect(jsonPath("$.message").value("Uno o más parámetros de la solicitud tienen un formato inválido."))
                .andExpect(jsonPath("$.errors").value(empty()))
                .andExpect(jsonPath("$.message").value(not(containsString("abc"))));
    }

    @Test
    @WithMockUser
    void shouldReturn400WhenRequiredRequestPartMissing() throws Exception {
        mockMvc.perform(multipart("/api/v1/mvc-errors/upload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("MISSING_REQUEST_PARAMETER"))
                .andExpect(jsonPath("$.title").value("Parámetro Requerido Ausente"))
                .andExpect(jsonPath("$.message").value("Falta un parámetro obligatorio en la solicitud."))
                .andExpect(jsonPath("$.errors").value(empty()));
    }

    @Test
    @WithMockUser
    void shouldReturn413WhenMaxUploadSizeExceeded() throws Exception {
        mockMvc.perform(get("/api/v1/mvc-errors/max-upload"))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.title").value("Archivo Demasiado Grande"))
                .andExpect(jsonPath("$.message").value("El archivo o la solicitud supera el tamaño máximo permitido."))
                .andExpect(jsonPath("$.errors").value(empty()));
    }

    @Test
    @WithMockUser
    void shouldReturn404WhenRouteDoesNotExistEvenWithPost() throws Exception {
        mockMvc.perform(post("/api/v1/mvc-errors/no-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));
    }

    @Test
    @WithMockUser
    void shouldReturn400WithValidationFailedWhenRequestBodyInvalid() throws Exception {
        mockMvc.perform(post("/api/v1/mvc-errors/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").value(not(empty())));
    }

    @Test
    void shouldReturn401WhenUnauthenticatedEvenIfMethodIsIncorrect() throws Exception {
        mockMvc.perform(post("/api/v1/mvc-errors/get-only"))
                .andExpect(status().isUnauthorized());
    }
}
