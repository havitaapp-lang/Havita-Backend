package com.havita.modules.chat.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Porteria inicia (o retoma) la conversacion con un residente de una unidad. Ambos se validan en el servidor. */
public record ChatStartRequest(@NotNull UUID propertyId, @NotNull UUID residentUserId) {}
