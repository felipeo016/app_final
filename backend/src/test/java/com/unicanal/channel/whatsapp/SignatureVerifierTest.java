package com.unicanal.channel.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SignatureVerifierTest {

    private static final byte[] BODY = "{\"object\":\"whatsapp_business_account\"}".getBytes(StandardCharsets.UTF_8);

    private SignatureVerifier verifier(String appSecret) {
        return new SignatureVerifier(new WhatsAppProperties("", appSecret, "", "", "", null));
    }

    @Test
    void acceptsTheCorrectSignature() {
        String signature = WebhookTestSupport.sign("secret", BODY);

        assertThat(verifier("secret").isValid(BODY, signature)).isTrue();
    }

    @Test
    void matchesTheRfc4231HmacSha256Vector() {
        // RFC 4231, caso de prueba 2: clave "Jefe", datos "what do ya want for nothing?".
        byte[] data = "what do ya want for nothing?".getBytes(StandardCharsets.UTF_8);
        String expected = "sha256=5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843";

        assertThat(verifier("Jefe").isValid(data, expected)).isTrue();
    }

    @Test
    void rejectsASignatureMadeWithAnotherSecret() {
        String signature = WebhookTestSupport.sign("other-secret", BODY);

        assertThat(verifier("secret").isValid(BODY, signature)).isFalse();
    }

    @Test
    void rejectsABodyThatChangedByASingleByte() {
        String signature = WebhookTestSupport.sign("secret", BODY);
        byte[] tampered = BODY.clone();
        tampered[tampered.length - 2] ^= 1;

        assertThat(verifier("secret").isValid(tampered, signature)).isFalse();
    }

    @Test
    void rejectsMissingOrMalformedHeaders() {
        SignatureVerifier verifier = verifier("secret");
        String hex = WebhookTestSupport.sign("secret", BODY).substring("sha256=".length());

        assertThat(verifier.isValid(BODY, null)).isFalse();
        assertThat(verifier.isValid(BODY, "")).isFalse();
        assertThat(verifier.isValid(BODY, "sha256=")).isFalse();
        assertThat(verifier.isValid(BODY, hex)).isFalse();             // sin prefijo
        assertThat(verifier.isValid(BODY, "sha1=" + hex)).isFalse();   // otro algoritmo
        assertThat(verifier.isValid(BODY, "sha256=" + hex.substring(2))).isFalse(); // truncada
        assertThat(verifier.isValid(BODY, "sha256=zz" + hex)).isFalse();
    }

    @Test
    void failsClosedWithoutAConfiguredAppSecret() {
        // Sin App Secret nada es válido, ni siquiera una firma bien formada.
        assertThat(verifier("").isValid(BODY, WebhookTestSupport.sign("x", BODY))).isFalse();
        assertThat(verifier("").isValid(BODY, "sha256=")).isFalse();
    }

    @Test
    void rejectsANullBody() {
        assertThat(verifier("secret").isValid(null, WebhookTestSupport.sign("secret", BODY))).isFalse();
    }
}
