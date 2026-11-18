package ru.projectx.datareference.service.onec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.projectx.datareference.support.OneCMessages.envelope;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentMatchers;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundReceipt;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.exception.OneCFieldException;
import ru.projectx.datareference.mapper.stream.PriceListMapper;
import ru.projectx.datareference.repository.inbound.InboundMessageRepository;
import ru.projectx.datareference.repository.inbound.PriceListFinalizationRepository;
import tools.jackson.databind.json.JsonMapper;

class PriceListTransferRejectionTest {

    private static final UUID CID = UUID.fromString("8e4b0f70-0000-4000-8000-00000000000c");
    private static final UUID SERVICE_ID = UUID.fromString("8e4b0f70-0000-4000-8000-000000000003");
    private static final Instant NOW = Instant.parse("2026-03-14T09:15:00Z");
    private static final String GOOD_ROW = "{\"rowNumber\":1,\"price\":100}";

    private final InboundMessageRepository repository = mock(InboundMessageRepository.class);
    private final PriceListMapper priceList = mock(PriceListMapper.class);
    private final PriceListFinalizationRepository finalizations = mock(PriceListFinalizationRepository.class);
    private final PriceListTransferRejection rejection = new PriceListTransferRejection(repository,
            new PriceTransferServiceLock(repository, finalizations), priceList, JsonMapper.builder().build());

    @Test
    void serviceRowIsLocked_beforeTransferPartsAreRead() {
        InboundMessage trigger = received(batchData(GOOD_ROW));
        when(repository.findByCorrelationId(CID)).thenReturn(List.of());

        rejection.reject(trigger, "база данных отклонила запись: X, SQLSTATE 22003");

        InOrder steps = inOrder(finalizations, repository);
        steps.verify(finalizations).lockByService(SERVICE_ID);
        steps.verify(repository).findByCorrelationId(CID);
    }

    @Test
    void serviceOfBufferedParts_isLocked_whenPartNamesAnotherService() {
        UUID otherService = UUID.randomUUID();
        InboundMessage trigger = received(batchData(GOOD_ROW).replace(SERVICE_ID.toString(), otherService.toString()));
        when(repository.findBufferedTransferService(CID)).thenReturn(Optional.of(SERVICE_ID.toString()));
        when(repository.findByCorrelationId(CID)).thenReturn(List.of());

        rejection.reject(trigger, "части передачи " + CID + " называют разные услуги");

        InOrder steps = inOrder(finalizations, repository);
        steps.verify(finalizations).lockByService(SERVICE_ID);
        steps.verify(repository).findByCorrelationId(CID);
        verify(finalizations, never()).lockByService(otherService);
    }

    @Test
    void partNamingAnotherService_keepsItsOwnReason_andOthersPointToIt() {
        InboundMessage other = buffered(null);
        String otherService = UUID.randomUUID().toString();
        InboundMessage trigger = received(batchData(GOOD_ROW).replace(SERVICE_ID.toString(), otherService));
        when(repository.findBufferedTransferServices(CID)).thenReturn(List.of(SERVICE_ID.toString()));
        when(repository.findByCorrelationId(CID)).thenReturn(List.of(other));
        String reason = "части передачи " + CID + " называют разные услуги";

        rejection.reject(trigger, reason);

        assertFailed(trigger, reason);
        assertFailed(other, "передача " + CID + " отклонена: ошибка в сообщении " + trigger.receipt().messageId()
                + ": " + reason);
    }

    @Test
    void partWithoutService_keepsItsOwnReason_andOthersPointToIt() {
        InboundMessage other = buffered(null);
        InboundMessage trigger = received("{\"cid\":\"%s\",\"recordSet\":[%s]}".formatted(CID, GOOD_ROW));
        when(repository.findByCorrelationId(CID)).thenReturn(List.of(other));
        String reason = "нет обязательного GUID в поле keyValue";

        rejection.reject(trigger, reason);

        assertFailed(trigger, reason);
        assertFailed(other, "передача " + CID + " отклонена: ошибка в сообщении " + trigger.receipt().messageId()
                + ": " + reason);
    }

    @Test
    void finalMessageWithCounterBeyondInt_keepsItsOwnReason_andBufferedBatchPointsToIt() {
        InboundMessage batch = buffered(null);
        InboundMessage trigger = received("{\"cid\":\"%s\",\"keyValue\":\"%s\",\"countMessages\":1e10,\"countRows\":1}"
                .formatted(CID, SERVICE_ID));
        when(repository.findByCorrelationId(CID)).thenReturn(List.of(batch));
        String reason = "поле countMessages больше допустимого";

        assertThat(rejection.reject(trigger, reason)).containsExactly(trigger, batch);

        assertFailed(trigger, reason);
        assertFailed(batch, "передача " + CID + " отклонена: ошибка в сообщении " + trigger.receipt().messageId()
                + ": " + reason);
        verify(finalizations).lockByService(SERVICE_ID);
    }

    @Test
    void firstFinalMessageWithCounterBeyondInt_isRecordedInItsTransferWithoutCounters() {
        InboundMessage trigger = received("{\"cid\":\"%s\",\"keyValue\":\"%s\",\"countMessages\":1,\"countRows\":1e10}"
                .formatted(CID, SERVICE_ID));
        when(repository.findByCorrelationId(CID)).thenReturn(List.of());

        assertThat(rejection.reject(trigger, "поле countRows больше допустимого")).containsExactly(trigger);

        assertFailed(trigger, "поле countRows больше допустимого");
        assertThat(trigger.transferPart().correlationId()).isEqualTo(CID);
        assertThat(trigger.transferPart().batchCount()).isNull();
        assertThat(CorrelatedTransfer.of(CID, List.of(trigger)))
                .isEqualTo(new CorrelatedTransfer.Rejected("передача " + CID + " уже отклонена", List.of()));
    }

    @Test
    void decidesRejectionsOfPriceListStream() {
        assertThat(rejection.stream()).isEqualTo(OneCStream.PRICE_LISTS);
    }

    @Test
    void failureWhileAssembling_failsEveryBufferedPartWithTransferReason() {
        InboundMessage finalMessage = buffered(1);
        InboundMessage batch = buffered(null);
        InboundMessage trigger = received(batchData(GOOD_ROW));
        when(repository.findByCorrelationId(CID)).thenReturn(List.of(finalMessage, batch));
        String reason = "передача " + CID + " отклонена: база данных отклонила запись: X, SQLSTATE 22003";

        List<InboundMessage> rejected = rejection.reject(trigger, "база данных отклонила запись: X, SQLSTATE 22003");

        assertThat(rejected).containsExactly(trigger, finalMessage, batch);
        assertThat(rejected).allSatisfy(message -> assertFailed(message, reason));
    }

    @Test
    void exhaustedRetries_failEveryBufferedPartWithTransferReason() {
        InboundMessage batch = buffered(null);
        InboundMessage trigger = received(finalData());
        when(repository.findByCorrelationId(CID)).thenReturn(List.of(batch));
        String retries = "повторы исчерпаны на попытке 6: временный отказ базы данных: X, SQLSTATE 55P03";

        assertThat(rejection.reject(trigger, retries)).containsExactly(trigger, batch);
        assertFailed(trigger, "передача " + CID + " отклонена: " + retries);
        assertFailed(batch, "передача " + CID + " отклонена: " + retries);
    }

    @Test
    void defectOfThePart_keepsItsOwnReason_andOthersPointToIt() {
        InboundMessage batch = buffered(null);
        InboundMessage trigger = received(batchData("{\"price\":100}"));
        when(repository.findByCorrelationId(CID)).thenReturn(List.of(batch));

        List<InboundMessage> rejected = rejection.reject(trigger, "нет обязательного поля rowNumber");

        assertThat(rejected).containsExactly(trigger, batch);
        assertFailed(trigger, "нет обязательного поля rowNumber");
        assertFailed(batch, "передача %s отклонена: ошибка в сообщении %s: нет обязательного поля rowNumber"
                .formatted(CID, trigger.receipt().messageId()));
    }

    @Test
    void ownBufferedCopy_isNotRejectedAsAnotherPart() {
        InboundMessage trigger = received(batchData(GOOD_ROW));
        InboundMessage ownCopy = InboundMessage.receive(trigger.receipt(), envelope(batchData(GOOD_ROW)));
        ownCopy.markBuffered(CID, null, 1);
        InboundMessage batch = buffered(null);
        when(repository.findByCorrelationId(CID)).thenReturn(List.of(ownCopy, batch));

        List<InboundMessage> rejected = rejection.reject(trigger, "исчерпаны повторы");

        assertThat(rejected).containsExactly(trigger, batch);
        assertThat(ownCopy.outcome().status()).isEqualTo(InboundStatus.BUFFERED);
    }

    @Test
    void rowBreakingPriceListRules_isDefectOfThePart() {
        InboundMessage finalMessage = buffered(1);
        InboundMessage trigger = received(batchData(GOOD_ROW));
        doThrow(new OneCFieldException("в строке прайса нет цены")).when(priceList).checkRow(ArgumentMatchers.any());
        when(repository.findByCorrelationId(CID)).thenReturn(List.of(finalMessage));

        rejection.reject(trigger, "в строке прайса нет цены");

        assertFailed(trigger, "в строке прайса нет цены");
        assertFailed(finalMessage, "передача %s отклонена: ошибка в сообщении %s: в строке прайса нет цены"
                .formatted(CID, trigger.receipt().messageId()));
    }

    @Test
    void surplusBatch_failsAlone_andAppliedPartsKeepTheirOutcome() {
        InboundMessage applied = buffered(null);
        applied.markProcessed(NOW);
        InboundMessage trigger = received(batchData(GOOD_ROW));
        when(repository.findByCorrelationId(CID)).thenReturn(List.of(applied));
        String reason = "лишний пакет: передача " + CID + " уже применена";

        assertThat(rejection.reject(trigger, reason)).containsExactly(trigger);
        assertFailed(trigger, reason);
        assertThat(applied.outcome().status()).isEqualTo(InboundStatus.PROCESSED);
    }

    @Test
    void rejectedPart_remembersItsTransfer() {
        InboundMessage trigger = received(finalData());
        when(repository.findByCorrelationId(CID)).thenReturn(List.of());

        rejection.reject(trigger, "непредвиденная ошибка разбора: X");

        assertThat(trigger.transferPart().correlationId()).isEqualTo(CID);
        assertThat(trigger.transferPart().batchCount()).isEqualTo(1);
        assertFailed(trigger, "передача " + CID + " отклонена: непредвиденная ошибка разбора: X");
    }

    @Test
    void partWithoutTransferId_failsAlone() {
        InboundMessage trigger = received("{\"keyValue\":\"%s\",\"recordSet\":[]}".formatted(SERVICE_ID));

        assertThat(rejection.reject(trigger, "нет обязательного GUID в поле cid")).containsExactly(trigger);
        assertFailed(trigger, "нет обязательного GUID в поле cid");
        verifyNoInteractions(repository);
    }

    @Test
    void partWithMalformedTransferId_failsAlone() {
        InboundMessage trigger = received("{\"cid\":\"не GUID\",\"recordSet\":[]}");

        assertThat(rejection.reject(trigger, "поле cid не является GUID")).containsExactly(trigger);
        assertFailed(trigger, "поле cid не является GUID");
        verifyNoInteractions(repository);
    }

    @Test
    void unreadableOrErasedBody_failsAlone() {
        InboundMessage erased = InboundMessage.receive(receipt(), null);
        InboundMessage malformed = InboundMessage.receive(receipt(), "{не JSON");
        InboundMessage withoutData = InboundMessage.receive(receipt(), "{\"data\":[]}");

        assertThat(List.of(erased, malformed, withoutData)).allSatisfy(message -> {
            assertThat(rejection.reject(message, "причина")).containsExactly(message);
            assertFailed(message, "причина");
        });
        verifyNoInteractions(repository);
    }

    private static void assertFailed(InboundMessage message, String reason) {
        assertThat(message.outcome().status()).isEqualTo(InboundStatus.FAILED);
        assertThat(message.outcome().lastError()).isEqualTo(reason);
    }

    private static InboundMessage buffered(Integer batchCount) {
        InboundMessage message = received(batchData(GOOD_ROW));
        message.markBuffered(CID, batchCount, 1);
        return message;
    }

    private static InboundMessage received(String data) {
        return InboundMessage.receive(receipt(), envelope(data));
    }

    private static InboundReceipt receipt() {
        return new InboundReceipt(UUID.randomUUID(), OneCStream.PRICE_LISTS, "очередь", NOW);
    }

    private static String batchData(String... rows) {
        return "{\"cid\":\"%s\",\"keyValue\":\"%s\",\"recordSet\":[%s]}"
                .formatted(CID, SERVICE_ID, String.join(",", rows));
    }

    private static String finalData() {
        return "{\"cid\":\"%s\",\"keyValue\":\"%s\",\"countMessages\":1,\"countRows\":1}".formatted(CID, SERVICE_ID);
    }
}
