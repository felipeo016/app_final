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
// Barrido de envíos PENDING huérfanos: solo indexa los mensajes que están (o estuvieron) en PENDING
@CompoundIndex(name = "deliveryStatus_pendingSince", def = "{'deliveryStatus': 1, 'pendingSince': 1}",
        partialFilter = "{ 'pendingSince': { '$exists': true } }")
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
    /** Motivo legible del fallo cuando deliveryStatus es FAILED; se muestra al agente. */
    private String failureReason;
    /** Código estable del fallo (p. ej. CHANNEL_SEND_UNCONFIRMED: puede que el cliente sí lo recibiera). */
    private String failureCode;
    /**
     * Desde cuándo está en PENDING (al enviar o al reintentar); se borra al quedar SENT o FAILED. Un PENDING que
     * dura demasiado es un envío huérfano (el proceso se cayó a mitad): lo cierra {@code PendingSendSweeper}.
     */
    private Instant pendingSince;
    /** Respuesta escrita y enviada por la IA (sin {@code sentBy}): se muestra con la etiqueta "Respuesta automática". */
    private boolean automatic;

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
        this.pendingSince = deliveryStatus == DeliveryStatus.PENDING ? timestamp : null;
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

    public String getFailureReason() {
        return failureReason;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public boolean isAutomatic() {
        return automatic;
    }

    public void markAutomatic() {
        this.automatic = true;
    }
}
