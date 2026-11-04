package com.unicanal.channel.whatsapp;

import com.unicanal.channel.WebhookService;
import com.unicanal.channel.WebhookService.WebhookOutcome;
import com.unicanal.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook de WhatsApp. Es público (Meta no envía JWT ni token CSRF); su autenticidad se prueba con la
 * firma X-Hub-Signature-256 sobre los bytes crudos del cuerpo, que se comprueba ANTES de interpretar el JSON.
 */
@RestController
@RequestMapping("/api/webhooks/whatsapp")
public class WhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    private final WhatsAppProperties properties;
    private final SignatureVerifier signatureVerifier;
    private final WhatsAppCloudAdapter adapter;
    private final WebhookService webhookService;

    public WhatsAppWebhookController(WhatsAppProperties properties, SignatureVerifier signatureVerifier,
            WhatsAppCloudAdapter adapter, WebhookService webhookService) {
        this.properties = properties;
        this.signatureVerifier = signatureVerifier;
        this.adapter = adapter;
        this.webhookService = webhookService;
    }

    /** Verificación del webhook: devuelve hub.challenge como texto plano si el token coincide. */
    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        boolean valid = "subscribe".equals(mode) && challenge != null && tokenMatches(verifyToken);
        if (!valid) {
            log.warn("Verificación de webhook rechazada");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
    }

    /**
     * Recepción de mensajes y estados. Responde 200 cuando todo quedó guardado y 5xx si algún evento no
     * pudo guardarse, para que Meta reintente (el reintento es seguro: el procesamiento es idempotente).
     */
    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody byte[] rawBody,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        if (!signatureVerifier.isValid(rawBody, signature)) {
            log.warn("Webhook rechazado: firma inválida o ausente");
            throw new ApiException(HttpStatus.FORBIDDEN, "INVALID_SIGNATURE", "Firma inválida");
        }
        WebhookOutcome outcome = webhookService.handle(adapter, rawBody);
        log.info("Webhook de WhatsApp: {} guardados, {} repetidos, {} estados, {} ignorados, {} fallidos",
                outcome.stored(), outcome.duplicates(), outcome.statusesApplied(), outcome.ignored(),
                outcome.failed());
        if (outcome.failed() > 0) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "WEBHOOK_PROCESSING_FAILED",
                    "No se pudieron procesar todos los eventos");
        }
        return ResponseEntity.ok().build();
    }

    private boolean tokenMatches(String provided) {
        String expected = properties.verifyToken();
        if (expected.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
