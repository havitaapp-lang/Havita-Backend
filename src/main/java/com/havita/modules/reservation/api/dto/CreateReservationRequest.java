package com.havita.modules.reservation.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import jakarta.validation.constraints.Size;
import java.util.UUID;

import jakarta.validation.constraints.Size;
public record CreateReservationRequest(
        @NotNull(message = "El ID de la zona común es obligatorio")
        UUID commonZoneId,

        @NotNull(message = "El ID del inmueble es obligatorio")
        UUID propertyId,

        UUID residentId,

        @NotNull(message = "La fecha de reserva es obligatoria")
        LocalDate reservationDate,

        @NotNull(message = "La hora de inicio es obligatoria")
        LocalTime startTime,

        @NotNull(message = "La hora de finalización es obligatoria")
        LocalTime endTime,

        @Min(value = 1, message = "Debe haber al menos 1 invitado")
        Integer guestsCount,

        @Size(max = 500, message = "Las observaciones no pueden exceder 500 caracteres")
        String observations
) {}

