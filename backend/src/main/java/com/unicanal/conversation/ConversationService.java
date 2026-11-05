package com.unicanal.conversation;

import com.unicanal.channel.ChannelType;
import com.unicanal.common.ApiException;
import com.unicanal.common.AuthPrincipal;
import com.unicanal.common.FieldValidationException;
import java.math.BigDecimal;
import org.bson.types.Decimal128;
import org.springframework.data.mongodb.core.aggregation.ComparisonOperators;
import com.unicanal.common.PageResponse;
import com.unicanal.conversation.dto.ConversationResponse;
import com.unicanal.conversation.dto.MessageResponse;
import com.unicanal.message.MessageRepository;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Consulta y cambios de estado de las conversaciones. Los cambios se hacen con actualizaciones
 * puntuales en MongoDB, nunca guardando el documento entero: así no se pisan los contadores que el
 * webhook actualiza a la vez ($inc de unreadCount, $max de lastMessageAt...).
 */
@Service
public class ConversationService {

    public static final int MAX_PAGE_SIZE = 100;

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final MongoTemplate mongoTemplate;
    private final ConversationViews views;
    private final ApplicationEventPublisher events;

    public ConversationService(ConversationRepository conversations, MessageRepository messages,
            MongoTemplate mongoTemplate, ConversationViews views, ApplicationEventPublisher events) {
        this.conversations = conversations;
        this.messages = messages;
        this.mongoTemplate = mongoTemplate;
        this.views = views;
        this.events = events;
    }

    /**
     * Bandeja: conversaciones del tenant, la de actividad más reciente primero.
     *
     * @param assignee "me", "unassigned", un userId, o nulo para todas
     */
    public PageResponse<ConversationResponse> list(AuthPrincipal principal, ConversationStatus status,
            ChannelType channel, String assignee, int page, int size) {
        return list(principal, status, channel, assignee, false, page, size);
    }

    /**
     * @param unanswered solo las que esperan respuesta: no resueltas, con mensaje del cliente y sin respuesta
     *                   enviada después (lastInboundAt posterior a lastOutboundAt, o sin lastOutboundAt). Los
     *                   envíos FAILED no fijan lastOutboundAt, así que no cuentan como respuesta.
     */
    public PageResponse<ConversationResponse> list(AuthPrincipal principal, ConversationStatus status,
            ChannelType channel, String assignee, boolean unanswered, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        Criteria criteria = Criteria.where("tenantId").is(principal.tenantId());
        if (status != null) {
            criteria.and("status").is(status);
        }
        if (channel != null) {
            criteria.and("channel").is(channel);
        }
        if (assignee != null && !assignee.isBlank()) {
            switch (assignee) {
                case "me" -> criteria.and("assignedTo").is(principal.userId());
                case "unassigned" -> criteria.and("assignedTo").is(null);
                default -> criteria.and("assignedTo").is(assignee);
            }
        }
        if (unanswered) {
            // open == (status != RESOLVED). En BSON un campo ausente es menor que cualquier fecha, así que
            // $gt también cubre "nunca se respondió".
            criteria.and("open").is(true).and("lastInboundAt").ne(null)
                    .andOperator(Criteria.expr(ComparisonOperators.valueOf("lastInboundAt")
                            .greaterThan("lastOutboundAt")));
        }
        Query query = Query.query(criteria);
        long total = mongoTemplate.count(query, Conversation.class);
        query.with(PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Order.desc("lastMessageAt"), Sort.Order.desc("createdAt"))));
        List<Conversation> found = mongoTemplate.find(query, Conversation.class);
        return PageResponse.of(views.toResponses(principal.tenantId(), found), safePage, safeSize, total);
    }

    public ConversationResponse get(String tenantId, String conversationId) {
        return views.toResponse(tenantId, find(tenantId, conversationId));
    }

    /** Mensajes de la conversación, del más reciente al más antiguo (la página 0 es la última parte del chat). */
    public PageResponse<MessageResponse> messages(String tenantId, String conversationId, int page, int size) {
        find(tenantId, conversationId);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("timestamp"), Sort.Order.desc("_id")));
        return PageResponse.from(messages.findByTenantIdAndConversationId(tenantId, conversationId, pageable),
                MessageResponse::from);
    }

    public ConversationResponse markRead(String tenantId, String conversationId) {
        find(tenantId, conversationId);
        mongoTemplate.updateFirst(byId(tenantId, conversationId), Update.update("unreadCount", 0),
                Conversation.class);
        events.publishEvent(new ConversationUpdatedEvent(tenantId, conversationId));
        return get(tenantId, conversationId);
    }

    /**
     * Cambia el estado. RESOLVED cierra la conversación ({@code open = false}); cualquier otro estado la deja
     * abierta. Reabrir choca con el índice único parcial si el cliente ya tiene otra conversación abierta en
     * el canal (le escribió después de resolverla): entonces responde 409.
     */
    public ConversationResponse changeStatus(String tenantId, String conversationId, ConversationStatus status) {
        Conversation conversation = find(tenantId, conversationId);
        Update update = Update.update("status", status).set("open", status != ConversationStatus.RESOLVED);
        try {
            mongoTemplate.updateFirst(byId(tenantId, conversationId), update, Conversation.class);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ANOTHER_CONVERSATION_OPEN",
                    "No se puede reabrir: este cliente ya tiene otra conversación abierta por "
                            + conversation.getChannel() + ". Continúa la atención en esa conversación.");
        }
        events.publishEvent(new ConversationUpdatedEvent(tenantId, conversationId));
        return get(tenantId, conversationId);
    }

    /** Marca o desmarca la venta. Con {@code isSale = false} el monto se borra. */
    public ConversationResponse markSale(String tenantId, String conversationId, boolean isSale,
            BigDecimal saleAmount) {
        if (isSale && saleAmount == null) {
            throw new FieldValidationException("saleAmount", "El monto es obligatorio cuando hay venta");
        }
        find(tenantId, conversationId);
        Update update = Update.update("isSale", isSale)
                .set("saleAmount", isSale ? new Decimal128(saleAmount) : null);
        mongoTemplate.updateFirst(byId(tenantId, conversationId), update, Conversation.class);
        events.publishEvent(new ConversationUpdatedEvent(tenantId, conversationId));
        return get(tenantId, conversationId);
    }

    Conversation find(String tenantId, String conversationId) {
        return conversations.findByIdAndTenantId(conversationId, tenantId)
                .orElseThrow(ConversationService::notFound);
    }

    static Query byId(String tenantId, String conversationId) {
        return Query.query(Criteria.where("_id").is(conversationId).and("tenantId").is(tenantId));
    }

    static ApiException notFound() {
        return ApiException.notFound("CONVERSATION_NOT_FOUND", "Conversación no encontrada");
    }
}
