package com.unicanal.conversation;

import com.unicanal.channel.ChannelType;
import com.unicanal.common.AuthPrincipal;
import com.unicanal.common.PageResponse;
import com.unicanal.conversation.dto.AssignRequest;
import com.unicanal.conversation.dto.ChangeStatusRequest;
import com.unicanal.conversation.dto.ConversationResponse;
import com.unicanal.conversation.dto.MessageResponse;
import com.unicanal.conversation.dto.SaleRequest;
import com.unicanal.conversation.dto.SendMessageRequest;
import org.springframework.web.bind.annotation.PatchMapping;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Bandeja unificada. El tenant sale siempre del JWT: una conversación de otra empresa responde 404. */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final ReplyService replyService;
    private final AssignmentService assignmentService;

    public ConversationController(ConversationService conversationService, ReplyService replyService,
            AssignmentService assignmentService) {
        this.conversationService = conversationService;
        this.replyService = replyService;
        this.assignmentService = assignmentService;
    }

    /** @param assignee "me", "unassigned" o el id de un agente */
    @GetMapping
    public PageResponse<ConversationResponse> list(@AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) ConversationStatus status,
            @RequestParam(required = false) ChannelType channel,
            @RequestParam(required = false) String assignee,
            @RequestParam(defaultValue = "false") boolean unanswered,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return conversationService.list(principal, status, channel, assignee, unanswered, page, size);
    }

    @PatchMapping("/{id}/sale")
    public ConversationResponse markSale(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id,
            @Valid @RequestBody SaleRequest request) {
        return conversationService.markSale(principal.tenantId(), id, request.isSale(), request.saleAmount());
    }

    @GetMapping("/{id}")
    public ConversationResponse get(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        return conversationService.get(principal.tenantId(), id);
    }

    @GetMapping("/{id}/messages")
    public PageResponse<MessageResponse> messages(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return conversationService.messages(principal.tenantId(), id, page, size);
    }

    /** Responde al cliente. 201 siempre que se haya guardado: el cuerpo dice si quedó SENT o FAILED (y por qué). */
    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse reply(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id,
            @Valid @RequestBody SendMessageRequest request) {
        return replyService.reply(principal, id, request.text());
    }

    @PostMapping("/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse addNote(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id,
            @Valid @RequestBody SendMessageRequest request) {
        return replyService.addNote(principal, id, request.text());
    }

    @PostMapping("/{id}/read")
    public ConversationResponse markRead(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        return conversationService.markRead(principal.tenantId(), id);
    }

    @PutMapping("/{id}/status")
    public ConversationResponse changeStatus(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String id, @Valid @RequestBody ChangeStatusRequest request) {
        return conversationService.changeStatus(principal.tenantId(), id, request.status());
    }

    @PutMapping("/{id}/assignee")
    public ConversationResponse assign(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id,
            @RequestBody AssignRequest request) {
        return assignmentService.assign(principal, id, request.agentId());
    }
}
