package com.havita.security;

import com.havita.common.email.EmailService;
import com.havita.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SEC-9-9: el formulario publico de demostracion no sirve para inyectar contenido en correos ni para enviar correos a
 * terceros a voluntad. El servicio de correo esta simulado: la prueba no envia nada a Brevo.
 */
class DemoRequestAbuseIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mvc;
    @MockBean private EmailService emailService;

    @BeforeEach
    void reset() {
        clearInvocations(emailService);
    }

    private static String uniqueIp() {
        return "10.%d.%d.%d".formatted((int) (Math.random() * 250), (int) (Math.random() * 250), (int) (Math.random() * 250));
    }

    private MockHttpServletRequestBuilder demo(String json, String ip) {
        return post("/api/v1/public/demo-requests").contentType(MediaType.APPLICATION_JSON).content(json)
                .with(r -> { r.setRemoteAddr(ip); return r; });
    }

    private static String body(String name, String community, String email, String notes) {
        return "{\"name\":\"%s\",\"communityName\":\"%s\",\"units\":\"50-150\",\"email\":\"%s\",\"phone\":\"+57 300 000 0000\",\"role\":\"Administrador\",\"plan\":\"Personalizado\",\"notes\":\"%s\"}"
                .formatted(name, community, email, notes);
    }

    private static String email() {
        return "prospecto-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }

    @Test
    @DisplayName("una solicitud legitima avisa al equipo comercial y confirma al solicitante sin copiar texto del formulario")
    void legitimateRequest() throws Exception {
        String to = email();
        int code = mvc.perform(demo(body("Ana Pérez & Cía", "Torre O'Neil (Norte) #2", to, "Llamar en la tarde"), uniqueIp()))
                .andReturn().getResponse().getStatus();
        assertThat(code).isEqualTo(200);

        ArgumentCaptor<String> adminHtml = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmail(eq("demo@havita.com.co"), anyString(), adminHtml.capture());
        assertThat(adminHtml.getValue()).contains("Ana Pérez &amp; Cía").contains("Torre O&#39;Neil (Norte) #2")
                .doesNotContain("Ana Pérez & Cía");

        ArgumentCaptor<String> clientHtml = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmail(eq(to), anyString(), clientHtml.capture());
        assertThat(clientHtml.getValue()).doesNotContain("Ana").doesNotContain("Torre").doesNotContain("Llamar");
    }

    @Test
    @DisplayName("HTML, scripts, enlaces y saltos de linea en los campos: 400 sin enviar ningun correo")
    void injectionAttemptsAreRejected() throws Exception {
        List<String> payloads = List.of(
                body("<script>alert(1)</script>", "Torre A", email(), ""),
                body("Ana", "<a href=\\\"https://evil.example\\\">Gana premio</a>", email(), ""),
                body("Ana", "Torre A\\r\\nBcc: victima@example.test", email(), ""),
                body("Ana\\u0000", "Torre A", email(), ""),
                body("Ana", "Torre A", "victima@example.test\\r\\nBcc: otra@example.test", ""),
                body("Ana", "Torre A", "\\\"<x>\\\"@example.test", ""));
        for (String p : payloads) {
            assertThat(mvc.perform(demo(p, uniqueIp())).andReturn().getResponse().getStatus()).as(p).isEqualTo(400);
        }
        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("las notas (texto libre) nunca se insertan en los correos")
    void notesAreNotRendered() throws Exception {
        mvc.perform(demo(body("Ana", "Torre A", email(), "<a href='https://evil.example'>premio</a>"), uniqueIp())).andReturn();
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService, times(2)).sendEmail(anyString(), anyString(), html.capture());
        html.getAllValues().forEach(v -> assertThat(v).doesNotContain("evil.example"));
    }

    @Test
    @DisplayName("destinatarios: un mismo correo recibe una sola confirmacion en 24 h aunque se repita la solicitud")
    void oneConfirmationPerRecipient() throws Exception {
        String victim = email();
        for (int i = 0; i < 3; i++) {
            assertThat(mvc.perform(demo(body("Ana", "Torre A", victim, ""), uniqueIp())).andReturn().getResponse().getStatus()).isEqualTo(200);
        }
        verify(emailService, times(1)).sendEmail(eq(victim), anyString(), anyString());
        verify(emailService, times(3)).sendEmail(eq("demo@havita.com.co"), anyString(), anyString());
    }

    @Test
    @DisplayName("frecuencia por cliente: la sexta solicitud en una hora responde 429 y no envia correos")
    void perClientRateLimit() throws Exception {
        String ip = uniqueIp();
        for (int i = 0; i < 5; i++) {
            assertThat(mvc.perform(demo(body("Ana", "Torre A", email(), ""), ip)).andReturn().getResponse().getStatus()).isEqualTo(200);
        }
        clearInvocations(emailService);
        assertThat(mvc.perform(demo(body("Ana", "Torre A", email(), ""), ip)).andReturn().getResponse().getStatus()).isEqualTo(429);
        verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
    }
}
