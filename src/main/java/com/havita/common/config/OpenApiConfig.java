package com.havita.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        final String securitySchemeName = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("Havita SaaS API — Gestión Integral de Propiedad Horizontal")
                        .version("1.0.0")
                        .description("""
                                Backend SaaS Modular Monolith para la administración de conjuntos residenciales, \
                                copropiedades y edificios en Colombia conforme a la Ley 675 de 2001.

                                ### Módulos Incluidos:
                                1. **Identity & Security**: Autenticación JWT y Roles RBAC.
                                2. **Organizations & Tenants**: Multi-tenancy con aislamiento estricto y configuración por copropiedad.
                                3. **Properties & Common Zones**: Estructuras, inmuebles, coeficientes y áreas comunes.
                                4. **Residents & Census**: Censo poblacional, vehículos y mascotas.
                                5. **Access Control & Gatehouse**: Visitas, pases QR, control de accesos y paquetería.
                                6. **Staff & Shifts**: Colaboradores, turnos y control de asistencia con geolocalización.
                                7. **PQRS & Maintenance**: Peticiones, quejas, reclamos, solicitudes y mantenimiento.
                                8. **Reservations & Amenities**: Reserva de amenidades con control de aforo y prevención de solapamientos.
                                9. **Communications & Documents**: Circulares segmentadas y repositorio documental.
                                10. **Assemblies & Voting**: Asambleas presenciales/virtuales con cálculo dinámico de quórum y votación por coeficientes.
                                11. **Accounting & Invoicing**: Facturación, liquidación por coeficiente Ley 675, pagos y estados de cuenta.
                                12. **SaaS Subscriptions & Billing**: Gestión comercial B2B para copropiedades con IVA 19%.
                                13. **Feature Flags & Gating**: Modulación de funciones por plan Starter, Professional o Enterprise.
                                14. **Security Hardening & Audit Logs**: Cabeceras de protección HTTP y bitácora forense inmutable.
                                15. **Real-time & WebSockets**: Chat de portería, notificaciones push y botón de pánico SOS.
                                16. **Export & Reporting**: Descarga oficial de Paz y Salvo en PDF, balances en Excel y censo en CSV.
                                """)
                        .contact(new Contact()
                                .name("Soporte Tecnológico Havita")
                                .email("soporte@havita.com")
                                .url("https://havita.com"))
                        .license(new License()
                                .name("Propiedad Comercial Havita")
                                .url("https://havita.com/licencia")))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Ingrese el token JWT obtenido del endpoint /api/v1/auth/login")));
    }
}
