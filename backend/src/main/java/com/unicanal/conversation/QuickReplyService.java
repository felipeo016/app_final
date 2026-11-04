package com.unicanal.conversation;

import com.unicanal.common.ApiException;
import com.unicanal.conversation.dto.QuickReplyRequest;
import java.util.List;
import org.springframework.stereotype.Service;

/** Respuestas rápidas de la empresa. Todos las leen; solo el OWNER las gestiona (lo exige el controlador). */
@Service
public class QuickReplyService {

    private final QuickReplyRepository repository;

    public QuickReplyService(QuickReplyRepository repository) {
        this.repository = repository;
    }

    public List<QuickReply> list(String tenantId) {
        return repository.findByTenantIdOrderByTitleAsc(tenantId);
    }

    public QuickReply create(String tenantId, QuickReplyRequest request) {
        return repository.save(new QuickReply(tenantId, request.title().strip(), request.text().strip()));
    }

    public QuickReply update(String tenantId, String id, QuickReplyRequest request) {
        QuickReply reply = repository.findByIdAndTenantId(id, tenantId).orElseThrow(QuickReplyService::notFound);
        reply.setTitle(request.title().strip());
        reply.setText(request.text().strip());
        return repository.save(reply);
    }

    public void delete(String tenantId, String id) {
        if (repository.deleteByIdAndTenantId(id, tenantId) == 0) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("QUICK_REPLY_NOT_FOUND", "Respuesta rápida no encontrada");
    }
}
