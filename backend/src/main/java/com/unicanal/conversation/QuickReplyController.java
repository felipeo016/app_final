package com.unicanal.conversation;

import com.unicanal.common.AuthPrincipal;
import com.unicanal.conversation.dto.QuickReplyRequest;
import com.unicanal.conversation.dto.QuickReplyResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Todos los usuarios pueden usar las respuestas rápidas; solo el OWNER las crea, edita y borra. */
@RestController
@RequestMapping("/api/quick-replies")
public class QuickReplyController {

    private final QuickReplyService service;

    public QuickReplyController(QuickReplyService service) {
        this.service = service;
    }

    @GetMapping
    public List<QuickReplyResponse> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.list(principal.tenantId()).stream().map(QuickReplyResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('OWNER')")
    public QuickReplyResponse create(@AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody QuickReplyRequest request) {
        return QuickReplyResponse.from(service.create(principal.tenantId(), request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public QuickReplyResponse update(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id,
            @Valid @RequestBody QuickReplyRequest request) {
        return QuickReplyResponse.from(service.update(principal.tenantId(), id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('OWNER')")
    public void delete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        service.delete(principal.tenantId(), id);
    }
}
