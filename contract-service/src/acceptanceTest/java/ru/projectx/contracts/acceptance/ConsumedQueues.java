package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.awaitility.Awaitility;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.stereotype.Component;
import ru.projectx.contracts.service.integration.consumer.InboxConsumerPause;
import ru.projectx.contracts.service.integration.consumer.InboxConsumerSubscriptions;
import ru.projectx.contracts.service.integration.consumer.InboxQueueConsumers;
import ru.projectx.contracts.service.leadership.BackgroundLeadership;

@Component
public class ConsumedQueues {

    private static final Duration PATIENCE = Duration.ofSeconds(60);

    private final InboxQueueConsumers consumers;
    private final RabbitListenerEndpointRegistry containers;
    private final InboxConsumerSubscriptions subscriptions;
    private final InboxConsumerPause pause;
    private final BackgroundLeadership leadership;

    public ConsumedQueues(InboxQueueConsumers consumers, RabbitListenerEndpointRegistry containers,
            InboxConsumerSubscriptions subscriptions, InboxConsumerPause pause, BackgroundLeadership leadership) {
        this.consumers = consumers;
        this.containers = containers;
        this.subscriptions = subscriptions;
        this.pause = pause;
        this.leadership = leadership;
    }

    void awaitEveryConsumer() {
        Awaitility.await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(consumers.queuesWithoutConsumer()).as("очереди без подписанного потребителя").isEmpty());
    }

    void awaitSubscribedOrStopped() {
        Awaitility.await().atMost(PATIENCE).until(this::everyRunningContainerSubscribedOrStopped);
    }

    void restoreConsumerOf(String queue) {
        if (!leadership.leader() || pause.paused().isPresent()) {
            return;
        }
        MessageListenerContainer container = containers.getListenerContainer(queue);
        if (container == null) {
            return;
        }
        Awaitility.await().atMost(PATIENCE).ignoreExceptions().untilAsserted(() -> {
            if (!container.isRunning()) {
                container.start();
            }
            assertThat(subscriptions.subscribed(queue)).as("потребитель очереди %s снова подписан", queue).isTrue();
        });
    }

    boolean unsubscribedOrStopped(String queue) {
        return !running(queue) || !subscriptions.subscribed(queue);
    }

    private boolean everyRunningContainerSubscribedOrStopped() {
        return containers.getListenerContainerIds().stream()
                .allMatch(queue -> !running(queue) || subscriptions.subscribed(queue));
    }

    private boolean running(String queue) {
        MessageListenerContainer container = containers.getListenerContainer(queue);
        return container != null && container.isRunning();
    }
}
