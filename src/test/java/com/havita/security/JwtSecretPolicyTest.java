package com.havita.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fase 2 / P7-E — politica del secreto JWT. Valores sinteticos generados en la prueba; ninguno real. */
class JwtSecretPolicyTest {

    /** 48 caracteres, 30+ distintos: sintetico, no es ninguna clave real. */
    private static final String STRONG = "Zq7#pL2!vX9@mN4$kR8%tW1^bY6&cD3*fH5(gJ0)sA-uE+iO";
    private static final String WEAK_REPEATED = "abababababababababababababababababababab";

    @Test
    @DisplayName("local sin JWT_SECRET: se acepta el valor de desarrollo (con aviso); demasiado corto nunca")
    void local() {
        MockEnvironment env = new MockEnvironment();
        assertThat(JwtSecretPolicy.validate(STRONG, env)).isEqualTo(JwtSecretPolicy.Outcome.DEV_DEFAULT_ACCEPTED_LOCALLY);
        assertThatThrownBy(() -> JwtSecretPolicy.validate("q7Zx", env)).hasMessageContaining("32 bytes").hasMessageNotContaining("q7Zx");
        assertThatThrownBy(() -> JwtSecretPolicy.validate(" ", env)).isInstanceOf(IllegalStateException.class);
        env.setProperty("JWT_SECRET", STRONG);
        assertThat(JwtSecretPolicy.validate(STRONG, env)).isEqualTo(JwtSecretPolicy.Outcome.OK);
    }

    @Test
    @DisplayName("Railway, perfil prod/staging o exigencia explicita: sin JWT_SECRET o predecible, NO arranca; mensajes sin el valor")
    void deployed() {
        for (MockEnvironment env : new MockEnvironment[]{
                new MockEnvironment().withProperty("RAILWAY_ENVIRONMENT", "production"),
                new MockEnvironment().withProperty("RAILWAY_PROJECT_ID", "p"),
                new MockEnvironment().withProperty("havita.security.require-strong-jwt-secret", "true"),
                profile("prod"), profile("staging")}) {
            assertThatThrownBy(() -> JwtSecretPolicy.validate(STRONG, env))
                    .hasMessageContaining("JWT_SECRET").hasMessageNotContaining(STRONG);
            env.setProperty("JWT_SECRET", WEAK_REPEATED);
            assertThatThrownBy(() -> JwtSecretPolicy.validate(WEAK_REPEATED, env))
                    .hasMessageContaining("predecible").hasMessageNotContaining(WEAK_REPEATED);
            env.setProperty("JWT_SECRET", STRONG);
            assertThat(JwtSecretPolicy.validate(STRONG, env)).isEqualTo(JwtSecretPolicy.Outcome.OK);
        }
    }

    private static MockEnvironment profile(String p) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(p);
        return env;
    }
}
