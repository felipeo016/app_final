package com.unicanal.message;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Toda consulta lleva tenantId. */
public interface MessageRepository extends MongoRepository<Message, String> {

    Page<Message> findByTenantIdAndConversationId(String tenantId, String conversationId, Pageable pageable);

    /** Idempotencia de webhooks y actualización de `statuses` por id externo. */
    Optional<Message> findByTenantIdAndExternalId(String tenantId, String externalId);
}
