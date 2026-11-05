package com.unicanal.conversation;

import com.unicanal.common.ApiException;
import com.unicanal.common.AuthPrincipal;
import com.unicanal.conversation.dto.ConversationResponse;
import com.unicanal.message.MessageReceivedEvent;
import com.unicanal.tenant.Tenant;
import com.unicanal.user.AgentDeactivatedEvent;
import com.unicanal.user.Role;
import com.unicanal.user.User;
import com.unicanal.user.UserRepository;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Asignación de conversaciones a agentes.
 * <ul>
 *   <li><b>OWNER</b>: asigna a cualquier usuario activo de su empresa, o deja el chat sin asignar.</li>
 *   <li><b>AGENT</b>: solo puede tomar un chat sin asignar o soltar uno propio; cualquier otra cosa es 403.
 *       Ambas operaciones son condicionales y atómicas: si dos agentes toman el mismo chat a la vez, solo
 *       uno lo consigue.</li>
 *   <li><b>Por turnos</b>: cada conversación nueva se asigna al siguiente agente activo (contador atómico
 *       {@code roundRobinCursor} del tenant).</li>
 *   <li><b>Desactivación</b>: los chats abiertos del agente desactivado quedan sin asignar.</li>
 * </ul>
 */
@Service
public class AssignmentService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);

    private final ConversationService conversationService;
    private final UserRepository users;
    private final MongoTemplate mongoTemplate;
    private final ApplicationEventPublisher events;

    public AssignmentService(ConversationService conversationService, UserRepository users,
            MongoTemplate mongoTemplate, ApplicationEventPublisher events) {
        this.conversationService = conversationService;
        this.users = users;
        this.mongoTemplate = mongoTemplate;
        this.events = events;
    }

    /** {@code agentId} nulo = dejar sin asignar. */
    public ConversationResponse assign(AuthPrincipal principal, String conversationId, String agentId) {
        String tenantId = principal.tenantId();
        String target = agentId == null || agentId.isBlank() ? null : agentId.trim();
        if (principal.role() == Role.OWNER) {
            assignAsOwner(tenantId, conversationId, target);
        } else {
            assignAsAgent(principal, conversationId, target);
        }
        return conversationService.get(tenantId, conversationId);
    }

    private void assignAsOwner(String tenantId, String conversationId, String target) {
        conversationService.find(tenantId, conversationId);
        if (target != null) {
            User user = users.findByIdAndTenantId(target, tenantId)
                    .orElseThrow(() -> ApiException.notFound("AGENT_NOT_FOUND", "Agente no encontrado"));
            if (!user.isActive()) {
                throw new ApiException(HttpStatus.CONFLICT, "AGENT_INACTIVE",
                        "No se puede asignar el chat a un agente desactivado");
            }
        }
        mongoTemplate.updateFirst(ConversationService.byId(tenantId, conversationId),
                Update.update("assignedTo", target), Conversation.class);
        events.publishEvent(new ConversationAssignedEvent(tenantId, conversationId, target));
    }

    private void assignAsAgent(AuthPrincipal principal, String conversationId, String target) {
        String tenantId = principal.tenantId();
        String self = principal.userId();
        if (target != null && !target.equals(self)) {
            throw forbidden("Un agente solo puede asignarse chats a sí mismo");
        }
        // Tomar: solo si está sin asignar. Soltar: solo si es propio. Una única operación condicional.
        String expectedCurrent = target == null ? self : null;
        var result = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(conversationId).and("tenantId").is(tenantId)
                        .and("assignedTo").is(expectedCurrent)),
                Update.update("assignedTo", target), Conversation.class);
        if (result.getMatchedCount() == 1) {
            events.publishEvent(new ConversationAssignedEvent(tenantId, conversationId, target));
            return;
        }
        Conversation current = conversationService.find(tenantId, conversationId); // 404 si no existe
        if (target != null && self.equals(current.getAssignedTo())) {
            return; // ya era suyo: idempotente
        }
        throw forbidden(target != null
                ? "Este chat ya está asignado a otro agente"
                : "Solo puedes quitarte chats que tengas asignados");
    }

    /** Asignación por turnos de las conversaciones nuevas. Un fallo aquí nunca impide guardar el mensaje. */
    @EventListener
    @Order(0) // antes de la difusión por WebSocket: el evento del chat nuevo ya sale con su agente
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.newConversation()) {
            return;
        }
        try {
            assignRoundRobin(event.tenantId(), event.conversationId());
        } catch (RuntimeException e) {
            log.error("No se pudo asignar por turnos la conversación {} (tenant {}); queda sin asignar",
                    event.conversationId(), event.tenantId(), e);
        }
    }

    void assignRoundRobin(String tenantId, String conversationId) {
        List<User> agents = users.findByTenantIdAndRoleAndActiveTrueOrderByCreatedAtAsc(tenantId, Role.AGENT);
        if (agents.isEmpty()) {
            return;
        }
        Tenant tenant = mongoTemplate.findAndModify(Query.query(Criteria.where("_id").is(tenantId)),
                new Update().inc("roundRobinCursor", 1), FindAndModifyOptions.options().returnNew(true),
                Tenant.class);
        if (tenant == null) {
            return;
        }
        User next = agents.get((int) Math.floorMod(tenant.getRoundRobinCursor() - 1, (long) agents.size()));
        var result = mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(conversationId).and("tenantId").is(tenantId)
                        .and("assignedTo").is(null)),
                Update.update("assignedTo", next.getId()), Conversation.class);
        if (result.getModifiedCount() == 1) {
            events.publishEvent(new ConversationAssignedEvent(tenantId, conversationId, next.getId()));
        }
    }

    /** Al desactivar un agente, sus chats abiertos quedan sin asignar (y se avisa con ASSIGNED). */
    @EventListener
    public void onAgentDeactivated(AgentDeactivatedEvent event) {
        Query owned = Query.query(Criteria.where("tenantId").is(event.tenantId())
                .and("assignedTo").is(event.agentId()).and("open").is(true));
        owned.fields().include("_id");
        List<String> ids = mongoTemplate.find(owned, Conversation.class).stream().map(Conversation::getId)
                .filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return;
        }
        mongoTemplate.updateMulti(Query.query(Criteria.where("tenantId").is(event.tenantId())
                        .and("assignedTo").is(event.agentId()).and("open").is(true)),
                Update.update("assignedTo", null), Conversation.class);
        ids.forEach(id -> events.publishEvent(new ConversationAssignedEvent(event.tenantId(), id, null)));
    }

    private static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "ASSIGNMENT_FORBIDDEN", message);
    }
}
