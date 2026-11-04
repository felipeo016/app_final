package com.unicanal.user;

import com.unicanal.common.AuthPrincipal;
import com.unicanal.user.dto.CreateAgentRequest;
import com.unicanal.user.dto.UserResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agents")
@PreAuthorize("hasRole('OWNER')")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping
    public List<UserResponse> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return agentService.list(principal.tenantId()).stream().map(UserResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreateAgentRequest request) {
        return UserResponse.from(agentService.create(principal.tenantId(), request));
    }

    @PatchMapping("/{id}/deactivate")
    public UserResponse deactivate(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String id) {
        return UserResponse.from(agentService.deactivate(principal.tenantId(), id));
    }
}
