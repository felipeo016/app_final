package com.unicanal.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.unicanal.channel.ChannelType;
import com.unicanal.demo.DemoCustomers.Customer;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DemoCustomersTest {

    private static Set<String> allNamesExcept(String free) {
        Set<String> taken = new HashSet<>();
        for (String first : DemoCustomers.FIRST_NAMES) {
            for (String last : DemoCustomers.LAST_NAMES) {
                taken.add(first + " " + last);
            }
        }
        taken.remove(free);
        return taken;
    }

    @Test
    void neverRepeatsANameThatAlreadyExistsEvenWhenOnlyOneIsLeft() {
        Set<String> taken = allNamesExcept("Mariana Cardona");

        Customer customer = DemoCustomers.newCustomer(ChannelType.WHATSAPP, taken, id -> false, new Random(7));

        assertThat(customer.name()).isEqualTo("Mariana Cardona");
    }

    @Test
    void whenEveryCombinationIsTakenItUsesTwoSurnames() {
        Set<String> taken = allNamesExcept("ninguno");

        String name = DemoCustomers.newCustomer(ChannelType.INSTAGRAM, taken, id -> false, new Random(1)).name();

        assertThat(name.split(" ").length).isGreaterThanOrEqualTo(3);
        assertThat(taken).doesNotContain(name);
    }

    @Test
    void identifiersLookRealAndAvoidOnesAlreadyInUse() {
        Random random = new Random(3);
        Customer whatsapp = DemoCustomers.newCustomer(ChannelType.WHATSAPP, Set.of(), id -> false, random);
        assertThat(whatsapp.externalId()).matches("573\\d{9}");

        Customer instagram = DemoCustomers.newCustomer(ChannelType.INSTAGRAM, Set.of(), id -> false, random);
        assertThat(instagram.externalId()).matches("[a-z]+(\\.[a-z]+)+\\d{2}");

        Customer facebook = DemoCustomers.newCustomer(ChannelType.FACEBOOK, Set.of(), id -> false, random);
        assertThat(facebook.externalId()).startsWith("fb.").matches("fb\\.[a-z.]+\\d{2}");

        // Si el identificador ya existe en la empresa, genera otro
        Set<String> seen = new HashSet<>();
        Customer retried = DemoCustomers.newCustomer(ChannelType.WHATSAPP, Set.of(), id -> seen.add(id) && seen.size() < 3,
                random);
        assertThat(seen).hasSize(3).contains(retried.externalId());
    }

    @Test
    void handlesDropAccentsAndSpaces() {
        String handle = DemoCustomers.externalId(ChannelType.INSTAGRAM, "Miguel Ángel Muñoz", new Random(5));
        assertThat(handle).matches("miguel\\.angel\\.munoz\\d{2}");
    }
}
