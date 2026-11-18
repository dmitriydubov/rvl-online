package ru.projectx.datareference.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.cucumber.java.ru.Тогда;
import java.time.Duration;
import java.util.List;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import ru.projectx.datareference.config.OneCQueueProperties;
import ru.projectx.datareference.service.integration.OneCConsumerRestart;
import ru.projectx.datareference.service.integration.OneCConsumerSubscriptions;

public class ConsumerRestartSteps {

    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(60);

    private final OneCConsumerRestart restart;
    private final RabbitListenerEndpointRegistry containers;
    private final OneCConsumerSubscriptions subscriptions;
    private final ServiceJournal journal;
    private final List<String> configuredQueues;

    public ConsumerRestartSteps(OneCConsumerRestart restart, RabbitListenerEndpointRegistry containers,
            OneCConsumerSubscriptions subscriptions, ServiceJournal journal, OneCQueueProperties queues) {

        this.restart = restart;
        this.containers = containers;
        this.subscriptions = subscriptions;
        this.journal = journal;
        this.configuredQueues = List.of(
                queues.policies(), queues.insuranceContracts(), queues.contragents(), queues.mkb10(),
                queues.lpuContracts(), queues.programs(), queues.appealTypes(), queues.lpuContacts(),
                queues.priceLists(), queues.priceListsFinal(), queues.contragentServices(),
                queues.insuredAssociations(), queues.insuredAssociationsFinal());
    }

    void restartPass() {
        restart.restartStopped();
        await().atMost(SETTLE_TIMEOUT)
                .alias("каждый запущенный потребитель подписан или остановлен")
                .until(this::everyRunningContainerSubscribedOrStopped);
    }

    @Тогда("в журнале Дата-референса записей уровня ERROR о незапущенном потребителе очереди {string} — {int}")
    public void notStartedErrors(String queue, int expected) {
        assertThat(entries(Level.ERROR, "потребитель очереди " + configured(queue) + " не запущен: "))
                .as("записи ERROR о незапущенном потребителе очереди %s", queue)
                .hasSize(expected);
    }

    @Тогда("в журнале Дата-референса записей уровня INFO о возвращённом потребителе очереди {string} — {int}")
    public void returnedInfos(String queue, int expected) {
        assertThat(entries(Level.INFO, "потребитель очереди " + configured(queue) + " запущен: "))
                .as("записи INFO о возвращённом потребителе очереди %s", queue)
                .hasSize(expected);
    }

    private String configured(String queue) {
        assertThat(configuredQueues).as("очереди 1С:УСК из настроек стенда").contains(queue);
        return queue;
    }

    private List<ILoggingEvent> entries(Level level, String prefix) {
        List<ILoggingEvent> entries = journal.events().stream()
                .filter(event -> event.getLevel() == level && event.getFormattedMessage().startsWith(prefix))
                .toList();
        assertThat(entries).as("записи сервиса о потребителе без приложенного исключения")
                .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        return entries;
    }

    private boolean everyRunningContainerSubscribedOrStopped() {
        return containers.getListenerContainerIds().stream()
                .allMatch(queue -> !containers.getListenerContainer(queue).isRunning() || subscriptions.subscribed(queue));
    }
}
