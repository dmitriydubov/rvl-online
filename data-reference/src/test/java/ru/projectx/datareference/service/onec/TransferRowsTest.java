package ru.projectx.datareference.service.onec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.projectx.datareference.support.OneCMessages.envelope;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundReceipt;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.exception.OneCFieldException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TransferRowsTest {

    private static final UUID CID = UUID.fromString("8e4b0f70-0000-4000-8000-00000000000c");
    private static final Instant NOW = Instant.parse("2026-11-15T09:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TransferRows rows = new TransferRows(JSON);

    @Test
    void rowsOfAllBatches_areTakenInSourceRowOrder() {
        CorrelatedTransfer.Assembled transfer = assembled(3,
                batch("[{\"rowNumber\":2},{\"rowNumber\":3}]", 2), batch("[{\"rowNumber\":1}]", 1));

        List<JsonNode> inOrder = rows.inOrder(transfer, ParsedTransferParts.NONE);

        assertThat(inOrder).extracting(row -> row.path("rowNumber").asInt()).containsExactly(1, 2, 3);
    }

    @Test
    void batchWithEmptyRowsArray_addsNoRows() {
        CorrelatedTransfer.Assembled transfer = assembled(0, batch("[]", 0));

        assertThat(rows.inOrder(transfer, ParsedTransferParts.NONE)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            ,"recordSet":null    | нет обязательного поля recordSet
            ,"recordSet":{}      | поле recordSet не является массивом
            """)
    void batchBufferedWithoutRowsArray_cannotBeAssembled_ratherThanAddsNoRows(String recordSet, String reason) {
        CorrelatedTransfer.Assembled transfer = assembled(0, part("{\"cid\":\"%s\"%s}".formatted(CID, recordSet), 0));

        assertThatThrownBy(() -> rows.inOrder(transfer, ParsedTransferParts.NONE))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage(reason);
    }

    @Test
    void batchBufferedWithoutRecordSetField_cannotBeAssembled() {
        CorrelatedTransfer.Assembled transfer = assembled(0, part("{\"cid\":\"%s\"}".formatted(CID), 0));

        assertThatThrownBy(() -> rows.inOrder(transfer, ParsedTransferParts.NONE))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("нет обязательного поля recordSet");
    }

    private static CorrelatedTransfer.Assembled assembled(int rowCount, InboundMessage... batches) {
        InboundMessage finalMessage = message("{\"cid\":\"%s\"}".formatted(CID));
        finalMessage.markBuffered(CID, batches.length, rowCount);
        List<InboundMessage> parts = new ArrayList<>(List.of(batches));
        parts.add(finalMessage);
        return (CorrelatedTransfer.Assembled) CorrelatedTransfer.of(CID, parts);
    }

    private static InboundMessage batch(String recordSet, int rowCount) {
        return part("{\"cid\":\"%s\",\"recordSet\":%s}".formatted(CID, recordSet), rowCount);
    }

    private static InboundMessage part(String data, int rowCount) {
        InboundMessage message = message(data);
        message.markBuffered(CID, null, rowCount);
        return message;
    }

    private static InboundMessage message(String data) {
        return InboundMessage.receive(
                new InboundReceipt(UUID.randomUUID(), OneCStream.PRICE_LISTS, "очередь", NOW), envelope(data));
    }
}
