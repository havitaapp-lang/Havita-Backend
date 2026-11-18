package com.havita.modules.pqrs.api.dto;

import jakarta.validation.constraints.NotBlank;

import jakarta.validation.constraints.Size;
public record AddPqrsCommentRequest(
        @NotBlank(message = "El comentario o respuesta es obligatorio")
        @Size(max = 500, message = "El comentario no puede exceder 500 caracteres")
        String comment,

        String changeStatusTo
) {}

