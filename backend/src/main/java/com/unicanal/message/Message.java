package com.unicanal.message;

import java.time.Instant;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;

/**
 * Mensaje de una conversación. {@code externalId} es el id del mensaje en el canal (wamid en
 * WhatsApp): el índice único parcial {tenantId, externalId} hace idempotente el procesamiento de
 * webhooks reintentados y sirve para localizar el mensaje al llegar un `status`. Las notas
 * internas y los mensajes salientes aún sin id externo no lo tienen y no participan del índice.
 */
@Document("messages")
@CompoundIndex(name = "tenant_conversation_timestamp", def = "{'tenantId': 1, 'conversationId': 1, 'timestamp': -1}")
@CompoundIndex(name = "tenant_externalId_unique", def = "{'tenantId': 1, 'externalId': 1}", unique = true,
        partialFilter = "{ 'externalId': { '$type': 'string' } }")
public class Message {

    @MongoId(FieldType.STRING)
    private String id;
    private String tenantId;
    private String conversationId;
    private Direction direction;
    private MessageType type;
    /**
     * Texto del mensaje o de la nota. Los tipos que aún no se muestran (imagen, audio, video...) se guardan
     * con un texto descriptivo, p. ej. "[Audio recibido]"; la descarga de archivos no está implementada.
     */
    private String content;
    private String externalId;
    /** userId del agente; nulo si lo escribió el cliente. */
    private String sentBy;
    private Instant timestamp;
    private DeliveryStatus deliveryStatus;

    protected Message() {
    }

    public Message(String tenantId, String conversationId, Direction direction, MessageType type, String content,
            String externalId, String sentBy, Instant timestamp, DeliveryStatus deliveryStatus) {
        this.tenantId = tenantId;
        this.conversationId = conversationId;
        this.direction = direction;
        this.type = type;
        this.content = content;
        this.externalId = externalId;
        this.sentBy = sentBy;
        this.timestamp = timestamp;
        this.deliveryStatus = deliveryStatus;
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public Direction getDirection() {
        return direction;
    }

    public MessageType getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getSentBy() {
        return sentBy;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public DeliveryStatus getDeliveryStatus() {
        return deliveryStatus;
    }

    public void setDeliveryStatus(DeliveryStatus deliveryStatus) {
        this.deliveryStatus = deliveryStatus;
    }
}
