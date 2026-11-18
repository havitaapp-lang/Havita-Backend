package com.havita.security;

import com.havita.modules.organization.domain.Tenant;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    /**
     * SEC-9: opcional porque no existe en los tests @WebMvcTest, que no cargan repositorios; sin el, ningun token
     * concede permisos (falla cerrado).
     */
    private final org.springframework.beans.factory.ObjectProvider<AccessEvaluator> accessEvaluator;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        try {
            if (jwtService.validateToken(token)) {
                Claims claims = jwtService.parseToken(token);
                String userId = claims.getSubject();
                
                // Get requested tenant from header
                String requestedTenantId = request.getHeader("X-Tenant-ID");
                
                // SEC-9: el JWT solo identifica (sub). Rol, alcance, suspension de la comunidad y acceso vigente de
                // RESIDENT se deciden contra PostgreSQL en AccessEvaluator (la misma decision que usan las URLs firmadas
                // de archivos). Sin evaluador (tests @WebMvcTest sin repositorios) ningun token concede permisos.
                AccessEvaluator evaluator = accessEvaluator.getIfAvailable();
                AccessEvaluator.Outcome outcome = evaluator == null ? new AccessEvaluator.Anonymous("NO_EVALUATOR")
                        : evaluator.evaluate(parseUuidSafely(userId), claims.get("email", String.class),
                                parseUuidSafely(requestedTenantId));

                if (outcome instanceof AccessEvaluator.TenantBlocked blocked) {
                    Tenant tenant = blocked.tenant();
                    log.warn("Acceso denegado: el tenant {} está suspendido.", tenant.getId());
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    Map<String, Object> errBody = Map.of(
                            "success", false,
                            "error", Map.of(
                                    "code", "TENANT_SUSPENDED",
                                    "message", "Esta copropiedad se encuentra suspendida temporalmente por administración." +
                                            (tenant.getSuspendedReason() != null ? " Motivo: " + tenant.getSuspendedReason() : "")
                            )
                    );
                    new ObjectMapper().writeValue(response.getOutputStream(), errBody);
                    return;
                } else if (outcome instanceof AccessEvaluator.Anonymous anonymous) {
                    log.warn("Token valido sin acceso vigente a la comunidad solicitada ({}): se trata como anonimo.", anonymous.reason());
                } else if (outcome instanceof AccessEvaluator.Granted granted) {
                    EffectiveAccess access = granted.access();
                    if (access.tenantId() != null) {
                        TenantContextHolder.setTenantId(access.tenantId());
                    }
                    String role = access.role().name();
                    List<SimpleGrantedAuthority> authorities = access.global()
                            ? List.of(new SimpleGrantedAuthority("ROLE_" + role), new SimpleGrantedAuthority(EffectiveAccess.GLOBAL_SCOPE))
                            : List.of(new SimpleGrantedAuthority("ROLE_" + role));
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userId, null, authorities);
                    authentication.setDetails(access);

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (Exception e) {
            // SEC-9 (P2): solo el tipo; el mensaje de la excepcion podria incluir contenido del token o de la consulta.
            log.warn("Fallo de autenticación JWT o autorización de tenant: {}", com.havita.common.logging.SafeLog.errorType(e));
            SecurityContextHolder.clearContext();
            TenantContextHolder.clear();
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private UUID parseUuidSafely(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
