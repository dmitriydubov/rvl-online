package ru.projectx.datareference.service.onec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.projectx.datareference.support.OneCMessages.envelope;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundReceipt;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.exception.OneCFieldException;
import ru.projectx.datareference.mapper.ImportResult;
import ru.projectx.datareference.mapper.StreamMapper;
import ru.projectx.datareference.service.inbound.InboundMessageService;
import ru.projectx.datareference.service.inbound.OneCMessageApplied;
import ru.projectx.datareference.service.leadership.LeadershipFence;
import ru.projectx.datareference.service.leadership.LeadershipLostException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class ReferenceImportDispatchTest {

    private static final UUID MESSAGE_ID = UUID.fromString("7d2b1f0e-0000-4000-8000-0000000000bb");
    private static final String MKB10_DATA = "{\"code\":\"A00\"}";

    private final JsonMapper json = JsonMapper.builder().build();

    @Mock
    private StreamMapper mkb10Mapper;

    @Mock
    private StreamHandler priceListHandler;

    @Mock
    private InboundMessageService inboundMessageService;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private SupersededParentWaits supersededWaits;

    @Mock
    private ParentArrivals parentArrivals;

    private final LeadershipFence fence = mock(LeadershipFence.class);

    @Test
    void instanceThatLostTheLeaderLock_importsNothing() {
        doThrow(new LeadershipLostException("0b5f3c1e-7a2d-4e8b-9c6f-1d2e3f4a5b6c")).when(fence).holdUntilCommit();

        ReferenceImportService service = new ReferenceImportService(
                List.of(), List.of(), inboundMessageService, supersededWaits, parentArrivals, json, events, fence);

        assertThatThrownBy(() -> service.importMessage(message(OneCStream.MKB10, "{\"data\":" + MKB10_DATA + "}")))
                .isInstanceOf(LeadershipLostException.class);

        verifyNoInteractions(inboundMessageService, events, supersededWaits);
    }

    @Test
    void messageOfMappedStream_goesToItsMapper_withDataOfEnvelope() {
        when(mkb10Mapper.stream()).thenReturn(OneCStream.MKB10);
        when(priceListHandler.stream()).thenReturn(OneCStream.PRICE_LISTS);
        when(mkb10Mapper.apply(any())).thenReturn(ImportResult.applied());

        ImportOutcome result = service().importMessage(message(OneCStream.MKB10, envelope(MKB10_DATA)));

        assertThat(result).isEqualTo(ImportOutcome.applied());
        verify(mkb10Mapper).apply(json.readValue(MKB10_DATA, ObjectNode.class));
        verify(inboundMessageService).markProcessed(MESSAGE_ID);
        verify(priceListHandler, never()).handle(any(), any());
        verify(events).publishEvent(new OneCMessageApplied(OneCStream.MKB10));
    }

    @Test
    void messageAwaitingItsParent_publishesNothing() {
        when(mkb10Mapper.stream()).thenReturn(OneCStream.MKB10);
        when(priceListHandler.stream()).thenReturn(OneCStream.PRICE_LISTS);
        InboundMessage message = message(OneCStream.PRICE_LISTS, envelope(MKB10_DATA));
        when(priceListHandler.handle(any(), any())).thenReturn(ImportOutcome.awaitingParent("ждёт родителя: услуга"));

        ImportOutcome result = service().importMessage(message);

        assertThat(result).isEqualTo(ImportOutcome.awaitingParent("ждёт родителя: услуга"));
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void messageOfStreamWithOwnHandler_goesToThatHandler() {
        when(mkb10Mapper.stream()).thenReturn(OneCStream.MKB10);
        when(priceListHandler.stream()).thenReturn(OneCStream.PRICE_LISTS);
        InboundMessage message = message(OneCStream.PRICE_LISTS, envelope("{\"cid\":\"c1\"}"));
        when(priceListHandler.handle(any(), any())).thenReturn(ImportOutcome.skipped("ждёт финального"));

        ImportOutcome result = service().importMessage(message);

        assertThat(result).isEqualTo(ImportOutcome.skipped("ждёт финального"));
        verify(priceListHandler).handle(message, json.readValue("{\"cid\":\"c1\"}", ObjectNode.class));
        verify(mkb10Mapper, never()).apply(any());
        verify(events).publishEvent(new OneCMessageApplied(OneCStream.PRICE_LISTS));
    }

    @Test
    void failedMessage_publishesNoEvent() {
        when(mkb10Mapper.stream()).thenReturn(OneCStream.MKB10);
        when(priceListHandler.stream()).thenReturn(OneCStream.PRICE_LISTS);
        when(mkb10Mapper.apply(any())).thenThrow(new OneCFieldException("нет кода"));
        ReferenceImportService service = service();

        assertThatThrownBy(() -> service.importMessage(message(OneCStream.MKB10, envelope(MKB10_DATA))))
                .isInstanceOf(OneCFieldException.class);
        verifyNoInteractions(events);
    }

    @Test
    void twoHandlersForOneStream_failAtStartup() {
        when(mkb10Mapper.stream()).thenReturn(OneCStream.MKB10);
        when(priceListHandler.stream()).thenReturn(OneCStream.MKB10);

        assertThatThrownBy(this::service).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void erasedPayload_isContractViolation() {
        assertEnvelopeRejected(null, "тело сообщения стёрто по сроку хранения, разбирать нечего");
    }

    @Test
    void erasedPayloadOfStreamWithOwnHandler_isRejectedBeforeItsHandler() {
        when(mkb10Mapper.stream()).thenReturn(OneCStream.MKB10);
        when(priceListHandler.stream()).thenReturn(OneCStream.PRICE_LISTS);
        ReferenceImportService service = service();

        assertThatThrownBy(() -> service.importMessage(message(OneCStream.PRICE_LISTS, null)))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("тело сообщения стёрто по сроку хранения, разбирать нечего");
        verify(priceListHandler, never()).handle(any(), any());
    }

    @Test
    void envelopeWithoutData_isContractViolation() {
        assertEnvelopeRejected("{\"metadata\":{}}", "в конверте сообщения нет объекта data");
    }

    @Test
    void dataThatIsNotObject_isContractViolation() {
        assertEnvelopeRejected("{\"data\":[1]}", "в конверте сообщения нет объекта data");
    }

    @Test
    void bodyThatIsNotJson_isContractViolation_withoutQuotingTheBody() {
        ReferenceImportService service = new ReferenceImportService(List.of(), List.of(), inboundMessageService, supersededWaits, parentArrivals, json, events, fence);

        assertThatThrownBy(() -> service.importMessage(message(OneCStream.MKB10, "{не json")))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("тело сообщения не читается как JSON")
                .cause()
                .hasMessageStartingWith("tools.jackson.")
                .hasMessageNotContaining("не json");
    }

    private void assertEnvelopeRejected(String payload, String reason) {
        ReferenceImportService service = new ReferenceImportService(List.of(), List.of(), inboundMessageService, supersededWaits, parentArrivals, json, events, fence);

        assertThatThrownBy(() -> service.importMessage(message(OneCStream.MKB10, payload)))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage(reason);
    }

    private ReferenceImportService service() {
        return new ReferenceImportService(List.of(mkb10Mapper), List.of(priceListHandler), inboundMessageService, supersededWaits, parentArrivals, json, events, fence);
    }

    private static InboundMessage message(OneCStream stream, String payload) {
        return InboundMessage.receive(new InboundReceipt(MESSAGE_ID, stream, "очередь", Instant.EPOCH), payload);
    }
}
