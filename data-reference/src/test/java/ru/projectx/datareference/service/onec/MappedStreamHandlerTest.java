package ru.projectx.datareference.service.onec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.projectx.datareference.entity.inbound.AwaitedParent;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundReceipt;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.entity.inbound.ParentKind;
import ru.projectx.datareference.mapper.ImportResult;
import ru.projectx.datareference.mapper.StreamMapper;
import ru.projectx.datareference.service.inbound.InboundMessageService;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class MappedStreamHandlerTest {

    private static final UUID MESSAGE_ID = UUID.fromString("7d2b1f0e-0000-4000-8000-0000000000aa");
    private static final UUID RECORD = UUID.fromString("5f14a8d2-c3e7-4b90-a1d6-9e2b7c4f0a83");
    private static final AwaitedParent PARENT =
            new AwaitedParent(ParentKind.CONTRAGENT, UUID.fromString("6d0e4a7b-2c95-4f18-8b3d-e9a1c7f50d62"));

    @Mock
    private StreamMapper mapper;

    @Mock
    private InboundMessageService inboundMessageService;

    @Mock
    private SupersededParentWaits supersededWaits;

    @Mock
    private ParentArrivals parentArrivals;

    private final ObjectNode data = JsonMapper.builder().build().createObjectNode().put("code", "A00");

    @Test
    void handlesTheStreamOfItsMapper() {
        when(mapper.stream()).thenReturn(OneCStream.MKB10);

        assertThat(handler().stream()).isEqualTo(OneCStream.MKB10);
    }

    @Test
    void appliedMessage_isMarkedProcessed() {
        when(mapper.apply(data)).thenReturn(ImportResult.applied());

        ImportOutcome result = handler().handle(message(), data);

        assertThat(result).isEqualTo(ImportOutcome.applied());
        verify(inboundMessageService).markProcessed(MESSAGE_ID);
        verifyNoMoreInteractions(inboundMessageService);
        verifyNoInteractions(supersededWaits);
    }

    @Test
    void appliedMessageOfARecord_closesOlderWaitingMessagesOfThatRecord() {
        when(mapper.apply(data)).thenReturn(ImportResult.applied());
        when(mapper.recordOf(data)).thenReturn(Optional.of(RECORD));
        InboundMessage message = message();

        handler().handle(message, data);

        verify(inboundMessageService).markProcessed(MESSAGE_ID);
        verify(supersededWaits).closeOlderThan(message.receipt(), RECORD, "применено");
    }

    @Test
    void appliedMessage_announcesItsRecordAsArrivedParent() {
        when(mapper.apply(data)).thenReturn(ImportResult.applied());
        when(mapper.stream()).thenReturn(OneCStream.POLICIES);

        handler().handle(message(), data);

        verify(parentArrivals).arrived(OneCStream.POLICIES, data);
    }

    @Test
    void skippedMessage_announcesNoArrival() {
        when(mapper.apply(data)).thenReturn(ImportResult.skipped("не санаторий"));

        handler().handle(message(), data);

        verifyNoInteractions(parentArrivals);
    }

    @Test
    void skippedMessage_isMarkedSkippedWithItsReason() {
        when(mapper.apply(data)).thenReturn(ImportResult.skipped("не санаторий"));

        ImportOutcome result = handler().handle(message(), data);

        assertThat(result).isEqualTo(ImportOutcome.skipped("не санаторий"));
        verify(inboundMessageService).markSkipped(MESSAGE_ID, "не санаторий");
        verifyNoMoreInteractions(inboundMessageService);
        verifyNoInteractions(supersededWaits);
    }

    @Test
    void skippedMessageOfARecord_closesOlderWaitingMessagesOfThatRecord() {
        when(mapper.apply(data)).thenReturn(ImportResult.skipped("договор не нашего контрагента"));
        when(mapper.recordOf(data)).thenReturn(Optional.of(RECORD));
        InboundMessage message = message();

        handler().handle(message, data);

        verify(inboundMessageService).markSkipped(MESSAGE_ID, "договор не нашего контрагента");
        verify(supersededWaits).closeOlderThan(message.receipt(), RECORD, "пропущено");
    }

    @Test
    void messageWithoutItsParent_waitsForItWithTheRecordItChanges() {
        when(mapper.apply(data)).thenReturn(ImportResult.awaitingParent(PARENT));
        when(mapper.recordOf(data)).thenReturn(Optional.of(RECORD));

        ImportOutcome result = handler().handle(message(), data);

        assertThat(result).isEqualTo(
                ImportOutcome.awaitingParent("ждёт родителя: контрагент 6d0e4a7b-2c95-4f18-8b3d-e9a1c7f50d62"));
        verify(inboundMessageService).markAwaitingParent(MESSAGE_ID, PARENT, RECORD);
        verifyNoMoreInteractions(inboundMessageService);
        verifyNoInteractions(supersededWaits);
    }

    @Test
    void messageWithoutItsParent_ofAStreamWithoutRecords_waitsWithoutRecord() {
        when(mapper.apply(data)).thenReturn(ImportResult.awaitingParent(PARENT));
        when(mapper.recordOf(any())).thenReturn(Optional.empty());

        handler().handle(message(), data);

        verify(inboundMessageService).markAwaitingParent(MESSAGE_ID, PARENT, null);
    }

    private MappedStreamHandler handler() {
        return new MappedStreamHandler(mapper, inboundMessageService, supersededWaits, parentArrivals);
    }

    private static InboundMessage message() {
        return InboundMessage.receive(new InboundReceipt(MESSAGE_ID, OneCStream.MKB10, "очередь", Instant.EPOCH), "{}");
    }
}
