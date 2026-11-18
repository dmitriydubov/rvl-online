package ru.projectx.datareference.acceptance;

import io.cucumber.spring.CucumberContextConfiguration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import ru.projectx.datareference.config.OneCQueueProperties;
import ru.projectx.datareference.config.leadership.LeaderLockPoolConfig;

@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "projectx.scheduling.enabled=false",
        "spring.rabbitmq.ssl.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "projectx.api.max-concurrent-requests=20"})
@Import(CucumberSpringConfiguration.Containers.class)
public class CucumberSpringConfiguration {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        private static final String POSTGRES_IMAGE = "postgres:16.10-alpine";
        private static final String RABBITMQ_IMAGE = "rabbitmq:4.1.2-alpine";
        private static final String DATA_SOURCE_BEAN = "dataSource";
        static final String TODAY = "acceptance.today";

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer(POSTGRES_IMAGE);
        }

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbitContainer() {
            return new RabbitMQContainer(RABBITMQ_IMAGE);
        }

        @Bean
        @Primary
        StandClock standClock(Environment environment) {
            return Optional.ofNullable(environment.getProperty(TODAY))
                    .map(Containers::today)
                    .map(StandClock::runningOn)
                    .orElseGet(StandClock::new);
        }

        private static LocalDate today(String value) {
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException e) {
                throw new IllegalStateException(
                        TODAY + " (ACCEPTANCE_TODAY) ждёт дату yyyy-MM-dd, а задано: '" + value + "'", e);
            }
        }

        @Bean
        RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
            return new RabbitAdmin(connectionFactory);
        }

        @Bean
        OneCStandQueues oneCStandQueues(ConnectionFactory connectionFactory, OneCQueueProperties queues) {
            OneCStandQueues standQueues = new OneCStandQueues(connectionFactory);
            standQueues.declareAll(List.of(
                    queues.policies(), queues.insuranceContracts(), queues.contragents(), queues.mkb10(),
                    queues.lpuContracts(), queues.programs(), queues.appealTypes(), queues.lpuContacts(),
                    queues.priceLists(), queues.priceListsFinal(), queues.contragentServices(),
                queues.insuredAssociations(), queues.insuredAssociationsFinal()));
            return standQueues;
        }

        @Bean
        static BeanPostProcessor switchableDataSource() {
            DatabaseSwitch availability = new DatabaseSwitch();
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource pool && DATA_SOURCE_BEAN.equals(beanName)) {
                        return new SwitchableDataSource(
                                new LateFirstReadDataSource(new HeldReadsDataSource(pool)), availability);
                    }
                    if (bean instanceof DataSource pool && LeaderLockPoolConfig.LEADER_LOCK_POOL.equals(beanName)) {
                        return new SwitchableDataSource(pool, availability);
                    }
                    return bean;
                }
            };
        }
    }
}
