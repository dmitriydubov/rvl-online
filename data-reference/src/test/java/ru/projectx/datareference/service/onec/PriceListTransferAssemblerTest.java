package ru.projectx.datareference.service.onec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ru.projectx.datareference.support.OneCMessages.envelope;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.projectx.datareference.config.InboundRetryProperties;
import ru.projectx.datareference.entity.inbound.AwaitedParent;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundReceipt;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.entity.inbound.ParentKind;
import ru.projectx.datareference.entity.inbound.ParentWait;
import ru.projectx.datareference.entity.inbound.PriceListFinalization;
import ru.projectx.datareference.exception.OneCFieldException;
import ru.projectx.datareference.mapper.ImportResult;
import ru.projectx.datareference.mapper.stream.PriceListMapper;
import ru.projectx.datareference.repository.database.DatabaseClockRepository;
import ru.projectx.datareference.repository.inbound.InboundMessageRepository;
import ru.projectx.datareference.repository.inbound.PriceListFinalizationRepository;
import ru.projectx.datareference.service.inbound.FinalRejectionJournal;
import ru.projectx.datareference.service.inbound.InboundMessageService;
import ru.projectx.datareference.service.metrics.InboundTransitionMetrics;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class PriceListTransferAssemblerTest {

    private static final UUID CID = UUID.fromString("8e4b0f70-0000-4000-8000-00000000000c");
    private static final UUID SERVICE_ID = UUID.fromString("8e4b0f70-0000-4000-8000-000000000003");
    private static final Instant NOW = Instant.parse("2026-03-14T09:15:00Z");

    private final JsonMapper json = JsonMapper.builder().build();

    @Mock
    private InboundMessageRepository repository;

    @Mock
    private PriceListMapper priceListMapper;

    @Mock
    private PriceListFinalizationRepository finalizations;

    @Mock
    private TransferPartsReader partsReader;

    private final List<InboundMessage> correlated = new ArrayList<>();

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void handlesPriceListStream() {
        assertThat(assembler().stream()).isEqualTo(OneCStream.PRICE_LISTS);
    }

    @Test
    void batch_isBufferedWithItsRowCount_andWaitsForFinalMessage() {
        InboundMessage batch = message(batchData(row(1, "100.00"), row(2, "200.00")));

        ImportOutcome result = receive(batch);

        assertThat(result).isEqualTo(ImportOutcome.skipped("передача " + CID + " ждёт финального сообщения"));
        assertThat(batch.outcome().status()).isEqualTo(InboundStatus.BUFFERED);
        assertThat(batch.transferPart().correlationId()).isEqualTo(CID);
        assertThat(batch.transferPart().batchCount()).isNull();
        assertThat(batch.transferPart().rowCount()).isEqualTo(2);
        verify(repository).save(batch);
        verify(priceListMapper, never()).apply(any());
        assertThat(transitions(InboundStatus.BUFFERED)).isEqualTo(1.0);
    }

    @Test
    void finalMessage_isBufferedWithItsCounters() {
        InboundMessage finalMessage = message(finalData(2, 3));

        ImportOutcome result = receive(finalMessage);

        assertThat(result).isEqualTo(ImportOutcome.skipped("передача " + CID + " собрана на 0 из 2 сообщений"));
        assertThat(finalMessage.outcome().status()).isEqualTo(InboundStatus.BUFFERED);
        assertThat(finalMessage.transferPart().correlationId()).isEqualTo(CID);
        assertThat(finalMessage.transferPart().batchCount()).isEqualTo(2);
        assertThat(finalMessage.transferPart().rowCount()).isEqualTo(3);
    }

    @Test
    void assembledTransfer_appliesRowsOfAllBatchesInSourceOrder_andMarksWholeTransferProcessed() {
        InboundMessage first = buffered(batchData(row(3, "300.00"), row(1, "100.00")), null, 2);
        InboundMessage second = buffered(batchData(row(2, "200.00")), null, 1);
        InboundMessage finalMessage = message(finalData(2, 3));
        when(priceListMapper.apply(any())).thenReturn(ImportResult.applied());

        ImportOutcome result = receive(finalMessage);

        assertThat(result).isEqualTo(ImportOutcome.applied());
        verify(priceListMapper).apply(node("""
                {"keyValue":"%s","recordSet":[%s,%s,%s]}"""
                .formatted(SERVICE_ID, row(1, "100.00"), row(2, "200.00"), row(3, "300.00"))));
        assertThat(List.of(finalMessage, first, second)).allSatisfy(message -> {
            assertThat(message.outcome().status()).isEqualTo(InboundStatus.PROCESSED);
            assertThat(message.outcome().processedAt()).isEqualTo(NOW);
        });
        verifySavedAfterBuffering(finalMessage, first, second);
        assertThat(transitions(InboundStatus.BUFFERED)).isEqualTo(1.0);
        assertThat(transitions(InboundStatus.PROCESSED)).isEqualTo(3.0);
    }

    @Test
    void assembledTransferForServiceNotYetReceived_waitsForItWhole() {
        InboundMessage batch = buffered(batchData(row(1, "100.00")), null, 1);
        InboundMessage finalMessage = message(finalData(1, 1));
        AwaitedParent service = new AwaitedParent(ParentKind.CONTRAGENT_SERVICE, SERVICE_ID);
        when(priceListMapper.apply(any())).thenReturn(ImportResult.awaitingParent(service));

        ImportOutcome result = receive(finalMessage);

        assertThat(result).isEqualTo(ImportOutcome.awaitingParent(service.waitingReason()));
        assertThat(List.of(finalMessage, batch)).allSatisfy(message -> {
            assertThat(message.outcome().status()).isEqualTo(InboundStatus.AWAITING_PARENT);
            assertThat(message.outcome().parentWait()).contains(new ParentWait(service, NOW, null));
        });
        assertThat(transitions(InboundStatus.AWAITING_PARENT)).isEqualTo(2.0);
    }

    @Test
    void waitingTransfer_isAssembledAgainFromItsParts_andApplied() {
        AwaitedParent service = new AwaitedParent(ParentKind.CONTRAGENT_SERVICE, SERVICE_ID);
        InboundMessage batch = buffered(batchData(row(1, "100.00")), null, 1);
        InboundMessage finalMessage = message(finalData(1, 1));
        finalMessage.markBuffered(CID, 1, 1);
        batch.markAwaitingParent(service, null, NOW);
        finalMessage.markAwaitingParent(service, null, NOW);
        when(priceListMapper.apply(any())).thenReturn(ImportResult.applied());

        ImportOutcome result = receive(finalMessage);

        assertThat(result).isEqualTo(ImportOutcome.applied());
        assertThat(List.of(finalMessage, batch)).allSatisfy(message -> {
            assertThat(message.outcome().status()).isEqualTo(InboundStatus.PROCESSED);
            assertThat(message.outcome().parentWait()).isEmpty();
        });
    }

    @Test
    void transferSkippedByPriceList_isSkippedWholeWithThatReason() {
        InboundMessage batch = buffered(batchData(row(1, "100.00")), null, 1);
        InboundMessage finalMessage = message(finalData(1, 1));
        when(priceListMapper.apply(any())).thenReturn(ImportResult.skipped("услуги нет"));

        ImportOutcome result = receive(finalMessage);

        assertThat(result).isEqualTo(ImportOutcome.skipped("услуги нет"));
        assertThat(List.of(finalMessage, batch)).allSatisfy(message -> {
            assertThat(message.outcome().status()).isEqualTo(InboundStatus.SKIPPED);
            assertThat(message.outcome().lastError()).isEqualTo("услуги нет");
            assertThat(message.outcome().processedAt()).isEqualTo(NOW);
        });
        verifySavedAfterBuffering(finalMessage, batch);
        assertThat(transitions(InboundStatus.SKIPPED)).isEqualTo(2.0);
    }

    @Test
    void mismatchedCounters_failWholeTransfer_withoutTouchingPriceList() {
        InboundMessage batch = buffered(batchData(row(1, "100.00")), null, 1);
        InboundMessage finalMessage = message(finalData(1, 5));
        String reason = "счётчики передачи " + CID + " не сошлись: ожидалось 1 сообщений / 5 строк, получено 1 / 1";

        ImportOutcome result = receive(finalMessage);

        assertThat(result).isEqualTo(ImportOutcome.rejected(reason));
        assertThat(List.of(finalMessage, batch)).allSatisfy(message -> {
            assertThat(message.outcome().status()).isEqualTo(InboundStatus.FAILED);
            assertThat(message.outcome().lastError()).isEqualTo(reason);
        });
        verifySavedAfterBuffering(finalMessage, batch);
        verify(priceListMapper, never()).apply(any());
        assertThat(transitions(InboundStatus.FAILED)).isEqualTo(2.0);
    }

    @Test
    void batchWithRowWithoutNumber_isContractViolation_beforeAssembling() {
        InboundMessage finalMessage = buffered(finalData(1, 1), 1, 1);
        InboundMessage batch = message(batchData("{\"price\":100.00}"));

        assertThatThrownBy(() -> receive(batch))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("нет обязательного поля rowNumber");
        verify(priceListMapper, never()).apply(any());
        assertThat(finalMessage.outcome().status()).isEqualTo(InboundStatus.BUFFERED);
    }

    @Test
    void batchOfAppliedTransfer_isContractViolation() {
        InboundMessage applied = buffered(batchData(row(1, "100.00")), null, 1);
        applied.markProcessed(NOW);
        InboundMessage surplus = message(batchData(row(2, "200.00")));

        assertThatThrownBy(() -> receive(surplus))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("лишний пакет: передача " + CID + " уже применена");
        verify(priceListMapper, never()).apply(any());
    }

    @Test
    void partOfRejectedTransfer_isRejectedRightAway() {
        InboundMessage rejected = buffered(batchData(row(1, "100.00")), null, 1);
        rejected.markFailed("в строке прайса нет цены");
        InboundMessage lateFinal = message(finalData(1, 1));
        String reason = "передача " + CID + " уже отклонена";

        ImportOutcome result = receive(lateFinal);

        assertThat(result).isEqualTo(ImportOutcome.rejected(reason));
        assertThat(lateFinal.outcome().status()).isEqualTo(InboundStatus.FAILED);
        assertThat(lateFinal.outcome().lastError()).isEqualTo(reason);
        assertThat(rejected.outcome().lastError()).isEqualTo("в строке прайса нет цены");
        verifySavedAfterBuffering(lateFinal);
        verify(priceListMapper, never()).apply(any());
        assertThat(transitions(InboundStatus.FAILED)).isEqualTo(1.0);
    }

    @Test
    void defectivePartOfRejectedTransfer_isRejectedAsLatePart() {
        InboundMessage rejected = buffered(batchData(row(1, "100.00")), null, 1);
        rejected.markFailed("в строке прайса нет цены");
        InboundMessage lateBatch = message(batchData("{\"price\":100.00}"));

        ImportOutcome result = receive(lateBatch);

        assertThat(result).isEqualTo(ImportOutcome.rejected("передача " + CID + " уже отклонена"));
        assertThat(lateBatch.outcome().lastError()).isEqualTo("передача " + CID + " уже отклонена");
    }

    @Test
    void serviceRowIsLocked_beforeTransferPartsAreRead() {
        receive(message(batchData(row(1, "100.00"))));

        InOrder steps = inOrder(finalizations, repository);
        steps.verify(finalizations).lockByService(SERVICE_ID);
        steps.verify(repository).findByCorrelationId(CID);
    }

    @Test
    void transferParts_areReadInAdvance_beforeFirstQueryAndServiceLock() {
        receive(message(batchData(row(1, "100.00"))));

        InOrder steps = inOrder(partsReader, repository, finalizations);
        steps.verify(partsReader).parts(CID);
        steps.verify(repository).existsByCorrelationIdAndStatusIn(any(), any());
        steps.verify(finalizations).lockByService(SERVICE_ID);
    }

    @Test
    void assembledTransfer_takesBodiesParsedBeforeLock_andParsesNothingUnderIt() {
        InboundMessage batch = message(batchData(row(1, "100.00")));
        batch.markBuffered(CID, null, 1);
        InboundMessage finalMessage = message(finalData(1, 1));
        correlated.add(withoutBody(batch));
        correlated.add(finalMessage);
        when(repository.findByCorrelationId(CID)).thenReturn(correlated);
        when(partsReader.parts(CID)).thenReturn(List.of(batch, finalMessage));
        when(priceListMapper.apply(any())).thenReturn(ImportResult.applied());

        ImportOutcome result = assembler().handle(finalMessage, dataOf(finalMessage));

        assertThat(result).isEqualTo(ImportOutcome.applied());
        verify(priceListMapper).apply(node("""
                {"keyValue":"%s","recordSet":[%s]}""".formatted(SERVICE_ID, row(1, "100.00"))));
    }

    @Test
    void partMissedByAdvanceRead_isParsedByAssembly() {
        InboundMessage batch = buffered(batchData(row(1, "100.00")), null, 1);
        InboundMessage finalMessage = message(finalData(1, 1));
        correlated.add(finalMessage);
        when(repository.findByCorrelationId(CID)).thenReturn(correlated);
        when(partsReader.parts(CID)).thenReturn(List.of());
        when(priceListMapper.apply(any())).thenReturn(ImportResult.applied());

        ImportOutcome result = assembler().handle(finalMessage, dataOf(finalMessage));

        assertThat(result).isEqualTo(ImportOutcome.applied());
        verify(priceListMapper).apply(node("""
                {"keyValue":"%s","recordSet":[%s]}""".formatted(SERVICE_ID, row(1, "100.00"))));
        assertThat(batch.outcome().status()).isEqualTo(InboundStatus.PROCESSED);
    }

    @Test
    void serviceOfPart_isCheckedBeforeItsRowIsLocked() {
        receive(message(batchData(row(1, "100.00"))));

        InOrder steps = inOrder(finalizations, repository);
        steps.verify(repository).findBufferedTransferServices(CID);
        steps.verify(finalizations).lockByService(SERVICE_ID);
    }

    @Test
    void partNamingAnotherServiceThanTransfer_isContractViolation_beforeLocking() {
        InboundMessage batch = message(batchData(row(1, "100.00")));
        when(repository.findBufferedTransferServices(CID)).thenReturn(List.of(UUID.randomUUID().toString()));

        assertThatThrownBy(() -> assembler().handle(batch, dataOf(batch)))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("части передачи " + CID + " называют разные услуги");
        verify(finalizations, never()).lockByService(any());
        verify(repository, never()).save(any());
        verify(priceListMapper, never()).apply(any());
    }

    @Test
    void appliedTransfer_becomesTheLastOfItsService() {
        buffered(batchData(row(1, "100.00")), null, 1);
        InboundMessage finalMessage = message(finalData(1, 1));
        when(priceListMapper.apply(any())).thenReturn(ImportResult.applied());

        receive(finalMessage);

        verify(finalizations).recordApplied(SERVICE_ID, finalMessage.receipt().messageId());
    }

    @Test
    void transferFinalizedBeforeTheAppliedOne_isSkippedWhole_andPricesStay() {
        InboundMessage olderFinal = buffered(finalData(1, 1), 1, 1);
        InboundMessage lastBatch = message(batchData(row(1, "100.00")));
        UUID appliedCid = UUID.fromString("8e4b0f70-0000-4000-8000-0000000000c2");
        appliedFinalReceivedAt(appliedCid, NOW.plusSeconds(1));
        String reason = "по услуге " + SERVICE_ID + " уже применена более новая передача " + appliedCid;

        ImportOutcome result = receive(lastBatch);

        assertThat(result).isEqualTo(ImportOutcome.skipped(reason));
        assertThat(List.of(olderFinal, lastBatch)).allSatisfy(message -> {
            assertThat(message.outcome().status()).isEqualTo(InboundStatus.SKIPPED);
            assertThat(message.outcome().lastError()).isEqualTo(reason);
            assertThat(message.outcome().processedAt()).isEqualTo(NOW);
        });
        verify(priceListMapper, never()).apply(any());
        verify(finalizations, never()).recordApplied(any(), any());
    }

    @Test
    void partOfSkippedTransfer_isSkippedRightAway() {
        InboundMessage skipped = buffered(finalData(1, 1), 1, 1);
        skipped.markSkipped("прайс по услуге вне справочника", NOW);
        InboundMessage lateBatch = message(batchData("{\"price\":100.00}"));
        String reason = "передача " + CID + " уже пропущена";

        ImportOutcome result = receive(lateBatch);

        assertThat(result).isEqualTo(ImportOutcome.skipped(reason));
        assertThat(lateBatch.outcome().status()).isEqualTo(InboundStatus.SKIPPED);
        assertThat(lateBatch.outcome().lastError()).isEqualTo(reason);
        assertThat(lateBatch.outcome().processedAt()).isEqualTo(NOW);
        assertThat(skipped.outcome().lastError()).isEqualTo("прайс по услуге вне справочника");
        verifySavedAfterBuffering(lateBatch);
        verify(priceListMapper, never()).apply(any());
        assertThat(transitions(InboundStatus.SKIPPED)).isEqualTo(1.0);
    }

    @Test
    void secondFinalMessage_isContractViolation_beforeAssembling() {
        buffered(finalData(1, 1), 1, 1);
        InboundMessage secondFinal = message(finalData(1, 1));

        assertThatThrownBy(() -> receive(secondFinal))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("второе финальное сообщение передачи " + CID);
        verify(priceListMapper, never()).apply(any());
    }

    @Test
    void batchWithErasedBody_isContractViolation() {
        InboundMessage batch = InboundMessage.receive(
                new InboundReceipt(UUID.randomUUID(), OneCStream.PRICE_LISTS, "очередь", NOW), null);
        batch.markBuffered(CID, null, 1);
        correlated.add(batch);
        InboundMessage finalMessage = message(finalData(1, 1));

        assertThatThrownBy(() -> receive(finalMessage))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("тело сообщения передачи стёрто, собрать её нечем");
    }

    @Test
    void batchWithoutDataObject_isContractViolation() {
        InboundMessage batch = InboundMessage.receive(
                new InboundReceipt(UUID.randomUUID(), OneCStream.PRICE_LISTS, "очередь", NOW), "{\"metadata\":{}}");
        batch.markBuffered(CID, null, 1);
        correlated.add(batch);
        InboundMessage finalMessage = message(finalData(1, 1));

        assertThatThrownBy(() -> receive(finalMessage))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("в конверте сообщения нет объекта data");
    }

    @Test
    void messageWithoutTransferId_isContractViolation() {
        InboundMessage message = message("{\"keyValue\":\"%s\",\"recordSet\":[]}".formatted(SERVICE_ID));

        assertThatThrownBy(() -> assembler().handle(message, dataOf(message)))
                .isInstanceOf(OneCFieldException.class)
                .hasMessageContaining("cid");
    }

    private ImportOutcome receive(InboundMessage message) {
        correlated.add(message);
        when(repository.findByCorrelationId(CID)).thenReturn(correlated);
        when(partsReader.parts(CID)).thenReturn(correlated);
        return assembler().handle(message, dataOf(message));
    }

    private void verifySavedAfterBuffering(InboundMessage received, InboundMessage... others) {
        InOrder saves = inOrder(repository);
        saves.verify(repository).save(received);
        saves.verify(repository).findByCorrelationId(CID);
        saves.verify(repository).save(received);
        for (InboundMessage other : others) {
            saves.verify(repository).save(other);
        }
    }

    private double transitions(InboundStatus status) {
        return meters.get(InboundTransitionMetrics.TRANSITIONS)
                .tag("stream", OneCStream.PRICE_LISTS.name()).tag("status", status.name()).counter().count();
    }

    private void appliedFinalReceivedAt(UUID appliedCid, Instant receivedAt) {
        InboundMessage appliedFinal = InboundMessage.receive(
                new InboundReceipt(UUID.randomUUID(), OneCStream.PRICE_LISTS, "очередь", receivedAt), "{}");
        appliedFinal.markBuffered(appliedCid, 1, 1);
        UUID appliedFinalId = appliedFinal.receipt().messageId();
        when(finalizations.lockByService(SERVICE_ID))
                .thenReturn(Optional.of(PriceListFinalization.of(SERVICE_ID, appliedFinalId)));
        when(repository.findById(appliedFinalId)).thenReturn(Optional.of(appliedFinal));
    }

    private InboundMessage buffered(String data, Integer batchCount, int rowCount) {
        InboundMessage message = message(data);
        message.markBuffered(CID, batchCount, rowCount);
        correlated.add(message);
        return message;
    }

    private static InboundMessage withoutBody(InboundMessage part) {
        InboundMessage copy = InboundMessage.receive(part.receipt(), null);
        copy.markBuffered(CID, part.transferPart().batchCount(), part.transferPart().rowCount());
        return copy;
    }

    private static InboundMessage message(String data) {
        return InboundMessage.receive(
                new InboundReceipt(UUID.randomUUID(), OneCStream.PRICE_LISTS, "очередь", NOW), envelope(data));
    }

    private ObjectNode dataOf(InboundMessage message) {
        return (ObjectNode) json.readTree(message.payload()).get("data");
    }

    private ObjectNode node(String text) {
        return json.readValue(text, ObjectNode.class);
    }

    private PriceListTransferAssembler assembler() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InboundMessageService inboundMessageService = new InboundMessageService(
                repository, new InboundRetryProperties(List.of(Duration.ofSeconds(30))), clock,
                mock(DatabaseClockRepository.class),
                new InboundTransitionMetrics(meters), new FinalRejectionJournal(), List.of());
        PriceTransferOrder order = new PriceTransferOrder(finalizations, repository);
        PriceListReplacement replacement = new PriceListReplacement(priceListMapper, order, json);
        TransferAssembly assembly = new TransferAssembly(inboundMessageService,
                new TransferPartsInAdvance(partsReader, json), new TransferConclusion(inboundMessageService, clock));
        return new PriceListTransferAssembler(assembly, priceListMapper,
                new PriceTransferServiceLock(repository, finalizations), replacement);
    }

    private static String batchData(String... rows) {
        return "{\"cid\":\"%s\",\"keyValue\":\"%s\",\"recordSet\":[%s]}"
                .formatted(CID, SERVICE_ID, String.join(",", rows));
    }

    private static String finalData(int countMessages, int countRows) {
        return "{\"cid\":\"%s\",\"keyValue\":\"%s\",\"countMessages\":%d,\"countRows\":%d}"
                .formatted(CID, SERVICE_ID, countMessages, countRows);
    }

    private static String row(int rowNumber, String price) {
        return "{\"rowNumber\":%d,\"price\":%s}".formatted(rowNumber, price);
    }
}
