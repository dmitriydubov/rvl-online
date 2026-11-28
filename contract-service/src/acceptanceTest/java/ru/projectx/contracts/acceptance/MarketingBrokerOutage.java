package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import org.awaitility.Awaitility;
import org.springframework.stereotype.Component;
import org.testcontainers.containers.Container;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import ru.projectx.contracts.config.broker.MarketingBrokerProperties;

@Component
public class MarketingBrokerOutage {

    private static final String VIRTUAL_HOST = "marketing";
    private static final String NO_CONNECTIONS = "{\"max-connections\": 0}";
    private static final String EXPLANATION = "stand: marketing broker outage";
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private final RabbitMQContainer broker;
    private final String orderEventsQueue;
    private final ConsumedQueues consumers;
    private volatile boolean unavailable;

    public MarketingBrokerOutage(RabbitMQContainer broker, MarketingBrokerProperties properties,
            ConsumedQueues consumers) {
        this.broker = broker;
        this.orderEventsQueue = properties.orderEvents().queue();
        this.consumers = consumers;
    }

    void start() {
        unavailable = true;
        control("set_vhost_limits", "-p", VIRTUAL_HOST, NO_CONNECTIONS);
        control("close_all_connections", "-p", VIRTUAL_HOST, EXPLANATION);
        Awaitility.await().atMost(PATIENCE).untilAsserted(() -> {
            assertThat(openConnections()).as("соединения с vhost %s", VIRTUAL_HOST).isZero();
            assertThat(consumers.unsubscribedOrStopped(orderEventsQueue))
                    .as("подписка очереди %s снята", orderEventsQueue).isTrue();
        });
    }

    void end() {
        if (unavailable) {
            control("clear_vhost_limits", "-p", VIRTUAL_HOST);
            consumers.awaitSubscribedOrStopped();
            unavailable = false;
        }
    }

    private long openConnections() {
        return Arrays.stream(control("list_connections", "--quiet", "--no-table-headers", "vhost").split("\n"))
                .map(String::strip)
                .filter(VIRTUAL_HOST::equals)
                .count();
    }

    private String control(String... arguments) {
        String[] command = new String[arguments.length + 1];
        command[0] = "rabbitmqctl";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        try {
            Container.ExecResult result = broker.execInContainer(command);
            assertThat(result.getExitCode()).as("rabbitmqctl %s: %s", arguments[0], result.getStderr()).isZero();
            return result.getStdout();
        }
        catch (IOException failed) {
            throw new IllegalStateException("rabbitmqctl " + arguments[0] + " не выполнен", failed);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("rabbitmqctl " + arguments[0] + " прерван", interrupted);
        }
    }
}
