package com.havita.modules.chat.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ChatTransferRequest(@NotNull UUID toUserId) {}
