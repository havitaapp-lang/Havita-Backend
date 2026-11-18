package com.havita.common.config;

import com.havita.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class BaseSecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /** SEC-9-9: la documentacion OpenAPI se publica solo si el entorno la habilita (desactivada por defecto). */
    @org.springframework.beans.factory.annotation.Value("${springdoc.api-docs.enabled:false}")
    private boolean apiDocsEnabled;

    private static void configurePublicAndDefault(
            org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth) {
        // Endpoints públicos de autenticación
        auth.requestMatchers(
                "/api/v1/auth/login", "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password",
                "/api/v1/auth/refresh",
                "/api/v1/sync/stream",
                "/api/v1/push-subscriptions/public-key",
                // P8-B: recibos del service worker (sin sesion). Los autoriza un nonce de 128 bits de un solo uso
                // que viaja cifrado en el aviso; responden siempre 204, no devuelven datos y estan limitados.
                "/api/v1/intercom/push-receipts",
                // SEC-9-9: URL firmada de vida corta para <img>; el controlador valida firma y caducidad y revalida
                // contra PostgreSQL el acceso del usuario firmante en cada peticion.
                "/api/v1/files/signed"
        ).permitAll();
        // Endpoints públicos de resolución de tenants y branding
        auth.requestMatchers("/api/v1/public/**").permitAll();
        // Cualquier otra petición requiere autenticación
        auth.anyRequest().authenticated();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "http://*.localhost:*",
                "https://*.localhost:*",
                "http://192.168.*:*",
                "https://192.168.*:*",
                "http://*.sslip.io:*",
                "https://*.sslip.io:*",
                "http://*.nip.io:*",
                "https://*.nip.io:*",
                "http://10.*:*",
                "https://10.*:*",
                "http://172.16.*:*",
                "https://172.16.*:*"
        ));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization", "X-Tenant-ID", "Content-Disposition"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Hardening de Cabeceras HTTP de Seguridad
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                        .xssProtection(HeadersConfigurer.XXssConfig::disable)
                        .contentTypeOptions(HeadersConfigurer.ContentTypeOptionsConfig::disable)
                )
                .authorizeHttpRequests(auth -> {
                    // Health de la aplicacion y de la plataforma (HEALTHCHECK del Dockerfile: /actuator/health).
                    // SEC-9-9: el resto de Actuator ya no es publico (antes /actuator/** exponia metrics).
                    auth.requestMatchers("/api/v1/health/**", "/actuator/health", "/actuator/health/**").permitAll();
                    // SEC-9-9: OpenAPI y Swagger UI solo existen si se habilitan por entorno (perfil local o
                    // SPRINGDOC_ENABLED=true); solo entonces son accesibles sin sesion.
                    if (apiDocsEnabled) {
                        auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll();
                    }
                    configurePublicAndDefault(auth);
                });
        http
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"success\":false,\"message\":\"Sesión expirada o no autenticada\"}");
                        })
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
