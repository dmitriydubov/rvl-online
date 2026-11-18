package ru.projectx.datareference.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.rabbitmq.client.Channel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistrar;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import ru.projectx.datareference.config.OneCQueueProperties;
import ru.projectx.datareference.entity.inbound.OneCStream;

@ExtendWith(MockitoExtension.class)
class OneCQueueEndpointsTest {

    private static final OneCQueueProperties QUEUES = new OneCQueueProperties(
            "q-policies", "q-insurance-contracts", "q-contragents", "q-mkb10", "q-lpu-contracts",
            "q-programs", "q-appeal-types", "q-lpu-contacts", "q-price-lists", "q-price-lists-final",
            "q-contragent-services", "q-insured-associations",
            "q-insured-associations-final");

    private static final int QUEUE_COUNT = 13;

    @Mock
    private OneCQueueListener listener;

    @Mock
    private RabbitListenerEndpointRegistrar registrar;

    @Test
    void everyQueue_isDeliveredToTheListenerUnderItsOwnStream() throws Exception {
        Map<String, OneCStream> expected = Map.ofEntries(
                Map.entry("q-policies", OneCStream.POLICIES),
                Map.entry("q-insurance-contracts", OneCStream.INSURANCE_CONTRACTS),
                Map.entry("q-contragents", OneCStream.CONTRAGENTS),
                Map.entry("q-mkb10", OneCStream.MKB10),
                Map.entry("q-lpu-contracts", OneCStream.LPU_CONTRACTS),
                Map.entry("q-programs", OneCStream.PROGRAMS),
                Map.entry("q-appeal-types", OneCStream.APPEAL_TYPES),
                Map.entry("q-lpu-contacts", OneCStream.LPU_CONTACTS),
                Map.entry("q-price-lists", OneCStream.PRICE_LISTS),
                Map.entry("q-price-lists-final", OneCStream.PRICE_LISTS),
                Map.entry("q-contragent-services", OneCStream.CONTRAGENT_SERVICES),
                Map.entry("q-insured-associations", OneCStream.INSURED_ASSOCIATIONS),
                Map.entry("q-insured-associations-final", OneCStream.INSURED_ASSOCIATIONS));

        for (SimpleRabbitListenerEndpoint endpoint : registeredEndpoints()) {
            String queue = onlyQueueOf(endpoint);
            Message message = new Message(new byte[0], new MessageProperties());
            Channel channel = mock(Channel.class);

            ((ChannelAwareMessageListener) endpoint.getMessageListener()).onMessage(message, channel);

            verify(listener).receive(expected.get(queue), message, channel);
        }
    }

    @Test
    void eachQueue_getsItsOwnEndpoint() {
        List<SimpleRabbitListenerEndpoint> endpoints = registeredEndpoints();

        assertThat(endpoints).extracting(OneCQueueEndpointsTest::onlyQueueOf)
                .containsExactlyInAnyOrder(
                        "q-policies", "q-insurance-contracts", "q-contragents", "q-mkb10", "q-lpu-contracts",
                        "q-programs", "q-appeal-types", "q-lpu-contacts", "q-price-lists",
                        "q-price-lists-final", "q-contragent-services", "q-insured-associations",
                        "q-insured-associations-final");
        assertThat(endpoints).extracting(SimpleRabbitListenerEndpoint::getId).doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).isNotBlank());
    }

    @Test
    void endpointId_isItsQueueName() {
        assertThat(registeredEndpoints())
                .allSatisfy(endpoint -> assertThat(endpoint.getId()).isEqualTo(onlyQueueOf(endpoint)));
    }

    @Test
    void endpoints_leaveContainerSettingsToConfiguration() {
        assertThat(registeredEndpoints()).allSatisfy(endpoint -> {
            assertThat(endpoint.getAckMode()).isNull();
            assertThat(endpoint.getConcurrency()).isNull();
        });
    }

    @Test
    void endpoints_doNotStartWithTheContext_theLeaderRoleStartsThem() {
        assertThat(registeredEndpoints())
                .allSatisfy(endpoint -> assertThat(endpoint.getAutoStartup()).isFalse());
    }

    private List<SimpleRabbitListenerEndpoint> registeredEndpoints() {
        new OneCQueueEndpoints(listener, QUEUES).configureRabbitListeners(registrar);

        ArgumentCaptor<RabbitListenerEndpoint> registered = ArgumentCaptor.forClass(RabbitListenerEndpoint.class);
        verify(registrar, times(QUEUE_COUNT)).registerEndpoint(registered.capture());
        return registered.getAllValues().stream().map(SimpleRabbitListenerEndpoint.class::cast).toList();
    }

    private static String onlyQueueOf(SimpleRabbitListenerEndpoint endpoint) {
        assertThat(endpoint.getQueueNames()).hasSize(1);
        return endpoint.getQueueNames().iterator().next();
    }
}
