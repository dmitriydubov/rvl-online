package ru.projectx.datareference.support;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import ru.projectx.datareference.config.OneCQueueProperties;

@TestConfiguration(proxyBeanMethods = false)
public class RabbitTestcontainer {

    public static final String IMAGE = "rabbitmq:4.1.2-alpine";

    @Bean
    @ServiceConnection
    @ConditionalOnExpression("'${" + TestBroker.URL_PROPERTY + ":}'.isEmpty()")
    public RabbitMQContainer rabbitContainer() {
        return new RabbitMQContainer(IMAGE);
    }

    @Bean
    @ConditionalOnExpression("!'${" + TestBroker.URL_PROPERTY + ":}'.isEmpty()")
    public DynamicPropertyRegistrar externalBroker(Environment environment) {
        String url = environment.getRequiredProperty(TestBroker.URL_PROPERTY);
        return registry -> registry.add("spring.rabbitmq.addresses", () -> url);
    }

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    public Declarables oneCQueues(OneCQueueProperties queues) {
        List<Declarable> declarations = Stream.of(
                        queues.policies(), queues.insuranceContracts(), queues.contragents(),
                        queues.mkb10(), queues.lpuContracts(),
                        queues.programs(), queues.appealTypes(), queues.lpuContacts(), queues.priceLists(),
                        queues.priceListsFinal(), queues.contragentServices(),
                queues.insuredAssociations(), queues.insuredAssociationsFinal())
                .<Declarable>map(name -> QueueBuilder.durable(name).build())
                .toList();
        return new Declarables(declarations);
    }
}
