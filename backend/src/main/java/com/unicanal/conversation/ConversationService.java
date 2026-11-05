package com.unicanal.conversation;

import com.unicanal.channel.ChannelType;
import com.unicanal.common.ApiException;
import com.unicanal.common.AuthPrincipal;
import com.unicanal.common.FieldValidationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
        return list(principal, status, channel, assignee, unanswered, null, page, size);
    }

    /**
     * Bandeja ordenada por actividad (lastMessageAt desc) con el id como desempate, así el orden es total y estable.
     *
     * @param before cursor de "Cargar más" ({@code nextCursor} de la respuesta anterior): devuelve las
     *               conversaciones que van DESPUÉS de esa en el orden. A diferencia de pedir la página N, no se
     *               salta ni repite conversaciones aunque entre peticiones alguna cambie de estado o de filtro.
     */
    public PageResponse<ConversationResponse> list(AuthPrincipal principal, ConversationStatus status,
            ChannelType channel, String assignee, boolean unanswered, String before, int page, int size) {
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        Cursor cursor = Cursor.parse(before);
        int safePage = cursor != null ? 0 : Math.max(page, 0);

        List<Criteria> filters = new ArrayList<>();
        filters.add(Criteria.where("tenantId").is(principal.tenantId()));
        if (status != null) {
            filters.add(Criteria.where("status").is(status));
        }
        if (channel != null) {
            filters.add(Criteria.where("channel").is(channel));
        }
        if (assignee != null && !assignee.isBlank()) {
            filters.add(switch (assignee) {
                case "me" -> Criteria.where("assignedTo").is(principal.userId());
                case "unassigned" -> Criteria.where("assignedTo").is(null);
                default -> Criteria.where("assignedTo").is(assignee);
            });
        }
        if (unanswered) {
            // open == (status != RESOLVED). En BSON un campo ausente es menor que cualquier fecha, así que
            // $gt también cubre "nunca se respondió".
            filters.add(Criteria.where("open").is(true).and("lastInboundAt").ne(null));
            filters.add(Criteria.expr(ComparisonOperators.valueOf("lastInboundAt").greaterThan("lastOutboundAt")));
        }
        long total = mongoTemplate.count(Query.query(new Criteria().andOperator(filters)), Conversation.class);

        List<Criteria> page1 = new ArrayList<>(filters);
        if (cursor != null) {
            page1.add(cursor.after());
        }
        // Se pide uno de más para saber si hay otra página sin un segundo conteo
        Query query = Query.query(new Criteria().andOperator(page1))
                .with(Sort.by(Sort.Order.desc("lastMessageAt"), Sort.Order.desc("_id")))
                .skip((long) safePage * safeSize)
                .limit(safeSize + 1);
        List<Conversation> found = mongoTemplate.find(query, Conversation.class);
        boolean more = found.size() > safeSize;
        List<Conversation> items = more ? found.subList(0, safeSize) : found;
        String nextCursor = more ? Cursor.of(items.get(items.size() - 1)).encode() : null;
        List<ConversationResponse> responses = views.toResponses(principal.tenantId(), items);
        return cursor != null
                ? PageResponse.byCursor(responses, false, safeSize, total, nextCursor)
                : new PageResponse<>(responses, safePage, safeSize, total,
                        (int) ((total + safeSize - 1) / safeSize), nextCursor);
    }

    /** Posición en el orden de la bandeja: "{lastMessageAt en ms}_{id}" (sin hora: "_{id}"). */
    record Cursor(Instant lastMessageAt, String id) {

        static Cursor of(Conversation conversation) {
            return new Cursor(conversation.getLastMessageAt(), conversation.getId());
        }

        String encode() {
            return (lastMessageAt == null ? "" : String.valueOf(lastMessageAt.toEpochMilli())) + "_" + id;
        }

        static Cursor parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            int separator = value.indexOf('_');
            String id = separator < 0 ? "" : value.substring(separator + 1);
            if (separator < 0 || id.isBlank()) {
                throw invalid();
            }
            try {
                String millis = value.substring(0, separator);
                return new Cursor(millis.isEmpty() ? null : Instant.ofEpochMilli(Long.parseLong(millis)), id);
            } catch (NumberFormatException e) {
                throw invalid();
            }
        }

        /** Lo que va después en orden descendente (lastMessageAt desc, _id desc; las sin fecha al final). */
        Criteria after() {
            Criteria sameTimeOlderId = Criteria.where("lastMessageAt").is(lastMessageAt).and("_id").lt(id);
            if (lastMessageAt == null) {
                return sameTimeOlderId;
            }
            return new Criteria().orOperator(
                    Criteria.where("lastMessageAt").lt(lastMessageAt),
                    sameTimeOlderId,
                    Criteria.where("lastMessageAt").is(null));
        }

        private static FieldValidationException invalid() {
            return new FieldValidationException("before", "El cursor de paginación no es válido");
        }
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
        Update update = status == ConversationStatus.RESOLVED
                ? RiskAlertService.clearRisk().set("status", status).set("open", false)
                : Update.update("status", status).set("open", true);
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
