package com.unicanal.contact;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

/** Toda consulta lleva tenantId: el aislamiento entre empresas se garantiza en la firma. */
public interface ContactRepository extends MongoRepository<Contact, String> {

    Optional<Contact> findByIdAndTenantId(String id, String tenantId);

    /** Busca por una clave "CANAL:externalId" (ver {@link Contact#identityKey}). */
    @Query("{ 'tenantId': ?0, 'identityKeys': ?1 }")
    Optional<Contact> findByTenantIdAndIdentityKey(String tenantId, String identityKey);
}
