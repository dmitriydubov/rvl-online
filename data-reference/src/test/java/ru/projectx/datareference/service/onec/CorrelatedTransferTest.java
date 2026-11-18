package ru.projectx.datareference.service.onec;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.projectx.datareference.entity.inbound.AwaitedParent;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundReceipt;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.entity.inbound.ParentKind;
import ru.projectx.datareference.entity.inbound.ParentWait;
import ru.projectx.datareference.mapper.ImportResult;

class CorrelatedTransferTest {

    private static final UUID CID = UUID.fromString("8e4b0f70-0000-4000-8000-00000000000c");
    private static final Instant RECEIVED_AT = Instant.parse("2026-03-14T09:00:00Z");
    private static final Instant PROCESSED_AT = Instant.parse("2026-03-14T09:15:00Z");

    @Test
    void batchesWithoutFinalMessage_waitForIt() {
        CorrelatedTransfer transfer = CorrelatedTransfer.of(CID, List.of(batch(2)));

        assertThat(transfer).isEqualTo(new CorrelatedTransfer.Pending(
                "передача " + CID + " ждёт финального сообщения"));
    }

    @Test
    void fewerBatchesThanPromised_waitForTheRest() {
        CorrelatedTransfer transfer = CorrelatedTransfer.of(CID, List.of(finalMessage(2, 3), batch(1)));

        assertThat(transfer).isEqualTo(new CorrelatedTransfer.Pending(
                "передача " + CID + " собрана на 1 из 2 сообщений"));
    }

    @Test
    void allPromisedBatches_assembleTransfer_whateverTheOrder() {
        InboundMessage first = batch(1);
        InboundMessage second = batch(2);
        InboundMessage finalMessage = finalMessage(2, 3);

        CorrelatedTransfer transfer = CorrelatedTransfer.of(CID, List.of(first, finalMessage, second));

        assertThat(transfer).isEqualTo(new CorrelatedTransfer.Assembled(finalMessage, List.of(first, second)));
    }

    @Test
    void emptyTransfer_isAssembledRightAway() {
        InboundMessage finalMessage = finalMessage(0, 0);

        CorrelatedTransfer transfer = CorrelatedTransfer.of(CID, List.of(finalMessage));

        assertThat(transfer).isEqualTo(new CorrelatedTransfer.Assembled(finalMessage, List.of()));
    }

    @Test
    void messagesNoLongerBuffered_areLeftOut() {
        InboundMessage processedFinal = finalMessage(1, 1);
        processedFinal.markProcessed(PROCESSED_AT);
        InboundMessage processedBatch = batch(1);
        processedBatch.markProcessed(PROCESSED_AT);

        CorrelatedTransfer transfer = CorrelatedTransfer.of(CID, List.of(processedFinal, processedBatch));

        assertThat(transfer).isInstanceOf(CorrelatedTransfer.Pending.class);
    }

    @Test
    void partsArrivingAfterRejection_areRejectedRightAway() {
        InboundMessage rejectedBatch = batch(1);
        rejectedBatch.markFailed("в строке прайса нет цены");
        InboundMessage lateFinal = finalMessage(1, 1);
        InboundMessage lateBatch = batch(1);

        CorrelatedTransfer transfer = CorrelatedTransfer.of(CID, List.of(rejectedBatch, lateFinal, lateBatch));

        assertThat(transfer).isEqualTo(new CorrelatedTransfer.Rejected(
                "передача " + CID + " уже отклонена", List.of(lateFinal, lateBatch)));
    }

    @Test
    void appliedTransferWithRejectedSurplusBatch_isNotRejected() {
        InboundMessage applied = batch(1);
        applied.markProcessed(PROCESSED_AT);
        InboundMessage surplus = batch(1);
        surplus.markFailed("лишний пакет");

        assertThat(CorrelatedTransfer.isRejected(List.of(applied, surplus))).isFalse();
        assertThat(CorrelatedTransfer.isRejected(List.of(surplus))).isTrue();
        assertThat(CorrelatedTransfer.isRejected(List.of(applied))).isFalse();
        assertThat(CorrelatedTransfer.of(CID, List.of(applied, surplus, batch(1))))
                .isInstanceOf(CorrelatedTransfer.Pending.class);
    }

    @Test
    void partsArrivingAfterSkip_areSkippedRightAway() {
        InboundMessage skippedFinal = finalMessage(1, 1);
        skippedFinal.markSkipped("прайс по услуге вне справочника", PROCESSED_AT);
        InboundMessage lateBatch = batch(1);
        InboundMessage lateFinal = finalMessage(1, 1);

        CorrelatedTransfer transfer = CorrelatedTransfer.of(CID, List.of(skippedFinal, lateBatch, lateFinal));

        assertThat(transfer).isEqualTo(new CorrelatedTransfer.Closed(
                "передача " + CID + " уже пропущена", List.of(lateBatch, lateFinal)));
    }

    @Test
    void closedTransfer_skipsEveryLatePartWithReason() {
        InboundMessage lateBatch = batch(1);
        InboundMessage lateFinal = finalMessage(1, 1);
        CorrelatedTransfer.Closed transfer = new CorrelatedTransfer.Closed("уже пропущена", List.of(lateBatch, lateFinal));

        List<InboundMessage> skipped = transfer.skip(PROCESSED_AT);

        assertThat(skipped).containsExactly(lateBatch, lateFinal).allSatisfy(message -> {
            assertThat(message.outcome().status()).isEqualTo(InboundStatus.SKIPPED);
            assertThat(message.outcome().lastError()).isEqualTo("уже пропущена");
            assertThat(message.outcome().processedAt()).isEqualTo(PROCESSED_AT);
        });
    }

    @Test
    void transferIsClosed_onlyWhenRejectedOrSkipped() {
        InboundMessage applied = batch(1);
        applied.markProcessed(PROCESSED_AT);
        InboundMessage rejected = batch(1);
        rejected.markFailed("в строке прайса нет цены");
        InboundMessage skipped = batch(1);
        skipped.markSkipped("прайс по услуге вне справочника", PROCESSED_AT);

        assertThat(CorrelatedTransfer.isClosed(List.of(rejected))).isTrue();
        assertThat(CorrelatedTransfer.isClosed(List.of(skipped, batch(1)))).isTrue();
        assertThat(CorrelatedTransfer.isClosed(List.of(applied, batch(1)))).isFalse();
        assertThat(CorrelatedTransfer.isClosed(List.of(batch(1)))).isFalse();
    }

    @Test
    void rejectedTransfer_failsEveryLatePartWithReason() {
        InboundMessage lateFinal = finalMessage(1, 1);
        InboundMessage lateBatch = batch(1);
        CorrelatedTransfer.Rejected transfer = new CorrelatedTransfer.Rejected("уже отклонена", List.of(lateFinal, lateBatch));

        List<InboundMessage> failed = transfer.fail();

        assertThat(failed).containsExactly(lateFinal, lateBatch);
        assertThat(failed).allSatisfy(message -> {
            assertThat(message.outcome().status()).isEqualTo(InboundStatus.FAILED);
            assertThat(message.outcome().lastError()).isEqualTo("уже отклонена");
        });
    }

    @Test
    void morePromisedBatchesThanArrived_failWholeTransfer() {
        InboundMessage finalMessage = finalMessage(1, 2);
        InboundMessage first = batch(1);
        InboundMessage second = batch(1);

        CorrelatedTransfer transfer = CorrelatedTransfer.of(CID, List.of(finalMessage, first, second));

        assertThat(transfer).isEqualTo(new CorrelatedTransfer.Mismatched(
                "счётчики передачи " + CID + " не сошлись: ожидалось 1 сообщений / 2 строк, получено 2 / 2",
                finalMessage, List.of(first, second)));
    }

    @Test
    void rowsDifferingFromPromised_failWholeTransfer() {
        InboundMessage finalMessage = finalMessage(2, 5);
        InboundMessage first = batch(1);
        InboundMessage second = batch(2);

        CorrelatedTransfer transfer = CorrelatedTransfer.of(CID, List.of(first, second, finalMessage));

        assertThat(transfer).isEqualTo(new CorrelatedTransfer.Mismatched(
                "счётчики передачи " + CID + " не сошлись: ожидалось 2 сообщений / 5 строк, получено 2 / 3",
                finalMessage, List.of(first, second)));
    }

    @Test
    void mismatchedTransfer_failsEveryMessageWithReason_finalMessageFirst() {
        InboundMessage finalMessage = finalMessage(1, 5);
        InboundMessage batch = batch(1);
        CorrelatedTransfer.Mismatched transfer = new CorrelatedTransfer.Mismatched("счётчики", finalMessage, List.of(batch));

        List<InboundMessage> failed = transfer.fail();

        assertThat(failed).containsExactly(finalMessage, batch);
        assertThat(failed).allSatisfy(message -> {
            assertThat(message.outcome().status()).isEqualTo(InboundStatus.FAILED);
            assertThat(message.outcome().lastError()).isEqualTo("счётчики");
        });
    }

    @Test
    void assembledTransfer_marksEveryMessageProcessed_finalMessageFirst() {
        InboundMessage finalMessage = finalMessage(1, 1);
        InboundMessage batch = batch(1);
        CorrelatedTransfer.Assembled transfer = new CorrelatedTransfer.Assembled(finalMessage, List.of(batch));

        List<InboundMessage> processed = transfer.markProcessed(PROCESSED_AT);

        assertThat(processed).containsExactly(finalMessage, batch);
        assertThat(processed).allSatisfy(message -> {
            assertThat(message.outcome().status()).isEqualTo(InboundStatus.PROCESSED);
            assertThat(message.outcome().processedAt()).isEqualTo(PROCESSED_AT);
        });
    }

    @Test
    void outcomeOfApplying_isTheOutcomeOfEveryMessage() {
        InboundMessage finalMessage = finalMessage(1, 1);
        InboundMessage batch = batch(1);
        InboundMessage skippedFinal = finalMessage(1, 1);
        InboundMessage skippedBatch = batch(1);
        InboundMessage waitingFinal = finalMessage(1, 1);
        InboundMessage waitingBatch = batch(1);
        AwaitedParent service = new AwaitedParent(ParentKind.CONTRAGENT_SERVICE, CID);

        List<InboundMessage> applied = new CorrelatedTransfer.Assembled(finalMessage, List.of(batch))
                .markOutcome(ImportResult.applied(), PROCESSED_AT);
        List<InboundMessage> skipped = new CorrelatedTransfer.Assembled(skippedFinal, List.of(skippedBatch))
                .markOutcome(ImportResult.skipped("услуги нет"), PROCESSED_AT);
        List<InboundMessage> waiting = new CorrelatedTransfer.Assembled(waitingFinal, List.of(waitingBatch))
                .markOutcome(ImportResult.awaitingParent(service), PROCESSED_AT);

        assertThat(applied).containsExactly(finalMessage, batch)
                .allSatisfy(message -> assertThat(message.outcome().status()).isEqualTo(InboundStatus.PROCESSED));
        assertThat(skipped).containsExactly(skippedFinal, skippedBatch).allSatisfy(message -> {
            assertThat(message.outcome().status()).isEqualTo(InboundStatus.SKIPPED);
            assertThat(message.outcome().lastError()).isEqualTo("услуги нет");
            assertThat(message.outcome().processedAt()).isEqualTo(PROCESSED_AT);
        });
        assertThat(waiting).containsExactly(waitingFinal, waitingBatch).allSatisfy(message -> {
            assertThat(message.outcome().status()).isEqualTo(InboundStatus.AWAITING_PARENT);
            assertThat(message.outcome().lastError()).isEqualTo(service.waitingReason());
            assertThat(message.outcome().parentWait()).contains(new ParentWait(service, PROCESSED_AT, null));
        });
    }

    @Test
    void transferWaitingForItsService_isAssembledAgainFromTheSameParts() {
        InboundMessage finalMessage = finalMessage(1, 1);
        InboundMessage batch = batch(1);
        new CorrelatedTransfer.Assembled(finalMessage, List.of(batch)).markOutcome(
                ImportResult.awaitingParent(new AwaitedParent(ParentKind.CONTRAGENT_SERVICE, CID)), PROCESSED_AT);

        assertThat(CorrelatedTransfer.of(CID, List.of(finalMessage, batch)))
                .isEqualTo(new CorrelatedTransfer.Assembled(finalMessage, List.of(batch)));
    }

    @Test
    void assembledTransfer_skipsEveryMessageWithReason_finalMessageFirst() {
        InboundMessage finalMessage = finalMessage(1, 1);
        InboundMessage batch = batch(1);
        CorrelatedTransfer.Assembled transfer = new CorrelatedTransfer.Assembled(finalMessage, List.of(batch));

        List<InboundMessage> skipped = transfer.markSkipped("услуги нет", PROCESSED_AT);

        assertThat(skipped).containsExactly(finalMessage, batch);
        assertThat(skipped).allSatisfy(message -> {
            assertThat(message.outcome().status()).isEqualTo(InboundStatus.SKIPPED);
            assertThat(message.outcome().lastError()).isEqualTo("услуги нет");
            assertThat(message.outcome().processedAt()).isEqualTo(PROCESSED_AT);
        });
    }

    private static InboundMessage batch(int rows) {
        InboundMessage message = received();
        message.markBuffered(CID, null, rows);
        return message;
    }

    private static InboundMessage finalMessage(int batchCount, int rows) {
        InboundMessage message = received();
        message.markBuffered(CID, batchCount, rows);
        return message;
    }

    private static InboundMessage received() {
        return InboundMessage.receive(
                new InboundReceipt(UUID.randomUUID(), OneCStream.PRICE_LISTS, "очередь", RECEIVED_AT), "{}");
    }
}
