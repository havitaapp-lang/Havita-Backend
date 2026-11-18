package com.havita.modules.pqrs.api.dto;

import jakarta.validation.constraints.Size;
import java.util.UUID;

public record UpdatePqrsRequest(
        @Size(max = 50, message = "La categoría no puede exceder 50 caracteres")
        String category,

        @Size(max = 200, message = "El título no puede exceder 200 caracteres")
        String title,

        @Size(max = 1000, message = "La descripción no puede exceder 1000 caracteres")
        String description,

        @Size(max = 30, message = "La prioridad no puede exceder 30 caracteres")
        String priority,
        
        String status,

        /** Nota de la solucion (se guarda si la PQRS queda resuelta o cerrada). */
        @Size(max = 1000, message = "La nota de la solución no puede exceder 1000 caracteres")
        String responseNotes
) {}
