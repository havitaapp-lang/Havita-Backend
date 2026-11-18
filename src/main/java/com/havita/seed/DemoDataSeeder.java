package com.havita.seed;
import com.havita.modules.identity.domain.UserStatus;
import com.havita.modules.identity.domain.MembershipStatus;

import com.havita.modules.featureflag.domain.FeatureFlag;
import com.havita.modules.featureflag.infrastructure.FeatureFlagRepository;
import com.havita.modules.identity.domain.Membership;
import com.havita.modules.identity.domain.RoleType;
import com.havita.modules.identity.domain.User;
import com.havita.modules.identity.infrastructure.MembershipRepository;
import com.havita.modules.identity.infrastructure.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class DemoDataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;
    private final FeatureFlagRepository featureFlagRepository;
    private final com.havita.modules.subscription.infrastructure.SaaSPlanRepository saasPlanRepository;
    private final PasswordEncoder passwordEncoder;
    private final jakarta.persistence.EntityManager entityManager;

    @Value("${havita.bootstrap.super-admin.email:}")
    private String bootstrapSuperAdminEmail;

    @Value("${havita.bootstrap.super-admin.password:}")
    private String bootstrapSuperAdminPassword;

    private static final int MIN_BOOTSTRAP_PASSWORD_LENGTH = 12;

    @Override
    @Transactional
    public void run(String... args) {
        log.info("Iniciando sembrado de datos básicos (sin mock data)...");

        // Las restricciones de nullable de tenant_id ya estan en JPA

        // Las tablas y columnas son creadas por Hibernate con ddl-auto=create-drop,
        // por lo que ya permiten nulos (Integer/Long sin nullable=false).

        seedFeatureFlags();
        seedSaaSPlans();
        seedSuperAdmin();

        log.info("Sembrado completado.");
    }

    private void seedSaaSPlans() {
        if (saasPlanRepository.count() == 0) {
            saasPlanRepository.save(com.havita.modules.subscription.domain.SaaSPlan.builder()
                    .tier("ESSENTIAL")
                    .name("Esencial")
                    .grossPricePerProperty(new java.math.BigDecimal("3000"))
                    .platformPricePerProperty(new java.math.BigDecimal("2000"))
                    .communitySharePerProperty(new java.math.BigDecimal("1000"))
                    .maxProperties(100)
                    .maxStorageBytes(10L * 1024 * 1024 * 1024)
                    .build());
                    
            saasPlanRepository.save(com.havita.modules.subscription.domain.SaaSPlan.builder()
                    .tier("PROFESSIONAL")
                    .name("Profesional")
                    .grossPricePerProperty(new java.math.BigDecimal("3000"))
                    .platformPricePerProperty(new java.math.BigDecimal("2000"))
                    .communitySharePerProperty(new java.math.BigDecimal("1000"))
                    .maxProperties(500)
                    .maxStorageBytes(30L * 1024 * 1024 * 1024)
                    .popular(true)
                    .build());
                    
            saasPlanRepository.save(com.havita.modules.subscription.domain.SaaSPlan.builder()
                    .tier("LARGE")
                    .name("Grandes Comunidades")
                    .grossPricePerProperty(new java.math.BigDecimal("3000"))
                    .platformPricePerProperty(new java.math.BigDecimal("2000"))
                    .communitySharePerProperty(new java.math.BigDecimal("1000"))
                    .maxProperties(1000)
                    .maxStorageBytes(75L * 1024 * 1024 * 1024)
                    .build());
                    
            saasPlanRepository.save(com.havita.modules.subscription.domain.SaaSPlan.builder()
                    .tier("ENTERPRISE")
                    .name("Enterprise")
                    .grossPricePerProperty(new java.math.BigDecimal("0"))
                    .platformPricePerProperty(new java.math.BigDecimal("0"))
                    .communitySharePerProperty(new java.math.BigDecimal("0"))
                    .maxProperties(null)
                    .maxStorageBytes(null)
                    .build());
            log.info("Planes SaaS creados en base de datos.");
        }
    }

    private void seedFeatureFlags() {
        if (featureFlagRepository.count() == 0) {
            featureFlagRepository.save(FeatureFlag.builder()
                    .key("BIOMETRIC_ACCESS")
                    .name("Control de Acceso Biométrico")
                    .description("Permite torniquetes y lectores faciales en portería")
                    .category("Seguridad")
                    .enabledByPlan("ENTERPRISE")
                    .isGlobalEnabled(true)
                    .build());

            featureFlagRepository.save(FeatureFlag.builder()
                    .key("ONLINE_ASSEMBLY_VOTING")
                    .name("Votaciones en Asamblea en Tiempo Real")
                    .description("Permite votaciones ponderadas por coeficiente en vivo")
                    .category("Comunidad")
                    .enabledByPlan("PROFESSIONAL,ENTERPRISE")
                    .isGlobalEnabled(true)
                    .build());

            featureFlagRepository.save(FeatureFlag.builder()
                    .key("WHATSAPP_NOTIFICATIONS")
                    .name("Notificaciones Automáticas por WhatsApp")
                    .description("Envío automático al recibir encomiendas o visitas")
                    .category("Operación")
                    .enabledByPlan("STARTER,PROFESSIONAL,ENTERPRISE")
                    .isGlobalEnabled(true)
                    .build());
        }
    }

    private void seedSuperAdmin() {
        if (bootstrapSuperAdminEmail == null || bootstrapSuperAdminEmail.isBlank() ||
                bootstrapSuperAdminPassword == null || bootstrapSuperAdminPassword.isBlank()) {
            log.warn("Super Admin de bootstrap no configurado o credenciales incompletas; omitiendo creación.");
            return;
        }

        if (bootstrapSuperAdminPassword.length() < MIN_BOOTSTRAP_PASSWORD_LENGTH) {
            log.warn("La contraseña del Super Admin de bootstrap debe tener al menos {} caracteres; omitiendo creación.",
                    MIN_BOOTSTRAP_PASSWORD_LENGTH);
            return;
        }

        String email = bootstrapSuperAdminEmail;
        User superAdmin = userRepository.findByEmailIgnoreCase(email).orElse(null);
        
        if (superAdmin == null) {
            superAdmin = userRepository.save(User.builder()
                    .email(email)
                    .passwordHash(passwordEncoder.encode(bootstrapSuperAdminPassword))
                    .fullName("Super Administrador Global")
                    .phone("+57 300 000 0001")
                    .status(UserStatus.ACTIVE)
                    .build());
            log.info("Super Administrador creado exitosamente.");
        } else {
            log.info("Super Administrador existente detectado; conservando credenciales actuales.");


        }

        if (membershipRepository.findByUserId(superAdmin.getId()).isEmpty()) {
            membershipRepository.save(Membership.builder()
                    .user(superAdmin)
                    .tenantId(null) // Global admin has no specific tenant
                    .role(RoleType.SUPER_ADMIN)
                    .status(MembershipStatus.ACTIVE)
                    .build());
            log.info("Membresía global de Super Admin asignada.");
        }
    }
}
