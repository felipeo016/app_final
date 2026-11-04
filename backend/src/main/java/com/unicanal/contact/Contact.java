package com.unicanal.contact;

import com.unicanal.channel.ChannelType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;

/**
 * Cliente de una empresa. Puede tener una identidad por canal. {@code identityKeys} replica cada
 * identidad como un texto plano "CANAL:externalId" (p. ej. "WHATSAPP:573001234567") y es lo único
 * que se indexa: el índice único {tenantId, identityKeys} impide dos contactos con la misma
 * identidad dentro de una empresa, también ante webhooks concurrentes.
 */
@Document("contacts")
@CompoundIndex(name = "tenant_identity_unique", def = "{'tenantId': 1, 'identityKeys': 1}", unique = true)
@CompoundIndex(name = "tenant_name", def = "{'tenantId': 1, 'name': 1}")
public class Contact {

    @MongoId(FieldType.STRING)
    private String id;
    private String tenantId;
    private String name;
    private List<ContactIdentity> identities = new ArrayList<>();
    private List<String> identityKeys = new ArrayList<>();
    private String notes;
    private Instant createdAt;

    protected Contact() {
    }

    /** Todo contacto nace de un mensaje, así que siempre tiene al menos una identidad. */
    public Contact(String tenantId, String name, ChannelType channel, String externalId) {
        this.tenantId = tenantId;
        this.name = name;
        this.createdAt = Instant.now();
        addIdentity(channel, externalId);
    }

    public static String identityKey(ChannelType channel, String externalId) {
        return channel.name() + ":" + normalizeExternalId(externalId);
    }

    /** Agrega la identidad si aún no existe. Devuelve true si se agregó. */
    public boolean addIdentity(ChannelType channel, String externalId) {
        String key = identityKey(channel, externalId);
        if (identityKeys.contains(key)) {
            return false;
        }
        identities.add(new ContactIdentity(channel, normalizeExternalId(externalId)));
        identityKeys.add(key);
        return true;
    }

    private static String normalizeExternalId(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("El identificador externo del contacto es obligatorio");
        }
        return externalId.trim();
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<ContactIdentity> getIdentities() {
        return List.copyOf(identities);
    }

    public List<String> getIdentityKeys() {
        return List.copyOf(identityKeys);
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
