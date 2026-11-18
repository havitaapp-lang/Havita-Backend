package com.havita.modules.resident.domain;

import com.havita.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fase 2 / P0-C: valores de residents.type aceptados por la aplicacion. */
class ResidentTypesTest {

    @ParameterizedTest(name = "''{0}'' -> {1}")
    @CsvSource({
            "Propietario,Propietario", "Arrendatario,Arrendatario", "Residente,Residente",
            "propietario,Propietario", "arrendatario,Arrendatario", "residente,Residente",
            "ARRENDATARIO,Arrendatario", "'  Residente ',Residente"})
    @DisplayName("los tres tipos reales se aceptan y se guardan con la grafia canonica (solo mayusculas y espacios)")
    void canonicalSpelling(String raw, String expected) {
        assertThat(ResidentTypes.canonicalOrNull(raw)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "''{0}'' -> 400")
    @ValueSource(strings = {"OWNER", "TENANT", "RESIDENT", "owner", "tenant", "resident", "", "   ", "desconocido",
            "Inquilino", "Propietaria", "Arrendatario2"})
    @DisplayName("valores vacios, en ingles o desconocidos se rechazan sin convertirlos en otro tipo")
    void invalidRejected(String raw) {
        assertThatThrownBy(() -> ResidentTypes.canonicalOrNull(raw))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INVALID_RESIDENT_TYPE");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    @DisplayName("null significa 'no enviado': el llamador aplica su valor por defecto")
    void nullIsAbsent() {
        assertThat(ResidentTypes.canonicalOrNull(null)).isNull();
        assertThat(ResidentTypes.canonicalOrDefault(null, ResidentTypes.OWNER)).isEqualTo("Propietario");
        assertThatThrownBy(() -> ResidentTypes.canonicalOrDefault("", ResidentTypes.OWNER)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    @DisplayName("una invitacion del hogar solo admite Arrendatario o Residente")
    void householdInvitable() {
        assertThat(ResidentTypes.HOUSEHOLD_INVITABLE).containsExactlyInAnyOrder("Arrendatario", "Residente");
    }
}
