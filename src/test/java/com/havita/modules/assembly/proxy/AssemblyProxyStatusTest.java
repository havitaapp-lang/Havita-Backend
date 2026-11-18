package com.havita.modules.assembly.proxy;

import com.havita.modules.assembly.domain.AssemblyProxyStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static com.havita.modules.assembly.domain.AssemblyProxyStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Fase 2 / P1: matriz completa de transiciones (la misma que aplica el trigger assembly_proxies_guard de V102). */
class AssemblyProxyStatusTest {

    @Test
    @DisplayName("transiciones permitidas y prohibidas, todas las combinaciones")
    void fullMatrix() {
        Set<String> allowed = Set.of("PENDING_REVIEW>APPROVED", "PENDING_REVIEW>REJECTED", "PENDING_REVIEW>REVOKED",
                "PENDING_REVIEW>EXPIRED", "APPROVED>REVOKED", "APPROVED>USED", "APPROVED>EXPIRED");
        for (AssemblyProxyStatus from : AssemblyProxyStatus.values()) {
            for (AssemblyProxyStatus to : AssemblyProxyStatus.values()) {
                assertThat(from.canTransitionTo(to)).as(from + ">" + to).isEqualTo(allowed.contains(from + ">" + to));
            }
        }
    }

    @Test
    @DisplayName("estados finales y estados que ocupan la representacion de la unidad")
    void finalAndLive() {
        assertThat(EnumSet.allOf(AssemblyProxyStatus.class).stream().filter(AssemblyProxyStatus::isFinal))
                .containsExactlyInAnyOrder(REJECTED, REVOKED, USED, EXPIRED);
        assertThat(AssemblyProxyStatus.LIVE).containsExactlyInAnyOrder(PENDING_REVIEW, APPROVED, USED);
        assertThat(REJECTED.canTransitionTo(APPROVED)).as("rechazado no se aprueba sin un proceso nuevo").isFalse();
        assertThat(USED.canTransitionTo(REVOKED)).as("utilizado no se revoca").isFalse();
        assertThat(EXPIRED.canTransitionTo(APPROVED)).as("vencido no se aprueba").isFalse();
        assertThat(REJECTED.canTransitionTo(USED)).as("rechazado no se utiliza").isFalse();
    }
}
