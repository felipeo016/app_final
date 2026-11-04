package com.unicanal.channel.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WindowPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    @Test
    void isOpenWithinTwentyFourHours() {
        assertThat(WindowPolicy.isOpen(NOW.minus(Duration.ofMinutes(5)), NOW)).isTrue();
        assertThat(WindowPolicy.isOpen(NOW.minus(Duration.ofHours(23)).minusSeconds(59 * 60), NOW)).isTrue();
    }

    @Test
    void closesAtExactlyTwentyFourHours() {
        assertThat(WindowPolicy.isOpen(NOW.minus(Duration.ofHours(24)).plusMillis(1), NOW)).isTrue();
        assertThat(WindowPolicy.isOpen(NOW.minus(Duration.ofHours(24)), NOW)).isFalse();
        assertThat(WindowPolicy.isOpen(NOW.minus(Duration.ofHours(25)), NOW)).isFalse();
    }

    @Test
    void isClosedWhenTheCustomerNeverWrote() {
        assertThat(WindowPolicy.isOpen(null, NOW)).isFalse();
    }

    @Test
    void remainingTimeCountsDownAndNeverGoesNegative() {
        assertThat(WindowPolicy.remaining(NOW.minus(Duration.ofHours(20)), NOW)).isEqualTo(Duration.ofHours(4));
        assertThat(WindowPolicy.remaining(NOW.minus(Duration.ofHours(30)), NOW)).isEqualTo(Duration.ZERO);
        assertThat(WindowPolicy.remaining(null, NOW)).isEqualTo(Duration.ZERO);
    }
}
