package com.unicanal.conversation;

import com.unicanal.channel.ChannelType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;

/**
 * Conversación por episodio: un contacto tiene como máximo UNA conversación abierta por canal
 * (índice único parcial con {@code open: true}). Al pasar a RESOLVED se cierra ({@code open =
 * false}) y el siguiente mensaje del cliente en ese canal abre una conversación nueva.
 */
@Document("conversations")
@CompoundIndex(name = "tenant_status_lastMessage", def = "{'tenantId': 1, 'status': 1, 'lastMessageAt': -1}")
@CompoundIndex(name = "tenant_assigned_status", def = "{'tenantId': 1, 'assignedTo': 1, 'status': 1}")
@CompoundIndex(name = "tenant_contact_channel_open_unique", def = "{'tenantId': 1, 'contactId': 1, 'channel': 1}",
        unique = true, partialFilter = "{ 'open': true }")
@CompoundIndex(name = "tenant_contact_createdAt", def = "{'tenantId': 1, 'contactId': 1, 'createdAt': -1}")
@CompoundIndex(name = "tenant_atRisk", def = "{'tenantId': 1, 'atRisk': 1}")
public class Conversation {

    @MongoId(FieldType.STRING)
    private String id;
    private String tenantId;
    private String contactId;
    private ChannelType channel;
    private ConversationStatus status;
    /** Invariante: open == (status != RESOLVED). Solo se cambia a través de {@link #setStatus}. */
    private boolean open;
    private String assignedTo;
    private Instant lastMessageAt;
    private Instant lastInboundAt;
    private Instant lastOutboundAt;
    private Instant firstResponseAt;
    private String lastMessagePreview;
    private int unreadCount;
    @Field("isSale")
    private boolean sale;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal saleAmount;
    private List<String> tags = new ArrayList<>();
    /** Alerta "cero mensajes perdidos": el cliente espera respuesta hace más de riskAlertMinutes. */
    private boolean atRisk;
    /** Cuándo se marcó en riesgo; nulo si no lo está. */
    private Instant atRiskSince;
    /** Pasada de la alerta que la marcó: permite saber qué conversaciones cambió una actualización masiva. */
    private String atRiskRunId;
    private Instant createdAt;

    protected Conversation() {
    }

    public Conversation(String tenantId, String contactId, ChannelType channel) {
        this.tenantId = tenantId;
        this.contactId = contactId;
        this.channel = channel;
        this.status = ConversationStatus.NEW;
        this.open = true;
        this.createdAt = Instant.now();
    }

    /**
     * Cambia el estado y mantiene {@code open}. Reabrir una conversación RESOLVED puede chocar con
     * el índice único si el contacto ya tiene otra abierta en el canal: el guardado lanzará
     * DuplicateKeyException y el servicio debe traducirlo a 409.
     */
    public void setStatus(ConversationStatus status) {
        this.status = status;
        this.open = status != ConversationStatus.RESOLVED;
    }

    public void markSale(boolean sale, BigDecimal amount) {
        this.sale = sale;
        this.saleAmount = sale ? amount : null;
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getContactId() {
        return contactId;
    }

    public ChannelType getChannel() {
        return channel;
    }

    public ConversationStatus getStatus() {
        return status;
    }

    public boolean isOpen() {
        return open;
    }

    public String getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(String assignedTo) {
        this.assignedTo = assignedTo;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(Instant lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
    }

    public Instant getLastInboundAt() {
        return lastInboundAt;
    }

    public void setLastInboundAt(Instant lastInboundAt) {
        this.lastInboundAt = lastInboundAt;
    }

    public Instant getLastOutboundAt() {
        return lastOutboundAt;
    }

    public void setLastOutboundAt(Instant lastOutboundAt) {
        this.lastOutboundAt = lastOutboundAt;
    }

    public Instant getFirstResponseAt() {
        return firstResponseAt;
    }

    public void setFirstResponseAt(Instant firstResponseAt) {
        this.firstResponseAt = firstResponseAt;
    }

    public String getLastMessagePreview() {
        return lastMessagePreview;
    }

    public void setLastMessagePreview(String lastMessagePreview) {
        this.lastMessagePreview = lastMessagePreview;
    }

    public int getUnreadCount() {
        return unreadCount;
    }

    public void setUnreadCount(int unreadCount) {
        this.unreadCount = unreadCount;
    }

    public boolean isSale() {
        return sale;
    }

    public BigDecimal getSaleAmount() {
        return saleAmount;
    }

    public List<String> getTags() {
        return List.copyOf(tags);
    }

    public void setTags(List<String> tags) {
        this.tags = new ArrayList<>(tags);
    }

    public boolean isAtRisk() {
        return atRisk;
    }

    public void setAtRisk(boolean atRisk) {
        this.atRisk = atRisk;
    }

    public Instant getAtRiskSince() {
        return atRiskSince;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
