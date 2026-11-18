package com.havita.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Fase 2 / E1: orden estable y natural del selector de unidades (sin base de datos). */
class ResidentUnitResolverOrderTest {

    private static ResidentUnitResolver.UnitOption unit(String structure, String name, String id) {
        return new ResidentUnitResolver.UnitOption(UUID.fromString(id), null, name, name, "Apartamento", "OCCUPIED", null,
                structure == null ? null : UUID.randomUUID(), structure, structure == null ? null : "Torre",
                List.of("Propietario"), false, name, name, UUID.randomUUID());
    }

    @Test
    @DisplayName("comparacion natural: los numeros se comparan como numeros, sin distinguir mayusculas")
    void naturalCompare() {
        assertThat(ResidentUnitResolver.naturalCompare("Torre 2", "Torre 10")).isNegative();
        assertThat(ResidentUnitResolver.naturalCompare("Apto 201", "Apto 1001")).isNegative();
        assertThat(ResidentUnitResolver.naturalCompare("Casa 09", "Casa 9")).isZero();
        assertThat(ResidentUnitResolver.naturalCompare("bloque a", "Bloque B")).isNegative();
        assertThat(ResidentUnitResolver.naturalCompare("Torre 1", "Torre 1A")).isNegative();
    }

    @Test
    @DisplayName("orden del selector: estructura natural, unidad natural, sin estructura al final, id como desempate")
    void selectorOrder() {
        List<ResidentUnitResolver.UnitOption> units = new ArrayList<>(List.of(
                unit(null, "Casa 3", "00000000-0000-0000-0000-000000000005"),
                unit("Torre 10", "Apto 101", "00000000-0000-0000-0000-000000000004"),
                unit("Torre 2", "Apto 1001", "00000000-0000-0000-0000-000000000003"),
                unit("Torre 2", "Apto 201", "00000000-0000-0000-0000-000000000002"),
                unit(null, "Casa 3", "00000000-0000-0000-0000-000000000001")));
        units.sort(ResidentUnitResolver.UNIT_ORDER);
        assertThat(units).extracting(u -> u.propertyId().toString().substring(35)).containsExactly("2", "3", "4", "1", "5");
    }
}
