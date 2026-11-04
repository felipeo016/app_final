package com.unicanal.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.unicanal.channel.ChannelType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ConversationTest {

    private Conversation conversation() {
        return new Conversation("t1", "c1", ChannelType.WHATSAPP);
    }

    @Test
    void newConversationIsNewAndOpen() {
        Conversation conversation = conversation();

        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.NEW);
        assertThat(conversation.isOpen()).isTrue();
        assertThat(conversation.getUnreadCount()).isZero();
        assertThat(conversation.isSale()).isFalse();
        assertThat(conversation.isAtRisk()).isFalse();
    }

    @Test
    void resolvingClosesTheConversation() {
        Conversation conversation = conversation();

        conversation.setStatus(ConversationStatus.RESOLVED);

        assertThat(conversation.isOpen()).isFalse();
    }

    @Test
    void nonResolvedStatusesKeepItOpen() {
        Conversation conversation = conversation();

        conversation.setStatus(ConversationStatus.IN_PROGRESS);
        assertThat(conversation.isOpen()).isTrue();

        conversation.setStatus(ConversationStatus.RESOLVED);
        conversation.setStatus(ConversationStatus.IN_PROGRESS);
        assertThat(conversation.isOpen()).isTrue();
    }

    @Test
    void markSaleKeepsAmountOnlyWhileItIsASale() {
        Conversation conversation = conversation();

        conversation.markSale(true, new BigDecimal("150.50"));
        assertThat(conversation.isSale()).isTrue();
        assertThat(conversation.getSaleAmount()).isEqualByComparingTo("150.50");

        conversation.markSale(false, new BigDecimal("150.50"));
        assertThat(conversation.isSale()).isFalse();
        assertThat(conversation.getSaleAmount()).isNull();
    }
}
