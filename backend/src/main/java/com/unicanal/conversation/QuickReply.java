package com.unicanal.conversation;

import java.time.Instant;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;

/** Respuesta rápida (plantilla de texto) compartida por los agentes de una empresa. */
@Document("quickReplies")
@CompoundIndex(name = "tenant_title", def = "{'tenantId': 1, 'title': 1}")
public class QuickReply {

    @MongoId(FieldType.STRING)
    private String id;
    private String tenantId;
    private String title;
    private String text;
    private Instant createdAt;

    protected QuickReply() {
    }

    public QuickReply(String tenantId, String title, String text) {
        this.tenantId = tenantId;
        this.title = title;
        this.text = text;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
