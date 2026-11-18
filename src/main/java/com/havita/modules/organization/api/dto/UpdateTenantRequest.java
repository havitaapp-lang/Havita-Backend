package com.havita.modules.organization.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record UpdateTenantRequest(
        @Size(max = 255, message = "El nombre no puede exceder 255 caracteres")
        String name,

        @Size(max = 100, message = "El subdominio no puede exceder 100 caracteres")
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

        @Size(max = 500, message = "La URL del logo no puede exceder 500 caracteres")
        String logoUrl,

        @Size(max = 7, message = "El color primario no puede exceder 7 caracteres")
        String primaryColor,

        @Size(max = 7, message = "El color secundario no puede exceder 7 caracteres")
        String secondaryColor,

        @Size(max = 100, message = "El nombre del plan no puede exceder 100 caracteres")
        String planName,

        @Size(max = 150, message = "El nombre del administrador no puede exceder 150 caracteres")
        String adminName,

        @Email(message = "El correo del administrador debe tener un formato valido")
        @Size(max = 150, message = "El correo del administrador no puede exceder 150 caracteres")
        String adminEmail,

        @Size(max = 50, message = "El telefono del administrador no puede exceder 50 caracteres")
        String adminPhone,

        String adminPassword,

        UUID organizationId,

        Integer propertiesCount,

        List<String> activeFeatures
) {}
