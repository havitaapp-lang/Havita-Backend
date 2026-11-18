package com.havita.modules.chat.api.dto;

import jakarta.validation.constraints.PositiveOrZero;

/** Marca como leido hasta el mensaje {@code upToSeq} (incluido). */
public record ChatReadRequest(@PositiveOrZero long upToSeq) {}
