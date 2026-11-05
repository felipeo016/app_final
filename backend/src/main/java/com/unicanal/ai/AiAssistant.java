package com.unicanal.ai;

import com.unicanal.ai.AiPrompts.Author;
import com.unicanal.ai.AiPrompts.Candidate;
import com.unicanal.ai.AiPrompts.ContextMessage;
import com.unicanal.ai.AiPrompts.QuickReplyText;
import com.unicanal.common.ApiException;
import com.unicanal.contact.Contact;
import com.unicanal.contact.ContactRepository;
import com.unicanal.conversation.ConversationService;
import com.unicanal.conversation.QuickReply;
import com.unicanal.conversation.QuickReplyRepository;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.Direction;
import com.unicanal.message.Message;
import com.unicanal.message.MessageType;
import com.unicanal.tenant.Tenant;
import com.unicanal.tenant.TenantRepository;
import com.unicanal.tenant.TenantSettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Arma el contexto de una conversación, consulta al modelo y valida su respuesta. */
@Service
public class AiAssistant {

    private static final Logger log = LoggerFactory.getLogger(AiAssistant.class);
    private static final Pattern FIRST_NAME = Pattern.compile("\\p{Lu}[\\p{L}'-]{1,29}");

    private final AiProvider provider;
    private final TenantRepository tenants;
    private final QuickReplyRepository quickReplies;
    private final ContactRepository contacts;
    private final ConversationService conversationService;
    private final MongoTemplate mongoTemplate;
    private final JsonMapper jsonMapper;

    public AiAssistant(AiProvider provider, TenantRepository tenants, QuickReplyRepository quickReplies,
            ContactRepository contacts, ConversationService conversationService, MongoTemplate mongoTemplate,
            JsonMapper jsonMapper) {
        this.provider = provider;
        this.tenants = tenants;
        this.quickReplies = quickReplies;
        this.contacts = contacts;
        this.conversationService = conversationService;
        this.mongoTemplate = mongoTemplate;
        this.jsonMapper = jsonMapper;
    }

    /** Estado para el encabezado: READY, OFF (la empresa la apagó) o UNAVAILABLE (Ollama no responde). */
    public record Status(String state, AiMode mode, String model) {
    }

    /**
     * Lo que propuso el modelo para el último mensaje del cliente.
     *
     * @param answer        vacío si la salida del modelo no era un JSON válido
     * @param lastInboundId último mensaje del cliente que vio el modelo (para no responder a un contexto viejo)
     */
    public record Draft(Optional<AiAnswer> answer, String lastInboundId) {
    }

    public TenantSettings settings(String tenantId) {
        return tenants.findById(tenantId).map(Tenant::getSettings).orElseGet(TenantSettings::new);
    }

    public Status status(String tenantId) {
        AiMode mode = settings(tenantId).getAiMode();
        if (mode == AiMode.OFF) {
            return new Status("OFF", mode, provider.model());
        }
        return new Status(provider.isAvailable() ? "READY" : "UNAVAILABLE", mode, provider.model());
    }

    /** "Sugerir respuesta": no envía nada, solo devuelve el texto para que el agente lo revise. */
    public AiAnswer suggest(String tenantId, String conversationId) {
        conversationService.find(tenantId, conversationId);
        if (settings(tenantId).getAiMode() == AiMode.OFF) {
            throw new ApiException(HttpStatus.CONFLICT, "AI_OFF", "La IA está apagada en esta empresa");
        }
        try {
            return draft(tenantId, conversationId).answer().orElse(AiAnswer.CANNOT);
        } catch (AiUnavailableException e) {
            log.warn("IA no disponible al sugerir en la conversación {}: {}", conversationId, e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
                    "La IA no está disponible en este momento");
        }
    }

    /** Contexto de la conversación: los últimos mensajes y el último del cliente. */
    private record Context(List<ContextMessage> messages, String lastInboundId, String lastInboundText) {
    }

    private Context context(String tenantId, String conversationId) {
        String lastInboundId = null;
        String lastInboundText = null;
        List<ContextMessage> context = new ArrayList<>();
        for (Message message : recentMessages(tenantId, conversationId)) {
            boolean customer = message.getDirection() == Direction.INBOUND;
            if (customer) {
                lastInboundId = message.getId();
                lastInboundText = message.getContent();
            }
            context.add(new ContextMessage(customer ? Author.CUSTOMER : Author.STORE, message.getContent()));
        }
        return new Context(context, lastInboundId, lastInboundText);
    }

    private static boolean needsPerson(Context context, String conversationId) {
        if (HumanTopics.needsPerson(context.lastInboundText())) {
            log.info("IA: la conversación {} necesita a una persona (pedido, pago o reclamo); no se consulta al "
                    + "modelo", conversationId);
            return true;
        }
        return false;
    }

    /** Resultado de la selección del modo automático (para enviar y para la evaluación). */
    public record Selection(Draft draft, String quickReplyTitle, String outcome) {

        /** La IA no elige nada: el chat queda para el equipo. {@code outcome} dice por qué. */
        static Selection none(String lastInboundId, String outcome) {
            return new Selection(new Draft(Optional.of(AiAnswer.CANNOT), lastInboundId), null, outcome);
        }
    }

    /**
     * Modo "Respuesta automática", por SELECCIÓN y no por redacción: el modelo solo elige cuál respuesta rápida
     * responde el último mensaje del cliente (o ninguna) y se envía su texto EXACTO, con un saludo fijo
     * ("Hola, Valentina."). Si elige ninguna, o un código que no existe, o el JSON es inválido, no responde. Así la
     * IA no puede inventar datos: todo lo que llega al cliente lo escribió la empresa.
     *
     * @throws AiUnavailableException si el modelo no responde
     */
    public Selection selectReply(String tenantId, String conversationId) {
        Tenant tenant = tenants.findById(tenantId).orElseThrow();
        Context context = context(tenantId, conversationId);
        if (needsPerson(context, conversationId)) {
            return Selection.none(context.lastInboundId(), "tema para una persona (filtro fijo)");
        }
        List<QuickReply> replies = quickReplies.findByTenantIdOrderByTitleAsc(tenantId);
        if (replies.isEmpty()) {
            return Selection.none(context.lastInboundId(), "la empresa no tiene respuestas rápidas");
        }
        List<Candidate> candidates = new ArrayList<>();
        Map<String, QuickReply> byCode = new HashMap<>();
        for (int i = 0; i < replies.size(); i++) {
            String code = "R" + (i + 1);
            candidates.add(new Candidate(code, replies.get(i).getTitle(), replies.get(i).getText()));
            byCode.put(code, replies.get(i));
        }
        String raw = provider.chat(AiPrompts.selection(tenant.getName(), candidates, context.messages()),
                AiPrompts.selectionSchema(candidates));
        Optional<Optional<String>> choice = parseSelection(raw);
        if (choice.isEmpty()) {
            log.warn("IA: selección con formato inválido en la conversación {}; queda para un humano", conversationId);
            return Selection.none(context.lastInboundId(), "JSON inválido");
        }
        if (choice.get().isEmpty()) {
            return Selection.none(context.lastInboundId(), "ninguna respuesta rápida aplica");
        }
        QuickReply chosen = byCode.get(choice.get().get());
        if (chosen == null) {
            log.warn("IA: eligió una respuesta rápida que no existe ({}) en la conversación {}", choice.get().get(),
                    conversationId);
            return Selection.none(context.lastInboundId(), "código inexistente: " + choice.get().get());
        }
        String text = greeting(customerName(tenantId, conversationId)) + " " + chosen.getText().strip();
        return new Selection(new Draft(Optional.of(new AiAnswer(true, text)), context.lastInboundId()),
                chosen.getTitle(), "respuesta rápida \"" + chosen.getTitle() + "\"");
    }

    /** Vacío = JSON inválido; Optional.empty() dentro = eligió null ("ninguna"). */
    private Optional<Optional<String>> parseSelection(String raw) {
        try {
            JsonNode node = jsonMapper.readTree(raw == null ? "" : raw.strip());
            JsonNode id = node.path(AiPrompts.SELECTION_FIELD);
            if (!node.isObject() || id.isMissingNode()) {
                return Optional.empty();
            }
            if (id.isNull()) {
                return Optional.of(Optional.empty());
            }
            if (!id.isString() || id.asString().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Optional.of(id.asString().strip()));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    private String customerName(String tenantId, String conversationId) {
        String contactId = conversationService.find(tenantId, conversationId).getContactId();
        return contacts.findByIdAndTenantId(contactId, tenantId).map(Contact::getName).orElse(null);
    }

    /** "Hola, Valentina." si el nombre del perfil empieza con un nombre de pila; si no (un número, un usuario), "Hola." */
    static String greeting(String customerName) {
        if (customerName != null) {
            String first = customerName.strip().split("\\s+")[0];
            if (FIRST_NAME.matcher(first).matches()) {
                return "Hola, " + first + ".";
            }
        }
        return "Hola.";
    }

    /**
     * Consulta al modelo con el contexto actual de la conversación (redacción libre: modo Sugerencias).
     *
     * @throws AiUnavailableException si el modelo no responde
     */
    public Draft draft(String tenantId, String conversationId) {
        Tenant tenant = tenants.findById(tenantId).orElseThrow();
        Context ctx = context(tenantId, conversationId);
        List<ContextMessage> context = ctx.messages();
        String lastInboundId = ctx.lastInboundId();
        if (needsPerson(ctx, conversationId)) {
            return new Draft(Optional.of(AiAnswer.CANNOT), lastInboundId);
        }
        List<QuickReplyText> replies = quickReplies.findByTenantIdOrderByTitleAsc(tenantId).stream()
                .map(reply -> new QuickReplyText(reply.getTitle(), reply.getText())).toList();
        List<AiMessage> prompt = AiPrompts.build(tenant.getName(), tenant.getSettings().getBusinessInfo(), replies,
                context);
        String raw = provider.chat(prompt, AiPrompts.ANSWER_SCHEMA);
        Optional<AiAnswer> answer = AiAnswer.parse(raw, jsonMapper);
        if (answer.isEmpty()) {
            log.warn("IA: respuesta con formato inválido en la conversación {}; queda para un humano", conversationId);
            return new Draft(answer, lastInboundId);
        }
        if (answer.get().canAnswer()
                && !CatalogGuard.consistent(tenant.getSettings().getBusinessInfo(), answer.get().text())) {
            log.info("IA: la respuesta de la conversación {} menciona una talla o un color que el producto no tiene; "
                    + "queda para un humano", conversationId);
            return new Draft(Optional.of(AiAnswer.CANNOT), lastInboundId);
        }
        return new Draft(answer, lastInboundId);
    }

    /**
     * Los últimos {@value AiPrompts#CONTEXT_MESSAGES} mensajes que vio el cliente, del más antiguo al más nuevo
     * (sin notas internas ni envíos fallidos).
     */
    List<Message> recentMessages(String tenantId, String conversationId) {
        Query query = Query.query(Criteria.where("tenantId").is(tenantId).and("conversationId").is(conversationId)
                        .and("type").ne(MessageType.INTERNAL_NOTE).and("deliveryStatus").ne(DeliveryStatus.FAILED))
                .with(Sort.by(Sort.Direction.DESC, "timestamp")).limit(AiPrompts.CONTEXT_MESSAGES);
        List<Message> recent = new ArrayList<>(mongoTemplate.find(query, Message.class));
        Collections.reverse(recent);
        return recent;
    }
}
