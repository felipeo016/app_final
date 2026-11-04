package com.unicanal.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unicanal.channel.ChannelType;
import org.junit.jupiter.api.Test;

class ContactTest {

    @Test
    void identityKeyHasChannelColonExternalIdFormat() {
        assertThat(Contact.identityKey(ChannelType.WHATSAPP, "573001234567")).isEqualTo("WHATSAPP:573001234567");
    }

    @Test
    void newContactStartsWithItsFirstIdentity() {
        Contact contact = new Contact("t1", "Ana", ChannelType.WHATSAPP, " 573001234567 ");

        assertThat(contact.getIdentityKeys()).containsExactly("WHATSAPP:573001234567");
        assertThat(contact.getIdentities())
                .containsExactly(new ContactIdentity(ChannelType.WHATSAPP, "573001234567"));
    }

    @Test
    void addIdentityAddsOnePerChannelAndIgnoresRepeats() {
        Contact contact = new Contact("t1", "Ana", ChannelType.WHATSAPP, "573001234567");

        assertThat(contact.addIdentity(ChannelType.INSTAGRAM, "ana_ig")).isTrue();
        assertThat(contact.addIdentity(ChannelType.INSTAGRAM, "ana_ig")).isFalse();

        assertThat(contact.getIdentityKeys()).containsExactly("WHATSAPP:573001234567", "INSTAGRAM:ana_ig");
        assertThat(contact.getIdentities()).hasSize(2);
    }

    @Test
    void blankExternalIdIsRejected() {
        assertThatThrownBy(() -> new Contact("t1", "Ana", ChannelType.WHATSAPP, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
