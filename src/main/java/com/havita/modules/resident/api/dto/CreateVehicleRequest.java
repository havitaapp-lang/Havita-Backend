package com.havita.modules.resident.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateVehicleRequest(
        @NotNull(message = "El ID del inmueble es obligatorio")
        UUID propertyId,

        UUID residentId,

        @NotBlank(message = "La placa es obligatoria")
        @Size(max = 20, message = "La placa no puede exceder 20 caracteres")
        String plate,

        @Size(max = 30, message = "El tipo de vehículo no puede exceder 30 caracteres")
        String type,

        @Size(max = 50, message = "La marca no puede exceder 50 caracteres")
        String brand,

        @Size(max = 50, message = "El modelo no puede exceder 50 caracteres")
        String model,

        @Size(max = 30, message = "El color no puede exceder 30 caracteres")
        String color,

        @Size(max = 50, message = "El parqueadero asignado no puede exceder 50 caracteres")
        String parkingSpot
) {}
