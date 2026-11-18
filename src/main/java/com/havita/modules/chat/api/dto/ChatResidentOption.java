package com.havita.modules.chat.api.dto;

import java.util.UUID;

/** Residente con cuenta y relacion ACTIVE con una unidad (para que porteria inicie una conversacion). */
public record ChatResidentOption(UUID residentUserId, String residentName, UUID propertyId, String unitLabel,
                                 UUID conversationId) {}
