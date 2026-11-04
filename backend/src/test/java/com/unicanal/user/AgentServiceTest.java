package com.unicanal.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unicanal.Fixtures;
import com.unicanal.common.ApiException;
import com.unicanal.user.dto.CreateAgentRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private ApplicationEventPublisher events;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private AgentService service;

    @BeforeEach
    void setUp() {
        service = new AgentService(userRepository, passwordEncoder, events);
    }

    @Test
    void listIsScopedToTheTenantAndAgentRole() {
        User agent = Fixtures.user("a1", "t1", Role.AGENT, "a1@acme.com", "hash");
        when(userRepository.findByTenantIdAndRoleOrderByCreatedAtAsc("t1", Role.AGENT)).thenReturn(List.of(agent));

        assertThat(service.list("t1")).containsExactly(agent);
    }

    @Test
    void createBuildsActiveAgentInTheCallersTenant() {
        when(userRepository.existsByEmail("new@acme.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create("t1", new CreateAgentRequest(" Luis ", " New@Acme.com ", "s3cret-pass"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User agent = saved.getValue();
        assertThat(agent.getTenantId()).isEqualTo("t1");
        assertThat(agent.getRole()).isEqualTo(Role.AGENT);
        assertThat(agent.getName()).isEqualTo("Luis");
        assertThat(agent.getEmail()).isEqualTo("new@acme.com");
        assertThat(agent.isActive()).isTrue();
        assertThat(passwordEncoder.matches("s3cret-pass", agent.getPasswordHash())).isTrue();
    }

    @Test
    void createWithTakenEmailIsConflict() {
        when(userRepository.existsByEmail("new@acme.com")).thenReturn(true);

        assertThatThrownBy(() -> service.create("t1", new CreateAgentRequest("Luis", "new@acme.com", "s3cret-pass")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(userRepository, never()).save(any());
    }

    @Test
    void deactivateDisablesAgentAndPublishesEvent() {
        User agent = Fixtures.user("a1", "t1", Role.AGENT, "a1@acme.com", "hash");
        when(userRepository.findByIdAndTenantId("a1", "t1")).thenReturn(Optional.of(agent));
        when(userRepository.save(agent)).thenReturn(agent);

        User result = service.deactivate("t1", "a1");

        assertThat(result.isActive()).isFalse();
        verify(events).publishEvent(new AgentDeactivatedEvent("t1", "a1"));
    }

    @Test
    void deactivateIsIdempotentAndDoesNotRepublishEvent() {
        User agent = Fixtures.user("a1", "t1", Role.AGENT, "a1@acme.com", "hash");
        agent.setActive(false);
        when(userRepository.findByIdAndTenantId("a1", "t1")).thenReturn(Optional.of(agent));

        service.deactivate("t1", "a1");

        verify(userRepository, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void deactivateAgentOfAnotherTenantIsNotFound() {
        when(userRepository.findByIdAndTenantId("a1", "t2")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivate("t2", "a1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        verify(userRepository, never()).save(any());
    }

    @Test
    void ownerCannotBeDeactivated() {
        User owner = Fixtures.user("o1", "t1", Role.OWNER, "o@acme.com", "hash");
        when(userRepository.findByIdAndTenantId("o1", "t1")).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.deactivate("t1", "o1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(owner.isActive()).isTrue();
    }
}
