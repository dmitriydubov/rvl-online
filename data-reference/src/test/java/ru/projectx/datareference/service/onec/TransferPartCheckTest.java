package ru.projectx.datareference.service.onec;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mockito;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundReceipt;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.exception.OneCFieldException;
import ru.projectx.datareference.mapper.stream.PriceListMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class TransferPartCheckTest {

    private static final UUID CID = UUID.fromString("8e4b0f70-0000-4000-8000-00000000000c");
    private static final Instant NOW = Instant.parse("2026-03-14T09:15:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID SELF = UUID.fromString("8e4b0f70-0000-4000-8000-0000000000aa");

    private final PriceListMapper priceList = mock(PriceListMapper.class);
    private final TransferPartCheck check = new TransferPartCheck(priceList::checkRow);

    @Test
    void batchWithNumberedRows_passes_andEveryRowIsCheckedByPriceListRules() {
        ObjectNode data = batch("{\"rowNumber\":1}", "{\"rowNumber\":2}");

        check.check(SELF, markupOf(data), data, List.of());

        InOrder rows = Mockito.inOrder(priceList);
        rows.verify(priceList).checkRow(data.path("recordSet").get(0));
        rows.verify(priceList).checkRow(data.path("recordSet").get(1));
    }

    @Test
    void rowWithoutNumber_rejectsThePart_beforePriceListRules() {
        ObjectNode data = batch("{\"price\":1}");

        assertThatThrownBy(() -> check.check(SELF, markupOf(data), data, List.of()))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("нет обязательного поля rowNumber");
        verify(priceList, never()).checkRow(data.path("recordSet").get(0));
    }

    @Test
    void rowBreakingPriceListRules_rejectsThePart() {
        ObjectNode data = batch("{\"rowNumber\":1}");
        Mockito.doThrow(new OneCFieldException("в строке прайса нет цены"))
                .when(priceList).checkRow(data.path("recordSet").get(0));

        assertThatThrownBy(() -> check.check(SELF, markupOf(data), data, List.of()))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("в строке прайса нет цены");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"recordSet\":null"})
    void batchWithoutRowsArray_isRejectedAsMissingField_ratherThanBatchWithoutRows(String recordSet) {
        ObjectNode data = batchWith(recordSet);

        assertThatThrownBy(() -> check.check(SELF, markupOf(data), data, List.of()))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("нет обязательного поля recordSet");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"rowNumber\":1}", "{}", "\"[]\""})
    void batchWithRowsNotInArray_isRejected_beforeAnyRowIsChecked(String recordSet) {
        ObjectNode data = batchWith(",\"recordSet\":" + recordSet);

        assertThatThrownBy(() -> check.check(SELF, markupOf(data), data, List.of()))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле recordSet не является массивом");
        verifyNoInteractions(priceList);
    }

    @Test
    void batchWithEmptyRowsArray_passes() {
        ObjectNode data = batch();

        assertThatCode(() -> check.check(SELF, markupOf(data), data, List.of(part(true))))
                .doesNotThrowAnyException();
    }

    @Test
    void batchWithoutRowsArray_ofAppliedTransfer_isRejectedWithItsOwnDefect_ratherThanAsSurplus() {
        ObjectNode data = batchWith("");

        assertThatThrownBy(() -> check.check(SELF, markupOf(data), data, List.of(part(true), processedPart())))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("нет обязательного поля recordSet");
    }

    @Test
    void batchWithoutRowsArray_ofRejectedOrSkippedTransfer_isNotChecked() {
        ObjectNode data = batchWith("");
        InboundMessage rejected = part(true);
        rejected.markFailed("счётчики не сошлись");
        InboundMessage skipped = part(true);
        skipped.markSkipped("прайс по услуге вне справочника", NOW);

        assertThatCode(() -> check.check(SELF, markupOf(data), data, List.of(rejected))).doesNotThrowAnyException();
        assertThatCode(() -> check.check(SELF, markupOf(data), data, List.of(skipped))).doesNotThrowAnyException();
    }

    @Test
    void batchOfAppliedTransfer_isSurplus() {
        ObjectNode data = batch("{\"rowNumber\":1}");

        assertThatThrownBy(() -> check.check(SELF, markupOf(data), data, List.of(part(true), processedPart())))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("лишний пакет: передача " + CID + " уже применена");
    }

    @Test
    void batchOfTransferStillInBuffer_isNotSurplus() {
        ObjectNode data = batch("{\"rowNumber\":1}");

        assertThatCode(() -> check.check(SELF, markupOf(data), data, List.of(part(true), part(false))))
                .doesNotThrowAnyException();
    }

    @Test
    void partOfRejectedTransfer_isNotChecked() {
        ObjectNode data = batch("{\"price\":1}");
        InboundMessage rejected = part(false);
        rejected.markFailed("в строке прайса нет цены");

        assertThatCode(() -> check.check(SELF, markupOf(data), data, List.of(rejected, part(true))))
                .doesNotThrowAnyException();
        verifyNoInteractions(priceList);
    }

    @Test
    void secondSurplusBatchOfAppliedTransfer_isSurplus() {
        ObjectNode data = batch("{\"rowNumber\":1}");
        InboundMessage firstSurplus = part(false);
        firstSurplus.markFailed("лишний пакет");

        assertThatThrownBy(() -> check.check(SELF, markupOf(data), data, List.of(processedPart(), firstSurplus)))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("лишний пакет: передача " + CID + " уже применена");
    }

    @Test
    void finalMessage_hasNoRowsToCheck() {
        ObjectNode data = finalData();

        assertThatCode(() -> check.check(SELF, markupOf(data), data, List.of(processedPart())))
                .doesNotThrowAnyException();
        verifyNoInteractions(priceList);
    }

    @Test
    void finalMessage_amongPartsOfItsTransfer_isNotSecond() {
        ObjectNode data = finalData();

        assertThatCode(() -> check.check(SELF, markupOf(data), data, List.of(part(SELF, true), part(false))))
                .doesNotThrowAnyException();
    }

    @Test
    void secondFinalMessage_ofTransferInBuffer_rejectsThePart() {
        ObjectNode data = finalData();

        assertThatThrownBy(() -> check.check(SELF, markupOf(data), data, List.of(part(true), part(SELF, true))))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("второе финальное сообщение передачи " + CID);
    }

    @Test
    void secondFinalMessage_ofAppliedTransfer_namesTheAppliedTransfer() {
        ObjectNode data = finalData();
        InboundMessage appliedFinal = part(true);
        appliedFinal.markProcessed(NOW);

        assertThatThrownBy(() -> check.check(SELF, markupOf(data), data, List.of(appliedFinal, processedPart())))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("второе финальное сообщение: передача " + CID + " уже применена");
    }

    @Test
    void partOfSkippedTransfer_isNotChecked() {
        ObjectNode batch = batch("{\"price\":1}");
        ObjectNode secondFinal = finalData();
        InboundMessage skippedFinal = part(true);
        skippedFinal.markSkipped("прайс по услуге вне справочника", NOW);

        assertThatCode(() -> check.check(SELF, markupOf(batch), batch, List.of(skippedFinal))).doesNotThrowAnyException();
        assertThatCode(() -> check.check(SELF, markupOf(secondFinal), secondFinal, List.of(skippedFinal)))
                .doesNotThrowAnyException();
        verifyNoInteractions(priceList);
    }

    @Test
    void secondFinalMessage_ofRejectedTransfer_isNotChecked() {
        ObjectNode data = finalData();
        InboundMessage rejectedFinal = part(true);
        rejectedFinal.markFailed("счётчики не сошлись");

        assertThatCode(() -> check.check(SELF, markupOf(data), data, List.of(rejectedFinal)))
                .doesNotThrowAnyException();
    }

    private static TransferMarkup markupOf(ObjectNode data) {
        return TransferMarkup.of(data);
    }

    private static ObjectNode batch(String... rows) {
        return (ObjectNode) JSON.readTree("{\"cid\":\"%s\",\"recordSet\":[%s]}".formatted(CID, String.join(",", rows)));
    }

    private static ObjectNode batchWith(String recordSet) {
        return (ObjectNode) JSON.readTree("{\"cid\":\"%s\"%s}".formatted(CID, recordSet));
    }

    private static ObjectNode finalData() {
        return JSON.createObjectNode().put("cid", CID.toString()).put("countMessages", 1).put("countRows", 1);
    }

    private static InboundMessage part(boolean finalMessage) {
        return part(UUID.randomUUID(), finalMessage);
    }

    private static InboundMessage part(UUID messageId, boolean finalMessage) {
        InboundMessage message = InboundMessage.receive(
                new InboundReceipt(messageId, OneCStream.PRICE_LISTS, "очередь", NOW), "{}");
        message.markBuffered(CID, finalMessage ? 1 : null, 1);
        return message;
    }

    private static InboundMessage processedPart() {
        InboundMessage message = part(false);
        message.markProcessed(NOW);
        return message;
    }
}
