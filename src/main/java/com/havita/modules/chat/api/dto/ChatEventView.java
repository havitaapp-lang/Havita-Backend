package com.havita.modules.chat.api.dto;

import com.havita.modules.chat.domain.ChatEventType;

import java.time.OffsetDateTime;

/** Auditoria visible para porteria: quien tomo, libero, transfirio, cerro o reabrio y cuando. */
public record ChatEventView(ChatEventType type, String actorName, String fromName, String toName, OffsetDateTime at) {}
