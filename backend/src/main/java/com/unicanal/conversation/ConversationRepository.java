package com.unicanal.conversation;

import com.unicanal.channel.ChannelType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Toda consulta lleva tenantId. Los listados con filtros (Paso 5) usarán MongoTemplate/Criteria. */
public interface ConversationRepository extends MongoRepository<Conversation, String> {

    Optional<Conversation> findByIdAndTenantId(String id, String tenantId);

    /** La conversación abierta del contacto en el canal (a lo sumo una, por el índice único parcial). */
    Optional<Conversation> findByTenantIdAndContactIdAndChannelAndOpenTrue(String tenantId, String contactId,
            ChannelType channel);

    /** Historial de conversaciones del contacto, de la más reciente a la más antigua. */
    List<Conversation> findByTenantIdAndContactIdOrderByCreatedAtDesc(String tenantId, String contactId);
}
