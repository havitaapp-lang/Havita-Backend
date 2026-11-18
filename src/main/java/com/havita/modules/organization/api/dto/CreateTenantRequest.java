package com.havita.modules.organization.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateTenantRequest(
        UUID organizationId,

        @NotBlank(message = "El nombre del tenant es obligatorio")
        @Size(max = 255, message = "El nombre no puede exceder 255 caracteres")
        String name,

        @NotBlank(message = "El slug es obligatorio")
        @Size(max = 100, message = "El slug no puede exceder 100 caracteres")
        @Pattern(regexp = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", message = "El slug solo puede contener letras minusculas, numeros y guiones")
        String slug,

        @NotBlank(message = "El subdominio es obligatorio")
        @Size(max = 100, message = "El subdominio no puede exceder 100 caracteres")
        @Pattern(regexp = "^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", message = "El subdominio solo puede contener letras minusculas, numeros y guiones")
        String subdomain,

        @Size(max = 50, message = "El NIT no puede exceder 50 caracteres")
        String nit,

        @Size(max = 100, message = "La ciudad no puede exceder 100 caracteres")
        String city,

        @Size(max = 255, message = "La direccion no puede exceder 255 caracteres")
        String address,

        @Size(max = 50, message = "El telefono no puede exceder 50 caracteres")
        String phone,

        @Email(message = "El email de contacto debe tener un formato valido")
        @Size(max = 150, message = "El email de contacto no puede exceder 150 caracteres")
        String contactEmail,

        Integer storageLimitMb,

        
        String adminName,

        @Email(message = "El correo del administrador debe ser valido")
        
        String adminEmail,

        
        String adminPassword,

        String adminPhone,
        String planId,
        String planName,
        Integer propertiesCount
) {}
