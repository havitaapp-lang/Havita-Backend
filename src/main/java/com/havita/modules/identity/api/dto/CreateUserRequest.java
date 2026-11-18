package com.havita.modules.identity.api.dto;

import com.havita.modules.identity.domain.RoleType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateUserRequest(
        @NotBlank(message = "El nombre es requerido")
        String fullName,

        @NotBlank(message = "El correo es requerido")
        @Email(message = "Formato de correo invlido")
        String email,

        @NotBlank(message = "La contrasea es requerida")
        String password,

        String phone,

        @NotNull(message = "El rol es requerido")
        RoleType role
) {}