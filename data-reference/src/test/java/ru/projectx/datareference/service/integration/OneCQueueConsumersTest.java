package ru.projectx.datareference.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.projectx.datareference.config.OneCQueueProperties;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OneCQueueConsumersTest {

    private static final OneCQueueProperties QUEUES = new OneCQueueProperties(
            "q-policies", "q-insurance-contracts", "q-contragents", "q-mkb10", "q-lpu-contracts",
            "q-programs", "q-appeal-types", "q-lpu-contacts", "q-price-lists", "q-price-lists-final",
            "q-contragent-services", "q-insured-associations",
            "q-insured-associations-final");

    @Mock
    private OneCConsumerExpectation expectation;

    @Mock
    private OneCConsumerSubscriptions subscriptions;

    private OneCQueueConsumers consumers;

    @BeforeEach
    void leaderExpectsConsumers() {
        consumers = new OneCQueueConsumers(QUEUES, expectation, subscriptions);
        when(expectation.consumersExpected()).thenReturn(true);
    }

    @Test
    void everyQueueSubscribed_noQueueWithoutConsumer() {
        subscribed(allQueues());

        assertThat(consumers.queuesWithoutConsumer()).isEmpty();
    }

    @Test
    void nothingSubscribedYet_everyQueueIsWithoutConsumerInNameOrder() {
        assertThat(consumers.queuesWithoutConsumer())
                .hasSize(OneCQueue.values().length)
                .containsExactlyInAnyOrderElementsOf(allQueues())
                .isSorted();
    }

    @Test
    void oneQueueLostItsConsumer_onlyThatQueueIsNamed() {
        subscribed(allQueues());
        when(subscriptions.subscribed("q-policies")).thenReturn(false);

        assertThat(consumers.queuesWithoutConsumer()).containsExactly("q-policies");
    }

    @Test
    void consumersNotExpected_noQueueIsNamedEvenWithoutSubscriptions() {
        when(expectation.consumersExpected()).thenReturn(false);

        assertThat(consumers.queuesWithoutConsumer()).isEmpty();
    }

    private void subscribed(Set<String> queues) {
        queues.forEach(queue -> when(subscriptions.subscribed(queue)).thenReturn(true));
    }

    private static Set<String> allQueues() {
        return Set.of("q-policies", "q-insurance-contracts", "q-contragents", "q-mkb10", "q-lpu-contracts",
                "q-programs", "q-appeal-types", "q-lpu-contacts", "q-price-lists", "q-price-lists-final",
                "q-contragent-services", "q-insured-associations", "q-insured-associations-final");
    }
}
