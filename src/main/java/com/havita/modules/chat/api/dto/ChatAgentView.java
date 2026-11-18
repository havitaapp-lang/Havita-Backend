package com.havita.modules.chat.api.dto;

import java.util.UUID;

/** Portero activo de la comunidad (destino de una transferencia). */
public record ChatAgentView(UUID userId, String name) {}
