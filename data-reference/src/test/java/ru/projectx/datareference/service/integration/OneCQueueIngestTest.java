package ru.projectx.datareference.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static ru.projectx.datareference.support.OneCMessages.messageWithId;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import ru.projectx.datareference.config.OneCQueueProperties;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.repository.inbound.InboxRepository;
import ru.projectx.datareference.repository.inbound.InboundMessageRepository;
import ru.projectx.datareference.support.AmqpIntegrationTest;
import ru.projectx.datareference.support.DatabaseCleaner;
import ru.projectx.datareference.support.RunningListeners;
import tools.jackson.databind.ObjectMapper;

@AmqpIntegrationTest
class OneCQueueIngestTest {

    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OneCQueueProperties queues;

    @Autowired
    private InboundMessageRepository inboundMessageRepository;

    @Autowired
    private InboxRepository inboxRepository;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private RabbitListenerEndpointRegistry listeners;

    @Autowired
    private OneCQueueConsumers consumers;

    @BeforeEach
    void clearTables() {
        databaseCleaner.clear();
    }

    @BeforeEach
    void listenersAreRunning() {
        RunningListeners.assertAllRunning(listeners);
    }

    @Test
    void listenerContainers_areExactlyTheThirteenQueues() {
        assertThat(listeners.getListenerContainerIds()).containsExactlyInAnyOrder(
                queues.policies(), queues.insuranceContracts(), queues.contragents(), queues.mkb10(),
                queues.lpuContracts(), queues.programs(), queues.appealTypes(), queues.lpuContacts(),
                queues.priceLists(), queues.priceListsFinal(), queues.contragentServices(),
                queues.insuredAssociations(), queues.insuredAssociationsFinal());
    }

    @Test
    void everyQueueConsumer_isSubscribedThroughSimpleContainer() {
        assertThat(listeners.getListenerContainers()).allSatisfy(
                container -> assertThat(container).isInstanceOf(SimpleMessageListenerContainer.class));
        await().atMost(DELIVERY_TIMEOUT).untilAsserted(
                () -> assertThat(consumers.queuesWithoutConsumer()).as("очереди без подписанного потребителя").isEmpty());
    }

    @Test
    void messageFromQueue_isStoredAsRawWithItsStream() {
        UUID messageId = UUID.randomUUID();
        String body = messageWithId(messageId);

        rabbitTemplate.convertAndSend(queues.mkb10(), body);

        InboundMessage stored = awaitStored(messageId);
        assertThat(stored.receipt().stream()).isEqualTo(OneCStream.MKB10);
        assertThat(stored.receipt().sourceQueue()).isEqualTo(queues.mkb10());
        assertThat(stored.outcome().status()).isEqualTo(InboundStatus.NEW);
        assertThat(inboxRepository.findById(messageId)).isPresent();
    }

    @Test
    void storedPayload_isTheWholeMessage() {
        UUID messageId = UUID.randomUUID();
        String body = messageWithId(messageId);

        rabbitTemplate.convertAndSend(queues.mkb10(), body);

        assertThat(json.readTree(awaitStored(messageId).payload())).isEqualTo(json.readTree(body));
    }

    @Test
    void redeliveredMessage_isStoredOnce() {
        UUID repeated = UUID.randomUUID();
        UUID next = UUID.randomUUID();

        rabbitTemplate.convertAndSend(queues.mkb10(), messageWithId(repeated));
        rabbitTemplate.convertAndSend(queues.mkb10(), messageWithId(repeated));
        rabbitTemplate.convertAndSend(queues.mkb10(), messageWithId(next));

        awaitStored(next);
        assertThat(inboundMessageRepository.findById(repeated)).isPresent();
        assertThat(inboxRepository.findById(repeated)).isPresent();
    }

    @Test
    void insuranceContractQueue_isBoundToItsOwnStream() {
        UUID messageId = UUID.randomUUID();

        rabbitTemplate.convertAndSend(queues.insuranceContracts(), messageWithId(messageId));

        assertThat(awaitStored(messageId))
                .extracting(inbound -> inbound.receipt().stream(), inbound -> inbound.receipt().sourceQueue())
                .containsExactly(OneCStream.INSURANCE_CONTRACTS, queues.insuranceContracts());
    }

    @Test
    void priceListQueues_shareStreamButKeepTheirOwnQueue() {
        UUID batch = UUID.randomUUID();
        UUID last = UUID.randomUUID();

        rabbitTemplate.convertAndSend(queues.priceLists(), messageWithId(batch));
        rabbitTemplate.convertAndSend(queues.priceListsFinal(), messageWithId(last));

        assertThat(awaitStored(batch))
                .extracting(inbound -> inbound.receipt().stream(), inbound -> inbound.receipt().sourceQueue())
                .containsExactly(OneCStream.PRICE_LISTS, queues.priceLists());
        assertThat(awaitStored(last))
                .extracting(inbound -> inbound.receipt().stream(), inbound -> inbound.receipt().sourceQueue())
                .containsExactly(OneCStream.PRICE_LISTS, queues.priceListsFinal());
    }

    private InboundMessage awaitStored(UUID messageId) {
        await().atMost(DELIVERY_TIMEOUT)
                .until(() -> inboundMessageRepository.findById(messageId).isPresent());
        return inboundMessageRepository.findById(messageId).orElseThrow();
    }
}
