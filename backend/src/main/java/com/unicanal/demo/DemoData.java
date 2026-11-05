package com.unicanal.demo;

import com.unicanal.ai.AiMode;
import com.unicanal.auth.AuthService;
import com.unicanal.auth.dto.AuthResponse;
import com.unicanal.auth.dto.RegisterRequest;
import com.unicanal.channel.ChannelSendErrors;
import com.unicanal.channel.ChannelType;
import com.unicanal.channel.InboundMessage;
import com.unicanal.channel.InboundMessageProcessor;
import com.unicanal.contact.Contact;
import com.unicanal.conversation.Conversation;
import com.unicanal.conversation.ConversationStatus;
import com.unicanal.conversation.QuickReply;
import com.unicanal.conversation.QuickReplyRepository;
import com.unicanal.message.DeliveryStatus;
import com.unicanal.message.Direction;
import com.unicanal.message.Message;
import com.unicanal.message.MessageRepository;
import com.unicanal.message.MessageType;
import com.unicanal.tenant.Tenant;
import com.unicanal.tenant.TenantSettings;
import com.unicanal.user.AgentService;
import com.unicanal.user.Role;
import com.unicanal.user.User;
import com.unicanal.user.UserRepository;
import com.unicanal.user.dto.CreateAgentRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bson.types.Decimal128;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Datos de la empresa demo "Tienda Demo" (solo perfil dev). Los usa {@link DevDataSeeder} al arrancar y el
 * botón "Restaurar datos demo" del Simulador.
 *
 * <p>Nunca llama a un canal real: los mensajes de los clientes entran por el mismo {@link InboundMessageProcessor}
 * que usan los webhooks (sin red) y las respuestas de los agentes se guardan directamente como ya enviadas.
 */
@Component
@Profile("dev")
public class DemoData {

    public static final String COMPANY = "Tienda Demo";
    public static final String OWNER_NAME = "Sofía Ramírez";
    public static final String OWNER_EMAIL = "dueno@tiendademo.test";
    public static final String AGENT_1_NAME = "Laura Gómez";
    public static final String AGENT_1_EMAIL = "laura@tiendademo.test";
    public static final String AGENT_2_NAME = "Pedro Martínez";
    public static final String AGENT_2_EMAIL = "pedro@tiendademo.test";
    /** Contraseña de las tres cuentas demo (documentada en el README; solo desarrollo local). */
    public static final String DEMO_PASSWORD = "Demo-Unicanal-2026";

    /**
     * "Información del negocio" de la Tienda Demo, con los mismos datos que las respuestas rápidas y coherente con el
     * guion de la demo en vivo. Las tallas van una por una ("28, 30, 32…", no "28 a 40"): un modelo pequeño lee mal
     * los rangos. Quedan a propósito para el equipo el estado de un pedido, los datos para transferir y las
     * cotizaciones de dotación.
     */
    public static final String BUSINESS_INFO = """
            Tienda Demo vende solo ropa casual para hombre y mujer. No vendemos zapatos ni accesorios.
            Tienda física en Medellín (barrio Laureles, carrera 76 # 34-20). También vendemos por WhatsApp, \
            Instagram y Facebook.

            Horario: lunes a sábado de 8:00 a. m. a 7:00 p. m. Domingos y festivos no abrimos ni hacemos envíos.

            Envíos a toda Colombia:
            - Medellín y su área metropolitana: $8.000. Llega el mismo día si pides antes de las 2:00 p. m.
            - Resto del país: $15.000. Llega en 2 a 4 días hábiles.
            - Pago contra entrega (en efectivo al recibir): solo en Pereira. En las demás ciudades no hay contra \
            entrega.

            Medios de pago: Nequi, Daviplata y transferencia Bancolombia; en Pereira también efectivo contra entrega. \
            No recibimos tarjetas de crédito ni débito. Los datos para transferir los envía un asesor por el chat.

            Productos (precio por unidad):
            - Chaqueta de jean: $129.000. Tallas S, M y L.
            - Camiseta oversize en negro, blanco y gris: $45.000. Tallas S, M, L y XL.
            - Jogger en gris y negro: $79.000. Tallas S, M, L y XL.
            - Pantalón cargo en verde oliva y beige: $99.000. Tallas 28, 30, 32, 34, 36, 38 y 40.
            - Sudadera con capota (hoodie) en negro, gris y beige: $110.000. Tallas S, M, L y XL.
            - Vestido midi floral (el de la última publicación): $95.000. Tallas XS, S, M y L.

            Descuentos: no tenemos descuentos ni promociones, tampoco por cantidad. Los precios son los publicados.
            Dotaciones para empresas y prendas bordadas con logo: las cotiza un asesor.
            Cambios: dentro de los 30 días, con la etiqueta puesta.""";

    /** Una respuesta rápida de la Tienda Demo. */
    public record DemoQuickReply(String title, String text) {
    }

    /**
     * Respuestas rápidas de la Tienda Demo: cubren las preguntas del guion de la demo en vivo, con los mismos datos
     * de {@link #BUSINESS_INFO}. En "Respuesta automática" la IA solo puede enviar una de estas, tal cual.
     */
    public static final List<DemoQuickReply> QUICK_REPLIES = List.of(
            new DemoQuickReply("Horarios",
                    "Atendemos de lunes a sábado de 8:00 a. m. a 7:00 p. m. Domingos y festivos no abrimos."),
            new DemoQuickReply("Festivos",
                    "Los domingos y festivos no abrimos. Te esperamos de lunes a sábado de "
                            + "8:00 a. m. a 7:00 p. m."),
            new DemoQuickReply("Envíos",
                    "Enviamos a toda Colombia. En Medellín y su área metropolitana cuesta $8.000 y llega el mismo día "
                            + "si pides antes de las 2:00 p. m. Al resto del país cuesta $15.000 y llega en 2 a 4 días "
                            + "hábiles."),
            new DemoQuickReply("Contra entrega",
                    "El pago contra entrega (en efectivo al recibir) solo está disponible en Pereira."),
            new DemoQuickReply("Medios de pago",
                    "Puedes pagar con Nequi, Daviplata o transferencia Bancolombia; en Pereira también en efectivo "
                            + "contra entrega. No recibimos tarjetas de crédito ni débito."),
            new DemoQuickReply("Tienda física",
                    "Nuestra tienda física está en Medellín, barrio Laureles (carrera 76 # 34-20). También vendemos "
                            + "por WhatsApp, Instagram y Facebook."),
            new DemoQuickReply("Chaqueta de jean",
                    "La chaqueta de jean vale $129.000 y la tenemos solo en tallas S, M y L."),
            new DemoQuickReply("Camiseta oversize",
                    "La camiseta oversize vale $45.000. Colores: negro, blanco y gris. Tallas: S, M, L y XL."),
            new DemoQuickReply("Jogger",
                    "El jogger vale $79.000. Colores: gris y negro. Tallas: S, M, L y XL."),
            new DemoQuickReply("Pantalón cargo",
                    "El pantalón cargo vale $99.000. Colores: verde oliva y beige. Tallas: 28, 30, 32, 34, 36, 38 "
                            + "y 40."),
            new DemoQuickReply("Sudadera con capota",
                    "La sudadera con capota (hoodie) vale $110.000. Colores: negro, gris y beige. Tallas: S, M, L "
                            + "y XL."),
            new DemoQuickReply("Vestido floral",
                    "El vestido midi floral de la última publicación vale $95.000. Tallas: XS, S, M y L."),
            new DemoQuickReply("Zapatos y accesorios",
                    "Solo vendemos ropa: no tenemos zapatos ni accesorios."),
            new DemoQuickReply("Descuentos",
                    "Por ahora no tenemos descuentos ni promociones, tampoco por cantidad: los precios son los "
                            + "publicados."),
            new DemoQuickReply("Cambios",
                    "Puedes cambiar tu prenda dentro de los 30 días, con la etiqueta puesta."));

    private static final Logger log = LoggerFactory.getLogger(DemoData.class);
    private static final String ACCOUNT = "demo";

    private final AuthService authService;
    private final AgentService agentService;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final InboundMessageProcessor processor;
    private final MessageRepository messages;
    private final QuickReplyRepository quickReplies;
    private final MongoTemplate mongoTemplate;
    private final Clock clock;

    public DemoData(AuthService authService, AgentService agentService, UserRepository users,
            PasswordEncoder passwordEncoder, InboundMessageProcessor processor, MessageRepository messages,
            QuickReplyRepository quickReplies, MongoTemplate mongoTemplate, Clock clock) {
        this.authService = authService;
        this.agentService = agentService;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.processor = processor;
        this.messages = messages;
        this.quickReplies = quickReplies;
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
    }

    /** Empresa de la cuenta demo, si existe. */
    public Optional<String> tenantId() {
        return users.findByEmail(OWNER_EMAIL).map(User::getTenantId);
    }

    /** Crea la empresa demo completa si no existe. Devuelve true si la creó. */
    public boolean createIfMissing() {
        if (users.existsByEmail(OWNER_EMAIL)) {
            log.info("Datos demo: la empresa \"{}\" ya existe, no se crea nada", COMPANY);
            return false;
        }
        AuthResponse owner = authService.register(new RegisterRequest(COMPANY, OWNER_NAME, OWNER_EMAIL, DEMO_PASSWORD));
        String tenantId = owner.tenant().id();
        User laura = agentService.create(tenantId, new CreateAgentRequest(AGENT_1_NAME, AGENT_1_EMAIL, DEMO_PASSWORD));
        User pedro = agentService.create(tenantId, new CreateAgentRequest(AGENT_2_NAME, AGENT_2_EMAIL, DEMO_PASSWORD));
        seedContent(tenantId, laura.getId(), pedro.getId());
        configureAi(tenantId);
        log.info("Datos demo creados: empresa \"{}\" (dueño {}, agentes {} y {})", COMPANY, OWNER_EMAIL,
                AGENT_1_EMAIL, AGENT_2_EMAIL);
        return true;
    }

    /**
     * Deja la empresa demo como recién creada. Conserva la empresa y las tres cuentas demo (mismos ids: las
     * sesiones abiertas siguen valiendo) pero les devuelve nombre, contraseña y estado activo; borra cualquier
     * otro usuario creado durante la demo y todos los contactos, conversaciones, mensajes y respuestas rápidas,
     * y vuelve a crear los del guion. Devuelve el id de la empresa.
     */
    public String restore() {
        Optional<String> existing = tenantId();
        if (existing.isEmpty()) {
            createIfMissing();
            return tenantId().orElseThrow();
        }
        String tenantId = existing.get();
        Query ofTenant = Query.query(Criteria.where("tenantId").is(tenantId));
        mongoTemplate.remove(ofTenant, Message.class);
        mongoTemplate.remove(ofTenant, Conversation.class);
        mongoTemplate.remove(ofTenant, Contact.class);
        mongoTemplate.remove(ofTenant, QuickReply.class);
        mongoTemplate.remove(Query.query(Criteria.where("tenantId").is(tenantId)
                .and("email").nin(List.of(OWNER_EMAIL, AGENT_1_EMAIL, AGENT_2_EMAIL))), User.class);
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(tenantId)),
                Update.update("settings.riskAlertMinutes", TenantSettings.DEFAULT_RISK_ALERT_MINUTES)
                        // IA apagada mientras se cargan los chats de ejemplo: así no responde ninguno
                        .set("settings.aiMode", AiMode.OFF)
                        .set("roundRobinCursor", 0L).set("name", COMPANY),
                Tenant.class);
        resetUser(tenantId, OWNER_EMAIL, OWNER_NAME, Role.OWNER);
        String laura = resetUser(tenantId, AGENT_1_EMAIL, AGENT_1_NAME, Role.AGENT);
        String pedro = resetUser(tenantId, AGENT_2_EMAIL, AGENT_2_NAME, Role.AGENT);
        seedContent(tenantId, laura, pedro);
        configureAi(tenantId);
        log.info("Datos demo restaurados: empresa \"{}\"", COMPANY);
        return tenantId;
    }

    /**
     * IA en modo Sugerencias con la información del negocio de ejemplo. Se aplica DESPUÉS de cargar los chats, para
     * que la IA no responda los mensajes de ejemplo. En la feria, el dueño puede pasarla a "Respuesta automática".
     */
    private void configureAi(String tenantId) {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(tenantId)),
                Update.update("settings.aiMode", AiMode.SUGGEST).set("settings.businessInfo", BUSINESS_INFO),
                Tenant.class);
    }

    /** Devuelve el usuario demo a su estado inicial (o lo vuelve a crear si se borró). Devuelve su id. */
    private String resetUser(String tenantId, String email, String name, Role role) {
        Optional<User> user = users.findByEmail(email).filter(found -> tenantId.equals(found.getTenantId()));
        if (user.isEmpty()) {
            return agentService.create(tenantId, new CreateAgentRequest(name, email, DEMO_PASSWORD)).getId();
        }
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(user.get().getId())),
                Update.update("name", name).set("active", true).set("role", role)
                        .set("passwordHash", passwordEncoder.encode(DEMO_PASSWORD)),
                User.class);
        return user.get().getId();
    }

    /** Conversaciones del prototipo, con horas relativas a "ahora" para que siempre se vean recientes. */
    private void seedContent(String tenantId, String laura, String pedro) {
        Instant now = clock.instant();

        // --- Ana Ríos (WhatsApp): una conversación anterior resuelta y la actual En proceso con respuesta ---
        String anaOld = inbound(tenantId, ChannelType.WHATSAPP, "573001234567", "Ana Ríos",
                "Hola, ¿hacen envíos a Medellín?", now.minus(Duration.ofDays(20)));
        outbound(tenantId, anaOld, laura, "¡Hola Ana! Sí, enviamos a todo el país en 2 a 4 días hábiles.",
                now.minus(Duration.ofDays(20)).plusSeconds(240), DeliveryStatus.READ, null);
        resolve(tenantId, anaOld);
        String ana = inbound(tenantId, ChannelType.WHATSAPP, "573001234567", "Ana Ríos",
                "¿Tienen tallas M disponibles?", now.minus(Duration.ofMinutes(12)));
        outbound(tenantId, ana, laura, "¡Hola Ana! Sí, tenemos talla M en negro y azul. ¿Cuál prefieres?",
                now.minus(Duration.ofMinutes(9)), DeliveryStatus.DELIVERED, null);
        assign(tenantId, ana, laura);

        // --- Tienda Sol (Instagram): En proceso y marcada como venta ---
        String sol = inbound(tenantId, ChannelType.INSTAGRAM, "tiendasol.co", "Tienda Sol",
                "Quiero cotizar 50 unidades", now.minus(Duration.ofHours(2)));
        outbound(tenantId, sol, pedro, "Con gusto. Por 50 unidades el precio es $25.000 c/u, con envío incluido.",
                now.minus(Duration.ofHours(2)).plusSeconds(300), DeliveryStatus.READ, null);
        assign(tenantId, sol, pedro);
        mongoTemplate.updateFirst(byId(tenantId, sol),
                Update.update("isSale", true).set("saleAmount", new Decimal128(new java.math.BigDecimal("1250000"))),
                Conversation.class);

        // --- Carlos P. (Facebook): En proceso, con una respuesta que FALLÓ (botón Reintentar) ---
        String carlos = inbound(tenantId, ChannelType.FACEBOOK, "carlos.p.fb", "Carlos P.",
                "¿Cuánto cuesta el envío?", now.minus(Duration.ofMinutes(30)));
        setStatus(tenantId, carlos, ConversationStatus.IN_PROGRESS);
        assign(tenantId, carlos, pedro);
        outbound(tenantId, carlos, pedro, "El envío dentro de la ciudad cuesta $8.000.",
                now.minus(Duration.ofMinutes(25)), DeliveryStatus.FAILED, "No se pudo enviar el mensaje por Facebook");

        // --- María D. (WhatsApp): Resuelto ---
        String maria = inbound(tenantId, ChannelType.WHATSAPP, "573109876543", "María D.",
                "¿Ya despacharon mi pedido?", now.minus(Duration.ofDays(1)));
        outbound(tenantId, maria, laura, "Sí, salió ayer. Te debe llegar hoy.",
                now.minus(Duration.ofDays(1)).plusSeconds(600), DeliveryStatus.READ, null);
        inbound(tenantId, ChannelType.WHATSAPP, "573109876543", "María D.", "Gracias, ya recibí el pedido",
                now.minus(Duration.ofHours(3)));
        assign(tenantId, maria, laura);
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
        outbound(tenantId, luisa, laura, "Hola Luisa, los domingos no hacemos domicilios. ¡Te esperamos el lunes!",
                now.minus(Duration.ofHours(26)).plusSeconds(120), DeliveryStatus.READ, null);
        inbound(tenantId, ChannelType.WHATSAPP, "573157778899", "Luisa M.", "Listo, entonces el lunes les escribo",
                now.minus(Duration.ofHours(25)).minus(Duration.ofMinutes(50)));
        assign(tenantId, luisa, laura);

        // --- Respuestas rápidas (también son lo único que envía la IA en "Respuesta automática") ---
        QUICK_REPLIES.forEach(reply -> quickReplies.save(new QuickReply(tenantId, reply.title(), reply.text())));
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
        // Ya enviado (o ya fallido): no es un envío en curso, así que no debe tener pendingSince
        Update finished = new Update().unset("pendingSince");
        if (status == DeliveryStatus.FAILED) {
            finished.set("failureReason", failureReason).set("failureCode", ChannelSendErrors.FAILED);
        }
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(saved.getId())), finished, Message.class);
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
