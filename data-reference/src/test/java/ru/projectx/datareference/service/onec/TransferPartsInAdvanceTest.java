package ru.projectx.datareference.service.onec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static ru.projectx.datareference.support.OneCMessages.envelope;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundReceipt;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.entity.inbound.OneCStream;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class TransferPartsInAdvanceTest {

    private static final UUID CID = UUID.fromString("8e4b0f70-0000-4000-8000-00000000000c");
    private static final UUID SERVICE_ID = UUID.fromString("8e4b0f70-0000-4000-8000-000000000003");
    private static final Instant NOW = Instant.parse("2026-03-14T09:15:00Z");

    private final JsonMapper json = JsonMapper.builder().build();

    @Mock
    private TransferPartsReader reader;

    @Test
    void partCompletingTransfer_parsesWholeTransfer() {
        InboundMessage finalMessage = buffered(finalData(2, 2), 2, 2);
        InboundMessage firstBatch = buffered(batchData(1), null, 1);
        InboundMessage lastBatch = message(batchData(2));
        ObjectNode lastBatchData = dataOf(lastBatch);
        when(reader.parts(CID)).thenReturn(List.of(finalMessage, firstBatch, storedCopy(lastBatch)));

        ParsedTransferParts parsed = parse(lastBatch, lastBatchData);

        assertThat(parsed.dataOf(finalMessage)).contains(dataOf(finalMessage));
        assertThat(parsed.dataOf(firstBatch)).contains(dataOf(firstBatch));
        assertThat(parsed.dataOf(lastBatch)).get().isSameAs(lastBatchData);
    }

    @Test
    void receivedPartNotYetVisibleInStoredParts_stillCompletesTransfer() {
        InboundMessage batch = buffered(batchData(1), null, 1);
        InboundMessage finalMessage = message(finalData(1, 1));
        when(reader.parts(CID)).thenReturn(List.of(batch));

        ParsedTransferParts parsed = parse(finalMessage, dataOf(finalMessage));

        assertThat(parsed.dataOf(batch)).contains(dataOf(batch));
    }

    @Test
    void redeliveredPartAlreadyBuffered_countsOnce() {
        InboundMessage finalMessage = buffered(finalData(1, 1), 1, 1);
        InboundMessage batch = message(batchData(1));
        InboundMessage storedBatch = storedCopy(batch);
        storedBatch.markBuffered(CID, null, 1);
        when(reader.parts(CID)).thenReturn(List.of(finalMessage, storedBatch));

        ParsedTransferParts parsed = parse(batch, dataOf(batch));

        assertThat(parsed.dataOf(finalMessage)).contains(dataOf(finalMessage));
    }

    @Test
    void transferStillWaitingForParts_parsesNothing() {
        InboundMessage finalMessage = buffered(finalData(2, 2), 2, 2);
        InboundMessage batch = message(batchData(1));
        when(reader.parts(CID)).thenReturn(List.of(finalMessage, storedCopy(batch)));

        assertThat(parse(batch, dataOf(batch))).isEqualTo(ParsedTransferParts.NONE);
    }

    @Test
    void advanceParsing_changesNoPart() {
        InboundMessage batch = buffered(batchData(1), null, 1);
        InboundMessage finalMessage = message(finalData(1, 1));
        InboundMessage storedFinal = storedCopy(finalMessage);
        when(reader.parts(CID)).thenReturn(List.of(batch, storedFinal));

        parse(finalMessage, dataOf(finalMessage));

        assertThat(finalMessage.outcome().status()).isEqualTo(InboundStatus.NEW);
        assertThat(storedFinal.outcome().status()).isEqualTo(InboundStatus.NEW);
        assertThat(finalMessage.transferPart().correlationId()).isNull();
    }

    @Test
    void erasedOrUnreadableBody_isLeftToAssembly() {
        InboundMessage erased = buffered(null, null, 1);
        InboundMessage unreadable = buffered("[]", null, 1);
        InboundMessage finalMessage = message(finalData(2, 2));
        when(reader.parts(CID)).thenReturn(List.of(erased, unreadable));

        ParsedTransferParts parsed = parse(finalMessage, dataOf(finalMessage));

        assertThat(parsed.dataOf(erased)).isEmpty();
        assertThat(parsed.dataOf(unreadable)).isEmpty();
        assertThat(parsed.dataOf(finalMessage)).isPresent();
    }

    private ParsedTransferParts parse(InboundMessage received, ObjectNode data) {
        return new TransferPartsInAdvance(reader, json).parse(received, TransferMarkup.of(data), data);
    }

    private static InboundMessage storedCopy(InboundMessage part) {
        return InboundMessage.receive(part.receipt(), part.payload());
    }

    private static InboundMessage buffered(String data, Integer batchCount, int rowCount) {
        InboundMessage message = InboundMessage.receive(receipt(), data == null ? null : envelope(data));
        message.markBuffered(CID, batchCount, rowCount);
        return message;
    }

    private static InboundMessage message(String data) {
        return InboundMessage.receive(receipt(), envelope(data));
    }

    private static InboundReceipt receipt() {
        return new InboundReceipt(UUID.randomUUID(), OneCStream.PRICE_LISTS, "очередь", NOW);
    }

    private ObjectNode dataOf(InboundMessage message) {
        return (ObjectNode) json.readTree(message.payload()).get("data");
    }

    private static String batchData(int rowNumber) {
        return "{\"cid\":\"%s\",\"keyValue\":\"%s\",\"recordSet\":[{\"rowNumber\":%d,\"price\":100.00}]}"
                .formatted(CID, SERVICE_ID, rowNumber);
    }

    private static String finalData(int countMessages, int countRows) {
        return "{\"cid\":\"%s\",\"keyValue\":\"%s\",\"countMessages\":%d,\"countRows\":%d}"
                .formatted(CID, SERVICE_ID, countMessages, countRows);
    }
}
