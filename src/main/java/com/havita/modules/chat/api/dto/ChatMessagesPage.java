package com.havita.modules.chat.api.dto;

import java.util.List;

/** Mensajes en orden ascendente de {@code seq}. {@code hasMore}: quedan mas en la direccion pedida. */
public record ChatMessagesPage(List<ChatMessageView> messages, boolean hasMore, ChatConversationView conversation) {}
