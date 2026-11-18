package com.havita.modules.chat.api.dto;

/** Resultado del envio. {@code duplicate}: reintento con el mismo clientMessageId (no se creo un mensaje nuevo). */
public record ChatSendResult(ChatConversationView conversation, ChatMessageView message, boolean duplicate) {}
