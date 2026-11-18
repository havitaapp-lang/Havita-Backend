package com.havita.security;

import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Fase 2 / P7-E — politica del secreto de firma de los JWT (y de las URLs firmadas, que derivan su clave de el).
 *
 * <ul>
 *   <li><b>Entorno desplegado</b> (Railway, perfiles {@code prod}/{@code production}/{@code staging} o
 *       {@code havita.security.require-strong-jwt-secret=true}): el secreto DEBE venir de {@code JWT_SECRET} (nunca el
 *       valor por defecto de {@code application.yml}), con al menos {@value #MIN_BYTES} bytes y {@value #MIN_DISTINCT}
 *       caracteres distintos. Si no, la aplicacion NO arranca.</li>
 *   <li><b>Local</b>: se admite el valor de desarrollo, con un aviso (sin el valor) en el registro.</li>
 * </ul>
 *
 * Los mensajes nunca incluyen el secreto ni partes de el.
 */
public final class JwtSecretPolicy {

    public static final int MIN_BYTES = 32;    // HS256: clave de 256 bits como minimo
    public static final int MIN_DISTINCT = 16; // evita secretos triviales ("aaaa...", "0123012301...")
    private static final Set<String> DEPLOYED_PROFILES = Set.of("prod", "production", "staging");
    private static final String[] DEPLOYMENT_MARKERS = {"RAILWAY_ENVIRONMENT", "RAILWAY_ENVIRONMENT_NAME", "RAILWAY_PROJECT_ID"};

    public enum Outcome { OK, DEV_DEFAULT_ACCEPTED_LOCALLY }

    private JwtSecretPolicy() {
    }

    /** Valida el secreto con la informacion del entorno de Spring. */
    public static Outcome validate(String secret, Environment env) {
        boolean explicit = notBlank(env.getProperty("JWT_SECRET"));
        boolean deployed = "true".equalsIgnoreCase(env.getProperty("havita.security.require-strong-jwt-secret"))
                || Arrays.stream(env.getActiveProfiles()).map(p -> p.toLowerCase(Locale.ROOT)).anyMatch(DEPLOYED_PROFILES::contains)
                || Arrays.stream(DEPLOYMENT_MARKERS).anyMatch(m -> notBlank(env.getProperty(m)));
        return validate(secret, explicit, deployed);
    }

    static Outcome validate(String secret, boolean explicitlyConfigured, boolean deployed) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("app.security.jwt.secret no esta configurado (JWT_SECRET)");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_BYTES) {
            throw new IllegalStateException("JWT_SECRET demasiado corto: se requieren al menos " + MIN_BYTES + " bytes");
        }
        if (deployed) {
            if (!explicitlyConfigured) {
                throw new IllegalStateException("Entorno desplegado sin JWT_SECRET: no se admite el valor por defecto de desarrollo");
            }
            if (secret.chars().distinct().count() < MIN_DISTINCT) {
                throw new IllegalStateException("JWT_SECRET demasiado predecible: se requieren al menos " + MIN_DISTINCT
                        + " caracteres distintos (use un valor aleatorio de 32+ bytes)");
            }
            return Outcome.OK;
        }
        return explicitlyConfigured ? Outcome.OK : Outcome.DEV_DEFAULT_ACCEPTED_LOCALLY;
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }
}
