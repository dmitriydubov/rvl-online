package ru.projectx.datareference.service.onec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundReceipt;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.exception.OneCFieldException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class TransferMarkupTest {

    private static final UUID CID = UUID.fromString("8e4b0f70-0000-4000-8000-00000000000c");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void batch_hasNoExpectedBatches_andCountsItsOwnRows() {
        TransferMarkup markup = TransferMarkup.of(node("""
                {"cid":"%s","recordSet":[{"rowNumber":1},{"rowNumber":2}]}""".formatted(CID)));

        assertThat(markup).isEqualTo(new TransferMarkup(CID, null, 2));
    }

    @Test
    void finalMessage_carriesCountersOfWholeTransfer() {
        TransferMarkup markup = TransferMarkup.of(node("""
                {"cid":"%s","countMessages":2,"countRows":3}""".formatted(CID)));

        assertThat(markup).isEqualTo(new TransferMarkup(CID, 2, 3));
    }

    @Test
    void finalMessageWithZeroBatches_isStillFinal() {
        TransferMarkup markup = TransferMarkup.of(node("""
                {"cid":"%s","countMessages":0,"countRows":0}""".formatted(CID)));

        assertThat(markup).isEqualTo(new TransferMarkup(CID, 0, 0));
    }

    @ParameterizedTest
    @CsvSource({"countMessages, 10000000000, 1", "countMessages, -10000000000, 1", "countRows, 1, 2147483648"})
    void counterBeyondIntBound_isContractViolationNamingField(String field, String countMessages, String countRows) {
        ObjectNode data = node("""
                {"cid":"%s","countMessages":%s,"countRows":%s}""".formatted(CID, countMessages, countRows));

        assertThatThrownBy(() -> TransferMarkup.of(data))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле " + field + " больше допустимого");
    }

    @ParameterizedTest
    @CsvSource({"countMessages, 2.5, 1", "countMessages, 2.0, 1", "countRows, 1, -2.5", "countRows, 1, 2.0"})
    void fractionalCounter_isContractViolationNamingField(String field, String countMessages, String countRows) {
        ObjectNode data = node("""
                {"cid":"%s","countMessages":%s,"countRows":%s}""".formatted(CID, countMessages, countRows));

        assertThatThrownBy(() -> TransferMarkup.of(data))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле " + field + " не является целым числом");
    }

    @Test
    void counterWrittenAsText_isReadAsBefore() {
        TransferMarkup markup = TransferMarkup.of(node("""
                {"cid":"%s","countMessages":"2","countRows":"3"}""".formatted(CID)));

        assertThat(markup).isEqualTo(new TransferMarkup(CID, 2, 3));
    }

    @Test
    void messageWithoutTransferId_isContractViolation() {
        assertThatThrownBy(() -> TransferMarkup.of(node("{\"recordSet\":[]}")))
                .isInstanceOf(OneCFieldException.class)
                .hasMessageContaining("cid");
    }

    @Test
    void markup_putsMessageIntoBuffer() {
        InboundMessage message = InboundMessage.receive(
                new InboundReceipt(UUID.randomUUID(), OneCStream.PRICE_LISTS, "очередь", Instant.EPOCH), "{}");

        new TransferMarkup(CID, 2, 3).applyTo(message);

        assertThat(message.outcome().status()).isEqualTo(InboundStatus.BUFFERED);
        assertThat(message.transferPart().correlationId()).isEqualTo(CID);
        assertThat(message.transferPart().batchCount()).isEqualTo(2);
        assertThat(message.transferPart().rowCount()).isEqualTo(3);
    }

    private static ObjectNode node(String text) {
        return JSON.readValue(text, ObjectNode.class);
    }
}
