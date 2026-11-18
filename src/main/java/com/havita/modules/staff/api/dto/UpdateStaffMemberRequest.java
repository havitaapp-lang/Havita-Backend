package com.havita.modules.staff.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Cuerpo de {@code PUT /api/v1/staff/{id}}: datos del colaborador (las mismas reglas que al crearlo). El acceso al
 * sistema se gestiona aparte en {@code PUT /api/v1/staff/{id}/access} y el estado en {@code POST /{id}/status}.
 */
public record UpdateStaffMemberRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 100, message = "El nombre no puede exceder 100 caracteres")
        String firstName,

        @NotBlank(message = "El apellido es obligatorio")
        @Size(max = 100, message = "El apellido no puede exceder 100 caracteres")
        String lastName,

        @Size(max = 20, message = "El tipo de documento no puede exceder 20 caracteres")
        String documentType,

        @NotBlank(message = "El número de documento es obligatorio")
        @Size(max = 50, message = "El documento no puede exceder 50 caracteres")
        String documentNumber,

        @Size(max = 50, message = "El teléfono no puede exceder 50 caracteres")
        String phone,

        @Size(max = 150, message = "El email no puede exceder 150 caracteres")
        String email,

        @NotBlank(message = "El cargo o posición es obligatorio")
        @Size(max = 100, message = "El cargo no puede exceder 100 caracteres")
        String position,

        @Size(max = 50, message = "El área no puede exceder 50 caracteres")
        String area,

        @Size(max = 50, message = "El tipo de contrato no puede exceder 50 caracteres")
        String contractType,

        @Size(max = 150, message = "La empresa proveedora no puede exceder 150 caracteres")
        String company,

        LocalDate startDate,

        @Size(max = 100, message = "La portería asignada no puede exceder 100 caracteres")
        String assignedGate,

        @Size(max = 500, message = "Las observaciones no pueden exceder 500 caracteres")
        String observations
) {}
