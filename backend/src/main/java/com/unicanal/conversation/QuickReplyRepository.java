package com.unicanal.conversation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/** Toda consulta lleva tenantId. */
public interface QuickReplyRepository extends MongoRepository<QuickReply, String> {

    List<QuickReply> findByTenantIdOrderByTitleAsc(String tenantId);

    Optional<QuickReply> findByIdAndTenantId(String id, String tenantId);

    long deleteByIdAndTenantId(String id, String tenantId);
}
