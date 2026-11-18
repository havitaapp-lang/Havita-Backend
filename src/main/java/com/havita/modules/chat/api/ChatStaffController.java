package com.havita.modules.chat.api;

import com.havita.common.api.ApiResponse;
import com.havita.modules.chat.api.dto.ChatAgentView;
import com.havita.modules.chat.api.dto.ChatConversationView;
import com.havita.modules.chat.api.dto.ChatEventView;
import com.havita.modules.chat.api.dto.ChatMessagesPage;
import com.havita.modules.chat.api.dto.ChatReadRequest;
import com.havita.modules.chat.api.dto.ChatResidentOption;
import com.havita.modules.chat.api.dto.ChatSendRequest;
import com.havita.modules.chat.api.dto.ChatSendResult;
import com.havita.modules.chat.api.dto.ChatStartRequest;
import com.havita.modules.chat.api.dto.ChatTransferRequest;
import com.havita.modules.chat.application.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
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
 * Fase 3 — bandeja compartida de porteria (rol SECURITY de la comunidad). La administracion de la comunidad
 * (COMMUNITY_ADMIN) puede CONSULTARLA (bandeja, conversacion, mensajes, historial); solo porteria la opera. Todas las
 * operaciones comprueban en el servidor la membresia vigente y acotan las consultas a la comunidad de la peticion.
 */
@RestController
@RequestMapping("/api/v1/chat/inbox")
@RequiredArgsConstructor
@Tag(name = "Chat (portería)", description = "Bandeja compartida de conversaciones con residentes")
public class ChatStaffController {

    private final ChatService chatService;

    @GetMapping("/conversations")
    @PreAuthorize("hasAnyRole('SECURITY', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Bandeja: filter=OPEN|PENDING|ACTIVE|MINE|CLOSED|ALL, q=residente/torre/unidad")
    public ResponseEntity<ApiResponse<Page<ChatConversationView>>> inbox(
            @RequestParam(required = false, defaultValue = "OPEN") ChatService.InboxFilter filter,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.inbox(filter, q, page, size)));
    }

    @PostMapping("/conversations")
    @PreAuthorize("hasRole('SECURITY')")
    @Operation(summary = "Iniciar o retomar la conversación con un residente (queda asignada a quien la inicia)")
    public ResponseEntity<ApiResponse<ChatConversationView>> start(@Valid @RequestBody ChatStartRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.start(request.propertyId(), request.residentUserId())));
    }

    @GetMapping("/conversations/{id}")
    @PreAuthorize("hasAnyRole('SECURITY', 'COMMUNITY_ADMIN')")
    public ResponseEntity<ApiResponse<ChatConversationView>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.getForStaff(id)));
    }

    @GetMapping("/conversations/{id}/messages")
    @PreAuthorize("hasAnyRole('SECURITY', 'COMMUNITY_ADMIN')")
    public ResponseEntity<ApiResponse<ChatMessagesPage>> messages(@PathVariable UUID id,
                                                                  @RequestParam(required = false) Long before,
                                                                  @RequestParam(required = false) Long after,
                                                                  @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.messagesForStaff(id, before, after, size)));
    }

    @GetMapping("/conversations/{id}/events")
    @PreAuthorize("hasAnyRole('SECURITY', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Auditoría: quién tomó, liberó, transfirió, cerró o reabrió")
    public ResponseEntity<ApiResponse<List<ChatEventView>>> events(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.history(id)));
    }

    @PostMapping("/conversations/{id}/messages")
    @PreAuthorize("hasRole('SECURITY')")
    @Operation(summary = "Responder (toma la conversación si estaba pendiente; la reabre si estaba cerrada)")
    public ResponseEntity<ApiResponse<ChatSendResult>> send(@PathVariable UUID id, @Valid @RequestBody ChatSendRequest request) {
        ChatSendResult result = chatService.sendAsStaff(id, request.body(), request.clientMessageId());
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(ApiResponse.ok(result));
    }

    @PostMapping("/conversations/{id}/claim")
    @PreAuthorize("hasRole('SECURITY')")
    public ResponseEntity<ApiResponse<ChatConversationView>> claim(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.claim(id)));
    }

    @PostMapping("/conversations/{id}/release")
    @PreAuthorize("hasRole('SECURITY')")
    public ResponseEntity<ApiResponse<ChatConversationView>> release(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.release(id)));
    }

    @PostMapping("/conversations/{id}/transfer")
    @PreAuthorize("hasRole('SECURITY')")
    public ResponseEntity<ApiResponse<ChatConversationView>> transfer(@PathVariable UUID id,
                                                                      @Valid @RequestBody ChatTransferRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.transfer(id, request.toUserId())));
    }

    @PostMapping("/conversations/{id}/close")
    @PreAuthorize("hasRole('SECURITY')")
    public ResponseEntity<ApiResponse<ChatConversationView>> close(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.close(id)));
    }

    @PostMapping("/conversations/{id}/read")
    @PreAuthorize("hasRole('SECURITY')")
    public ResponseEntity<ApiResponse<ChatConversationView>> read(@PathVariable UUID id,
                                                                  @Valid @RequestBody ChatReadRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.readAsStaff(id, request.upToSeq())));
    }

    @GetMapping("/agents")
    @PreAuthorize("hasRole('SECURITY')")
    @Operation(summary = "Porteros activos (destinos de transferencia)")
    public ResponseEntity<ApiResponse<List<ChatAgentView>>> agents() {
        return ResponseEntity.ok(ApiResponse.ok(chatService.agents()));
    }

    @GetMapping("/residents")
    @PreAuthorize("hasRole('SECURITY')")
    @Operation(summary = "Buscar residentes con cuenta para iniciar una conversación")
    public ResponseEntity<ApiResponse<List<ChatResidentOption>>> residents(@RequestParam(required = false) String q) {
        return ResponseEntity.ok(ApiResponse.ok(chatService.residentDirectory(q)));
    }
}
