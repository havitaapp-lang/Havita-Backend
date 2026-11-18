package com.havita.modules.organization.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Solicitud publica de demostracion. SEC-9-9: todos los campos de texto tienen longitud maxima y excluyen caracteres de
 * control y los delimitadores de HTML; aun asi, el controlador escapa todo lo que inserta en los correos.
 */
public record DemoRequestDto(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(min = 2, max = 120, message = "El nombre debe tener entre 2 y 120 caracteres")
        @Pattern(regexp = DemoRequestDto.SAFE_TEXT, message = "El nombre contiene caracteres no permitidos")
        String name,

        @NotBlank(message = "El nombre de la copropiedad es obligatorio")
        @Size(min = 2, max = 150, message = "El nombre de la copropiedad debe tener entre 2 y 150 caracteres")
        @Pattern(regexp = DemoRequestDto.SAFE_TEXT, message = "El nombre de la copropiedad contiene caracteres no permitidos")
        String communityName,

        @Size(max = 40, message = "El número de unidades no puede exceder 40 caracteres")
        @Pattern(regexp = DemoRequestDto.SAFE_TEXT, message = "El número de unidades contiene caracteres no permitidos")
        String units,

        @NotBlank(message = "El correo electrónico es obligatorio")
        @Email(regexp = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$", message = "El formato de correo es inválido")
        @Size(max = 150, message = "El correo no puede exceder 150 caracteres")
        String email,

        @NotBlank(message = "El teléfono de contacto es obligatorio")
        @Pattern(regexp = "^\\+?[0-9 ()-]{7,30}$", message = "El teléfono solo puede contener dígitos, espacios, paréntesis y guiones")
        String phone,

        @Size(max = 60, message = "El rol no puede exceder 60 caracteres")
        @Pattern(regexp = DemoRequestDto.SAFE_TEXT, message = "El rol contiene caracteres no permitidos")
        String role,

        @Size(max = 60, message = "El plan no puede exceder 60 caracteres")
        @Pattern(regexp = DemoRequestDto.SAFE_TEXT, message = "El plan contiene caracteres no permitidos")
        String plan,

        @Size(max = 1000, message = "Las notas no pueden exceder 1000 caracteres")
        String notes
) {
    /** Letras (incluidas tildes), digitos, espacios y puntuacion basica; sin < > " ` ni caracteres de control. */
    public static final String SAFE_TEXT = "^[\\p{L}\\p{N} .,'/&()+#-]*$";
}
