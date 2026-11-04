package com.unicanal.channel;

import static org.assertj.core.api.Assertions.assertThat;

import com.unicanal.message.DeliveryStatus;
import org.junit.jupiter.api.Test;

class DeliveryStatusUpdaterTest {

    @Test
    void statusesOnlyAdvanceAndNeverGoBackwards() {
        assertThat(DeliveryStatusUpdater.replaceableStatuses(DeliveryStatus.SENT))
                .containsExactly(DeliveryStatus.PENDING);
        assertThat(DeliveryStatusUpdater.replaceableStatuses(DeliveryStatus.DELIVERED))
                .containsExactly(DeliveryStatus.PENDING, DeliveryStatus.SENT);
        assertThat(DeliveryStatusUpdater.replaceableStatuses(DeliveryStatus.READ))
                .containsExactly(DeliveryStatus.PENDING, DeliveryStatus.SENT, DeliveryStatus.DELIVERED);
    }

    @Test
    void aFailureCannotOverwriteADeliveredOrReadMessage() {
        assertThat(DeliveryStatusUpdater.replaceableStatuses(DeliveryStatus.FAILED))
                .containsExactly(DeliveryStatus.PENDING, DeliveryStatus.SENT)
                .doesNotContain(DeliveryStatus.DELIVERED, DeliveryStatus.READ);
    }

    @Test
    void pendingIsNeverSetByAChannelEvent() {
        assertThat(DeliveryStatusUpdater.replaceableStatuses(DeliveryStatus.PENDING)).isEmpty();
    }
}
