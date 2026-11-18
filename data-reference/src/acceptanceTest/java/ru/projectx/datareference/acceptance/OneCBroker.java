package ru.projectx.datareference.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.spring.ScenarioScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import ru.projectx.datareference.config.OneCQueueProperties;

@Component
@ScenarioScope(proxyMode = ScopedProxyMode.NO)
public class OneCBroker {

    private final RabbitTemplate rabbit;
    private final RabbitAdmin admin;
    private final Map<String, String> queuesByStream;
    private final List<Published> published = new ArrayList<>();

    public OneCBroker(RabbitTemplate rabbit, RabbitAdmin admin, OneCQueueProperties queues) {
        this.rabbit = rabbit;
        this.admin = admin;
        this.queuesByStream = Map.of(
                "договоры страхования", queues.insuranceContracts(),
                "полисы", queues.policies(),
                "связи застрахованных", queues.insuredAssociations(),
                "связи застрахованных, финальные", queues.insuredAssociationsFinal());
    }

    public String queueOf(String stream) {
        assertThat(queuesByStream).as("очереди, в которые умеет публиковать шаг").containsKey(stream);
        return queuesByStream.get(stream);
    }

    public void publish(String queue, UUID messageId, byte[] body) {
        rabbit.send(queue, new Message(body.clone(), new MessageProperties()));
        published.add(new Published(queue, messageId, body.clone()));
    }

    public byte[] publishedBody(UUID messageId) {
        return published.stream()
                .filter(message -> message.messageId().equals(messageId))
                .map(Published::body)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalStateException("сценарий не публиковал сообщение " + messageId));
    }

    public List<UUID> publishedTo(String queue) {
        return published.stream().filter(message -> message.queue().equals(queue)).map(Published::messageId).toList();
    }

    public QueueInformation queue(String queue) {
        QueueInformation info = admin.getQueueInfo(queue);
        assertThat(info).as("очередь %s объявлена на брокере", queue).isNotNull();
        return info;
    }

    private record Published(String queue, UUID messageId, byte[] body) {
    }
}
