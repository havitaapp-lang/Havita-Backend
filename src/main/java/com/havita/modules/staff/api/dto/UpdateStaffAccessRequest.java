package com.havita.modules.staff.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code PUT /api/v1/staff/{id}/access}.
 *
 * @param enabled    true: el colaborador tiene acceso (se crea la cuenta si no la tiene); false: se desactiva su
 *                   membresia en esta comunidad (la cuenta no se borra)
 * @param loginEmail correo de inicio de sesion (obligatorio al crear la cuenta; para cambiarlo, la cuenta no debe tener
 *                   acceso en otra comunidad)
 * @param password   contrasena nueva (obligatoria al crear la cuenta; opcional despues). Nunca se devuelve
 * @param systemRole SECURITY (porteria) o STAFF (personal operativo)
 */
public record UpdateStaffAccessRequest(
        @NotNull(message = "Indica si el colaborador tiene acceso al sistema")
        Boolean enabled,

        @Email(message = "El correo de acceso no es válido")
        @Size(max = 150, message = "El correo de acceso no puede exceder 150 caracteres")
        String loginEmail,

        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres")
        String password,

        @Size(max = 30, message = "El rol no es válido")
        String systemRole
) {}
