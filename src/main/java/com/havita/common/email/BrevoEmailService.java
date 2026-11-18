package com.havita.common.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class BrevoEmailService implements EmailService {

    private final RestClient restClient;
    private final String senderEmail;
    private final String senderName;

    public BrevoEmailService(
            @Value("${app.email.brevo.api-key}") String apiKey,
            @Value("${app.email.sender.address:no-reply@havita.com.co}") String senderEmail,
            @Value("${app.email.sender.name:Havita}") String senderName) {
        
        this.senderEmail = senderEmail;
        this.senderName = senderName;
        
        this.restClient = RestClient.builder()
                .baseUrl("https://api.brevo.com/v3")
                .defaultHeader("api-key", apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public void sendEmail(String to, String subject, String htmlContent) {
        try {
            Map<String, Object> body = Map.of(
                    "sender", Map.of("name", senderName, "email", senderEmail),
                    "to", List.of(Map.of("email", to)),
                    "subject", subject,
                    "htmlContent", htmlContent
            );

            restClient.post()
                    .uri("/smtp/email")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            
            // SEC-9 (P2): destinatario enmascarado; el mensaje de la excepcion puede incluir el cuerpo de la respuesta.
            log.info("Email enviado exitosamente a {} mediante Brevo", com.havita.common.logging.SafeLog.maskEmail(to));
        } catch (Exception e) {
            log.error("Error al enviar email a {} mediante Brevo: {}", com.havita.common.logging.SafeLog.maskEmail(to),
                    com.havita.common.logging.SafeLog.errorType(e));
            // Dependiendo de tu logica, podrias lanzar una excepcion o solo loguear el error
        }
    }
}
