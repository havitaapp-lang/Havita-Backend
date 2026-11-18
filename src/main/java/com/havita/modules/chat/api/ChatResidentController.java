package com.havita.modules.chat.api;

import com.havita.common.api.ApiResponse;
import com.havita.modules.chat.api.dto.ChatConversationView;
import com.havita.modules.chat.api.dto.ChatMessagesPage;
import com.havita.modules.chat.api.dto.ChatReadRequest;
import com.havita.modules.chat.api.dto.ChatSendRequest;
import com.havita.modules.chat.api.dto.ChatSendResult;
import com.havita.modules.chat.application.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Fase 3 — chat del residente con porteria. Solo sus conversaciones, en unidades que hoy tiene activas; la unidad de
 * un envio nuevo sale de X-Property-Id validada contra SUS unidades (nunca se acepta un propertyId del cuerpo).
 */
@RestController
@RequestMapping("/api/v1/chat/me")
@RequiredArgsConstructor
@Tag(name = "Chat (residente)", description = "Chat de texto del residente con portería")
public class ChatResidentController {

    private final ChatService chatService;

    @GetMapping("/conversations")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Mis conversaciones con portería")
    public ResponseEntity<ApiResponse<List<ChatConversationView>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(chatService.listMine()));
    }

    @PostMapping("/messages")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Escribir a portería desde la unidad actual (crea o reabre la conversación)")
    public ResponseEntity<ApiResponse<ChatSendResult>> send(@Valid @RequestBody ChatSendRequest request) {
        ChatSendResult result = chatService.sendMine(request.body(), request.clientMessageId());
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(ApiResponse.ok(result));
    }

    @GetMapping("/conversations/{id}/messages")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Historial paginado (before) o recuperación tras desconexión (after)")
    public ResponseEntity<ApiResponse<ChatMessagesPage>> messages(@PathVariable UUID id,
                                                                  @RequestParam(required = false) Long before,
                                                                  @RequestParam(required = false) Long after,
                                                                  @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.messagesMine(id, before, after, size)));
    }

    @PostMapping("/conversations/{id}/read")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Marcar como leído hasta un mensaje")
    public ResponseEntity<ApiResponse<ChatConversationView>> read(@PathVariable UUID id,
                                                                  @Valid @RequestBody ChatReadRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.readMine(id, request.upToSeq())));
    }
}
