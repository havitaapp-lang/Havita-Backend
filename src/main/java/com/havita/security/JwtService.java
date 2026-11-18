package com.havita.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey secretKey;
    private final long accessExpirationMinutes;

    public JwtService(
            @Value("${app.security.jwt.secret}") String secret,
            @Value("${app.security.jwt.access-expiration-minutes:1440}") long accessExpirationMinutes,
            org.springframework.core.env.Environment environment
    ) {
        // P7-E: en un entorno desplegado se exige JWT_SECRET propio y robusto; nunca el valor de desarrollo.
        if (JwtSecretPolicy.validate(secret, environment) == JwtSecretPolicy.Outcome.DEV_DEFAULT_ACCEPTED_LOCALLY) {
            org.slf4j.LoggerFactory.getLogger(JwtService.class).warn(
                    "JWT_SECRET no definido: se usa el secreto de DESARROLLO (solo valido en local; nunca en un despliegue)");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpirationMinutes = accessExpirationMinutes;
    }

    public String generateAccessToken(UUID userId, String email, java.util.List<com.havita.modules.identity.domain.Membership> memberships, String propertyUnit) {
        Instant now = Instant.now();
        Instant expiry = now.plus(accessExpirationMinutes, ChronoUnit.MINUTES);

        java.util.List<Map<String, String>> membershipsClaim = memberships.stream()
                .map(m -> Map.of(
                        "tenantId", m.getTenantId() != null ? m.getTenantId().toString() : "",
                        "role", m.getRole().name()
                ))
                .toList();

        return Jwts.builder()
                .subject(userId.toString())
                .claims(Map.of(
                        "email", email,
                        "memberships", membershipsClaim,
                        "propertyUnit", propertyUnit != null ? propertyUnit : ""
                ))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
