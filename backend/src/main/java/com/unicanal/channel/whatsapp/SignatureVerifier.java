package com.unicanal.channel.whatsapp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Valida la firma {@code X-Hub-Signature-256} de Meta: HMAC-SHA256 del cuerpo crudo con el App Secret,
 * en hexadecimal y con el prefijo "sha256=". Falla cerrado: sin App Secret configurado, o con una
 * cabecera ausente o mal formada, nunca es válida. La comparación es de tiempo constante.
 */
@Component
public class SignatureVerifier {

    private static final String PREFIX = "sha256=";
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final WhatsAppProperties properties;

    public SignatureVerifier(WhatsAppProperties properties) {
        this.properties = properties;
    }

    public boolean isValid(byte[] rawBody, String signatureHeader) {
        String secret = properties.appSecret();
        if (secret.isBlank() || rawBody == null || signatureHeader == null) {
            return false;
        }
        String header = signatureHeader.trim();
        if (!header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return false;
        }
        String provided = header.substring(PREFIX.length()).toLowerCase(java.util.Locale.ROOT);
        String expected = HexFormat.of().formatHex(hmac(secret, rawBody));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 no disponible", e);
        }
    }
}
