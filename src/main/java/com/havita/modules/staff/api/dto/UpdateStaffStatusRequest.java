package com.havita.modules.staff.api.dto;

import com.havita.modules.staff.domain.StaffMemberStatus;
import jakarta.validation.constraints.NotNull;

/** Cuerpo de {@code POST /api/v1/staff/{id}/status}: ACTIVE o INACTIVE. */
public record UpdateStaffStatusRequest(
        @NotNull(message = "El estado es obligatorio")
        StaffMemberStatus status
) {}
