package com.havita.modules.organization.api;

import com.havita.common.api.ApiResponse;
import com.havita.common.email.EmailService;
import com.havita.modules.organization.api.dto.DemoRequestDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/public/demo-requests")
@RequiredArgsConstructor
@Tag(name = "Public Demo Requests", description = "Endpoints públicos para captación de clientes y solicitudes de demostración")
public class PublicDemoController {

    private final EmailService emailService;

    /*
     * SEC-9-9: el formulario es publico y envia correos. Limites (en memoria, por instancia):
     *  - por cliente (direccion remota): 5 solicitudes por hora; por encima, 429 sin enviar nada;
     *  - por destinatario: 1 confirmacion cada 24 h (el resto de solicitudes solo avisan al equipo comercial);
     *  - global: 60 confirmaciones por hora, contra el abuso distribuido.
     * Detras de un proxy, getRemoteAddr() es la IP del proxy (el limite por cliente pasa a ser global) hasta configurar
     * server.forward-headers-strategy con proxies de confianza; X-Forwarded-For no se lee directamente (falsificable).
     */
    private final com.havita.common.api.SlidingWindowRateLimiter perClient =
            new com.havita.common.api.SlidingWindowRateLimiter(5, java.time.Duration.ofHours(1), 10_000, java.time.Clock.systemUTC());
    private final com.havita.common.api.SlidingWindowRateLimiter perRecipient =
            new com.havita.common.api.SlidingWindowRateLimiter(1, java.time.Duration.ofHours(24), 10_000, java.time.Clock.systemUTC());
    private final com.havita.common.api.SlidingWindowRateLimiter globalConfirmations =
            new com.havita.common.api.SlidingWindowRateLimiter(60, java.time.Duration.ofHours(1), 1, java.time.Clock.systemUTC());

    /** Escapa para HTML (contenido y atributos). */
    private static String h(String value) {
        return org.springframework.web.util.HtmlUtils.htmlEscape(value == null ? "" : value, "UTF-8");
    }

    /** Asunto sin saltos de linea ni caracteres de control, acotado. */
    private static String subjectSafe(String value) {
        String clean = value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").trim();
        return clean.length() > 120 ? clean.substring(0, 120) : clean;
    }

    @PostMapping
    @Operation(summary = "Registrar solicitud de demostración comercial",
            description = "Recibe los datos del prospecto y despacha notificación por correo a demo@havita.com.co y confirmación al solicitante.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submitDemoRequest(@Valid @RequestBody DemoRequestDto dto,
                                                                             jakarta.servlet.http.HttpServletRequest request) {
        if (!perClient.tryAcquire(String.valueOf(request.getRemoteAddr()))) {
            log.warn("Solicitud de demostración rechazada por frecuencia (cliente)");
            return ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ApiResponse<>(false, Map.<String, Object>of("code", "RATE_LIMITED"),
                            "Demasiadas solicitudes. Intenta de nuevo más tarde.", java.time.OffsetDateTime.now()));
        }
        // SEC-9 (P2): endpoint publico; nombre y telefono no van al log y el correo va enmascarado (los datos completos
        // llegan al equipo comercial por correo).
        log.info("Nueva solicitud de demostración recibida: correo='{}', unidades='{}', rol='{}', plan='{}'",
                com.havita.common.logging.SafeLog.maskEmail(dto.email()), dto.units(), dto.role(), dto.plan());

        String bogotaTime = ZonedDateTime.now(ZoneId.of("America/Bogota"))
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss (z)"));

        String selectedPlan = (dto.plan() != null && !dto.plan().isBlank()) ? dto.plan() : "No especificado";
        String unitsCount = (dto.units() != null && !dto.units().isBlank()) ? dto.units() : "No especificado";
        String roleName = (dto.role() != null && !dto.role().isBlank()) ? dto.role() : "Administrador / Representante";

        // 1. Email para el equipo comercial de Havita (demo@havita.com.co)
        String adminSubject = subjectSafe("🔔 Nueva Solicitud de Demo: " + dto.communityName() + " (" + unitsCount + " unidades)");
        String adminHtml = String.format("""
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <style>
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; margin: 0; padding: 24px; color: #1e293b; }
                .card { max-width: 600px; margin: 0 auto; background: #ffffff; border-radius: 16px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.05); }
                .header { background: #0f172a; padding: 24px 32px; color: #ffffff; }
                .header h1 { margin: 0; font-size: 20px; font-weight: 700; color: #ffffff; }
                .header p { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
                .content { padding: 32px; }
                .field { margin-bottom: 18px; }
                .label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.05em; color: #64748b; font-weight: 600; margin-bottom: 4px; }
                .value { font-size: 15px; font-weight: 600; color: #0f172a; }
                .value-highlight { font-size: 16px; color: #2563eb; font-weight: 700; }
                .badge { display: inline-block; padding: 4px 10px; border-radius: 9999px; background: #eff6ff; color: #2563eb; font-size: 12px; font-weight: 600; }
                .footer { background: #f1f5f9; padding: 18px 32px; font-size: 12px; color: #64748b; text-align: center; border-top: 1px solid #e2e8f0; }
                .cta { display: inline-block; margin-top: 20px; padding: 10px 20px; background: #2563eb; color: #ffffff; text-decoration: none; border-radius: 8px; font-weight: 600; font-size: 14px; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="header">
                  <h1>Nueva Solicitud de Demostración Comercial</h1>
                  <p>Recibida a través de la landing page pública de Havita</p>
                </div>
                <div class="content">
                  <div class="field">
                    <div class="label">Copropiedad / Edificio</div>
                    <div class="value-highlight">%s</div>
                  </div>
                  <div class="field">
                    <div class="label">Nombre del Contacto</div>
                    <div class="value">%s</div>
                  </div>
                  <div class="field">
                    <div class="label">Rol en la Copropiedad</div>
                    <div class="value">%s</div>
                  </div>
                  <div class="field">
                    <div class="label">Número de Unidades</div>
                    <div class="value"><span class="badge">%s</span></div>
                  </div>
                  <div class="field">
                    <div class="label">Plan de Interés</div>
                    <div class="value"><span class="badge">%s</span></div>
                  </div>
                  <div class="field">
                    <div class="label">Teléfono / WhatsApp</div>
                    <div class="value"><a href="https://wa.me/%s" style="color: #10b981; text-decoration: none; font-weight: 700;">%s (Clic para WhatsApp)</a></div>
                  </div>
                  <div class="field">
                    <div class="label">Correo Electrónico</div>
                    <div class="value"><a href="mailto:%s" style="color: #2563eb; text-decoration: none;">%s</a></div>
                  </div>
                  <div class="field">
                    <div class="label">Fecha y Hora de Solicitud</div>
                    <div class="value" style="font-size: 13px; color: #64748b; font-weight: normal;">%s</div>
                  </div>
                  <div style="margin-top: 24px; padding-top: 16px; border-top: 1px solid #e2e8f0;">
                    <a href="mailto:%s?subject=Havita%%20-%%20Demostraci%%C3%%B3n%%20para%%20%s" style="color:#FFFFFF" class="cta">Responder al Prospecto por Correo</a>
                  </div>
                </div>
                <div class="footer">
                  Havita B2B Lead Notification System • Santiago de Cali, Colombia
                </div>
              </div>
            </body>
            </html>
            """,
                // SEC-9-9: todo valor del formulario se escapa; los de URL, ademas, se codifican.
                h(dto.communityName()),
                h(dto.name()),
                h(roleName),
                h(unitsCount),
                h(selectedPlan),
                dto.phone().replaceAll("[^0-9]", ""),
                h(dto.phone()),
                h(java.net.URLEncoder.encode(dto.email(), java.nio.charset.StandardCharsets.UTF_8).replace("%40", "@")),
                h(dto.email()),
                h(bogotaTime),
                h(java.net.URLEncoder.encode(dto.email(), java.nio.charset.StandardCharsets.UTF_8).replace("%40", "@")),
                h(java.net.URLEncoder.encode(dto.communityName(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"))
        );

        // Envío al correo comercial de Havita
        try {
            emailService.sendEmail("demo@havita.com.co", adminSubject, adminHtml);
            log.info("Correo de notificación de demo despachado a demo@havita.com.co");
        } catch (Exception e) {
            log.error("Fallo al enviar correo a demo@havita.com.co: {}", com.havita.common.logging.SafeLog.errorType(e));
        }

        // 2. Email de confirmación para el cliente solicitante.
        // SEC-9-9: el destinatario lo escribe quien envia el formulario, asi que este correo NO incluye ningun texto del
        // formulario (antes llevaba nombre y copropiedad sin escapar: servia para enviar contenido arbitrario a terceros
        // con remitente Havita). Ademas: una confirmacion por destinatario cada 24 h y un tope global por hora.
        String recipientKey = dto.email().trim().toLowerCase(java.util.Locale.ROOT);
        if (!perRecipient.tryAcquire(recipientKey) || !globalConfirmations.tryAcquire("all")) {
            log.info("Confirmación de demo omitida por límite de frecuencia: {}", com.havita.common.logging.SafeLog.maskEmail(dto.email()));
        } else try {
            String clientSubject = "¡Hemos recibido tu solicitud de demostración en Havita!";
            String clientHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="utf-8">
                  <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f8fafc; margin: 0; padding: 24px; color: #1e293b; }
                    .card { max-width: 560px; margin: 0 auto; background: #ffffff; border-radius: 16px; border: 1px solid #e2e8f0; overflow: hidden; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.05); }
                    .header { background: #0f172a; padding: 28px 32px; color: #ffffff; text-align: center; }
                    .header h1 { margin: 0; font-size: 22px; font-weight: 700; color: #ffffff; }
                    .content { padding: 32px; font-size: 15px; line-height: 1.6; color: #334155; }
                    .highlight { background: #eff6ff; border-left: 4px solid #2563eb; padding: 14px 18px; margin: 20px 0; border-radius: 0 8px 8px 0; font-weight: 500; }
                    .footer { background: #f1f5f9; padding: 20px 32px; font-size: 12px; color: #64748b; text-align: center; border-top: 1px solid #e2e8f0; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <div class="header">
                      <h1>¡Hola!</h1>
                    </div>
                    <div class="content">
                      <p>Gracias por tu interés en <strong>Havita</strong> para la administración de tu copropiedad.</p>
                      <div class="highlight">
                        Tu solicitud ha sido asignada a uno de nuestros asesores especialistas en copropiedades. Te contactaremos muy pronto vía WhatsApp o correo electrónico para coordinar tu demostración guiada sin compromiso.
                      </div>
                      <p>Mientras tanto, si deseas comunicarte directamente con nuestro equipo de atención comercial, puedes escribirnos a <a href="mailto:demo@havita.com.co" style="color: #2563eb;">demo@havita.com.co</a> o contactarnos vía WhatsApp.</p>
                      <p style="margin-top: 24px; font-weight: 600; color: #0f172a;">Equipo Havita<br><span style="font-weight: normal; font-size: 13px; color: #64748b;">Tecnología para comunidades residenciales modernas</span></p>
                    </div>
                    <div class="footer">
                      Havita • Santiago de Cali, Colombia • www.havita.com.co
                    </div>
                  </div>
                </body>
                </html>
                """;
            emailService.sendEmail(dto.email(), clientSubject, clientHtml);
            log.info("Correo de confirmación despachado al solicitante: {}", com.havita.common.logging.SafeLog.maskEmail(dto.email()));
        } catch (Exception e) {
            log.warn("No se pudo despachar correo de confirmación al solicitante {}: {}",
                    com.havita.common.logging.SafeLog.maskEmail(dto.email()), com.havita.common.logging.SafeLog.errorType(e));
        }

        return ResponseEntity.ok(ApiResponse.ok(
                Map.of("received", true, "notified", "demo@havita.com.co"),
                "Solicitud de demostración recibida exitosamente. Un asesor te contactará pronto."
        ));
    }
}
