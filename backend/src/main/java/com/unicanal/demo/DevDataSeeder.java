package com.unicanal.demo;

import com.unicanal.auth.AuthService;
import com.unicanal.auth.dto.AuthResponse;
import com.unicanal.auth.dto.RegisterRequest;
import com.unicanal.channel.ChannelSendErrors;
import com.unicanal.channel.ChannelType;
import com.unicanal.channel.InboundMessage;
import com.unicanal.channel.InboundMessageProcessor;
import com.unicanal.conversation.Conversation;
import com.unicanal.conversation.ConversationStatus;
import com.unicanal.conversation.QuickReply;
import com.unicanal.conversation.QuickReplyRepository;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.Direction;
import com.unicanal.message.Message;
import com.unicanal.message.MessageRepository;
import com.unicanal.message.MessageType;
import com.unicanal.user.AgentService;
import com.unicanal.user.User;
import com.unicanal.user.UserRepository;
import com.unicanal.user.dto.CreateAgentRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.bson.types.Decimal128;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Datos de demostración del prototipo. Solo existe con el perfil {@code dev} Y con {@code SEED_DEMO=true}.
 *
 * <ul>
 *   <li><b>Idempotente</b>: si el dueño demo ya existe, no crea nada (se puede dejar activado).</li>
 *   <li><b>Nunca llama a un canal real</b>: los mensajes de los clientes entran por el mismo
 *       {@link InboundMessageProcessor} que usan los webhooks (sin red), y las respuestas de los agentes se
 *       guardan directamente como ya enviadas. Así, aunque haya credenciales de WhatsApp configuradas, no se
 *       envía nada a nadie.</li>
 * </ul>
 * Las credenciales demo están en el README (son solo para desarrollo local).
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "app.seed-demo", havingValue = "true")
public class DevDataSeeder implements ApplicationRunner {

    public static final String COMPANY = "Tienda Demo";
    public static final String OWNER_EMAIL = "dueno@tiendademo.test";
    public static final String AGENT_1_EMAIL = "laura@tiendademo.test";
    public static final String AGENT_2_EMAIL = "pedro@tiendademo.test";
    /** Contraseña de las tres cuentas demo (documentada en el README; solo desarrollo local). */
    public static final String DEMO_PASSWORD = "Demo-Unicanal-2026";

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    private static final String ACCOUNT = "demo";

    private final AuthService authService;
    private final AgentService agentService;
    private final UserRepository users;
    private final InboundMessageProcessor processor;
    private final MessageRepository messages;
    private final QuickReplyRepository quickReplies;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    public DevDataSeeder(AuthService authService, AgentService agentService, UserRepository users,
            InboundMessageProcessor processor, MessageRepository messages, QuickReplyRepository quickReplies,
            MongoTemplate mongoTemplate, Clock clock) {
        this.authService = authService;
        this.agentService = agentService;
        this.users = users;
        this.processor = processor;
        this.messages = messages;
        this.quickReplies = quickReplies;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** Devuelve true si creó los datos; false si ya existían. */
    public boolean seed() {
        if (users.existsByEmail(OWNER_EMAIL)) {
            log.info("Datos demo: la empresa \"{}\" ya existe, no se crea nada", COMPANY);
            return false;
        }
        Instant now = clock.instant();
        AuthResponse owner = authService.register(new RegisterRequest(COMPANY, "Sofía Ramírez", OWNER_EMAIL,
                DEMO_PASSWORD));
        String tenantId = owner.tenant().id();
        User laura = agentService.create(tenantId, new CreateAgentRequest("Laura Gómez", AGENT_1_EMAIL, DEMO_PASSWORD));
        User pedro = agentService.create(tenantId, new CreateAgentRequest("Pedro Martínez", AGENT_2_EMAIL,
                DEMO_PASSWORD));

        // --- Ana Ríos (WhatsApp): una conversación anterior resuelta y la actual En proceso con respuesta ---
        String anaOld = inbound(tenantId, ChannelType.WHATSAPP, "573001234567", "Ana Ríos",
                "Hola, ¿hacen envíos a Medellín?", now.minus(Duration.ofDays(20)));
        outbound(tenantId, anaOld, laura.getId(), "¡Hola Ana! Sí, enviamos a todo el país en 2 a 3 días hábiles.",
                now.minus(Duration.ofDays(20)).plusSeconds(240), DeliveryStatus.READ, null);
        resolve(tenantId, anaOld);
        String ana = inbound(tenantId, ChannelType.WHATSAPP, "573001234567", "Ana Ríos",
                "¿Tienen tallas M disponibles?", now.minus(Duration.ofMinutes(12)));
        outbound(tenantId, ana, laura.getId(), "¡Hola Ana! Sí, tenemos talla M en negro y azul. ¿Cuál prefieres?",
                now.minus(Duration.ofMinutes(9)), DeliveryStatus.DELIVERED, null);
        assign(tenantId, ana, laura.getId());

        // --- Tienda Sol (Instagram): En proceso y marcada como venta ---
        String sol = inbound(tenantId, ChannelType.INSTAGRAM, "tiendasol.co", "Tienda Sol",
                "Quiero cotizar 50 unidades", now.minus(Duration.ofHours(2)));
        outbound(tenantId, sol, pedro.getId(), "Con gusto. Por 50 unidades el precio es $25.000 c/u, con envío incluido.",
                now.minus(Duration.ofHours(2)).plusSeconds(300), DeliveryStatus.READ, null);
        assign(tenantId, sol, pedro.getId());
        mongoTemplate.updateFirst(byId(tenantId, sol),
                Update.update("isSale", true).set("saleAmount", new Decimal128(new java.math.BigDecimal("1250000"))),
                Conversation.class);

        // --- Carlos P. (Facebook): En proceso, con una respuesta que FALLÓ (botón Reintentar) ---
        String carlos = inbound(tenantId, ChannelType.FACEBOOK, "carlos.p.fb", "Carlos P.",
                "¿Cuánto cuesta el envío?", now.minus(Duration.ofMinutes(30)));
        setStatus(tenantId, carlos, ConversationStatus.IN_PROGRESS);
        assign(tenantId, carlos, pedro.getId());
        outbound(tenantId, carlos, pedro.getId(), "El envío dentro de la ciudad cuesta $8.000.",
                now.minus(Duration.ofMinutes(25)), DeliveryStatus.FAILED, "No se pudo enviar el mensaje por Facebook");

        // --- María D. (WhatsApp): Resuelto ---
        String maria = inbound(tenantId, ChannelType.WHATSAPP, "573109876543", "María D.",
                "¿Ya despacharon mi pedido?", now.minus(Duration.ofDays(1)));
        outbound(tenantId, maria, laura.getId(), "Sí, salió ayer. Te debe llegar hoy.",
                now.minus(Duration.ofDays(1)).plusSeconds(600), DeliveryStatus.READ, null);
        inbound(tenantId, ChannelType.WHATSAPP, "573109876543", "María D.", "Gracias, ya recibí el pedido",
                now.minus(Duration.ofHours(3)));
        assign(tenantId, maria, laura.getId());
        resolve(tenantId, maria);

        // --- Jorge V. (Instagram): Nuevo, sin asignar y EN RIESGO (lleva 40 min sin respuesta) ---
        String jorge = inbound(tenantId, ChannelType.INSTAGRAM, "jorge.v", "Jorge V.", "Hola, vi su publicación",
                now.minus(Duration.ofMinutes(40)));
        assign(tenantId, jorge, null);
        mongoTemplate.updateFirst(byId(tenantId, jorge), Update.update("atRisk", true).set("atRiskSince", now),
                Conversation.class);

        // --- Luisa M. (WhatsApp): la ventana de 24 h ya se cerró (escribió hace 26 h) ---
        String luisa = inbound(tenantId, ChannelType.WHATSAPP, "573157778899", "Luisa M.",
                "¿Hacen domicilios el domingo?", now.minus(Duration.ofHours(26)));
        outbound(tenantId, luisa, laura.getId(), "Hola Luisa, los domingos no hacemos domicilios. ¡Te esperamos el lunes!",
                now.minus(Duration.ofHours(26)).plusSeconds(120), DeliveryStatus.READ, null);
        inbound(tenantId, ChannelType.WHATSAPP, "573157778899", "Luisa M.", "Listo, entonces el lunes les escribo",
                now.minus(Duration.ofHours(25)).minus(Duration.ofMinutes(50)));
        assign(tenantId, luisa, laura.getId());

        // --- Respuestas rápidas ---
        quickReplies.save(new QuickReply(tenantId, "Horarios",
                "Atendemos de lunes a sábado de 8:00 a. m. a 7:00 p. m."));
        quickReplies.save(new QuickReply(tenantId, "Envíos",
                "Hacemos envíos a todo el país. En la ciudad cuesta $8.000 y llega el mismo día."));
        quickReplies.save(new QuickReply(tenantId, "Medios de pago",
                "Puedes pagar con Nequi, Daviplata, transferencia Bancolombia o en efectivo contra entrega."));

        log.info("Datos demo creados: empresa \"{}\" (dueño {}, agentes {} y {})", COMPANY, OWNER_EMAIL,
                AGENT_1_EMAIL, AGENT_2_EMAIL);
        return true;
    }

    // --- utilidades ---

    /** El cliente escribe: mismo procesamiento que un webhook (contacto, conversación, contadores, eventos). */
    private String inbound(String tenantId, ChannelType channel, String externalId, String name, String text,
            Instant at) {
        return processor.process(tenantId, new InboundMessage(channel, ACCOUNT, externalId, name,
                "demo-in-" + UUID.randomUUID(), MessageType.TEXT, text, at)).conversationId();
    }

    /**
     * Respuesta de un agente guardada como ya enviada (sin llamar a ningún canal), dejando la conversación igual
     * que la dejaría un envío real: vista previa, última actividad, primera respuesta y NEW → IN_PROGRESS. Un
     * envío fallido no cuenta como respuesta (decisión 13).
     */
    private void outbound(String tenantId, String conversationId, String agentId, String text, Instant at,
            DeliveryStatus status, String failureReason) {
        Message message = new Message(tenantId, conversationId, Direction.OUTBOUND, MessageType.TEXT, text,
                status == DeliveryStatus.FAILED ? null : "demo-out-" + UUID.randomUUID(), agentId, at, status);
        Message saved = messages.save(message);
        if (status == DeliveryStatus.FAILED) {
            mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(saved.getId())),
                    Update.update("failureReason", failureReason).set("failureCode", ChannelSendErrors.FAILED),
                    Message.class);
        }
        Update update = new Update().max("lastMessageAt", at);
        if (status != DeliveryStatus.FAILED) {
            update.max("lastOutboundAt", at).min("firstResponseAt", at);
        }
        mongoTemplate.updateFirst(byId(tenantId, conversationId), update, Conversation.class);
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(conversationId).and("lastMessageAt").is(at)),
                Update.update("lastMessagePreview", text), Conversation.class);
        if (status != DeliveryStatus.FAILED) {
            mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(conversationId)
                            .and("status").is(ConversationStatus.NEW)),
                    Update.update("status", ConversationStatus.IN_PROGRESS), Conversation.class);
        }
        // El agente ya leyó lo anterior
        mongoTemplate.updateFirst(byId(tenantId, conversationId), Update.update("unreadCount", 0), Conversation.class);
    }

    private void assign(String tenantId, String conversationId, String agentId) {
        mongoTemplate.updateFirst(byId(tenantId, conversationId), Update.update("assignedTo", agentId),
                Conversation.class);
    }

    private void setStatus(String tenantId, String conversationId, ConversationStatus status) {
        mongoTemplate.updateFirst(byId(tenantId, conversationId), Update.update("status", status).set("open", true),
                Conversation.class);
    }

    private void resolve(String tenantId, String conversationId) {
        mongoTemplate.updateFirst(byId(tenantId, conversationId),
                Update.update("status", ConversationStatus.RESOLVED).set("open", false).set("unreadCount", 0),
                Conversation.class);
    }

    private static Query byId(String tenantId, String conversationId) {
        return Query.query(Criteria.where("_id").is(conversationId).and("tenantId").is(tenantId));
    }
}
