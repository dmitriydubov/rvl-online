package ru.projectx.datareference.service.schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static ru.projectx.datareference.support.OneCMessages.envelope;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionOperations;
import ru.projectx.datareference.entity.inbound.InboundOutcome;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.entity.inbound.RejectedMessage;
import ru.projectx.datareference.repository.database.DatabaseClockRepository;
import ru.projectx.datareference.repository.inbound.BufferedTransfer;
import ru.projectx.datareference.repository.inbound.InboundMessageRepository;
import ru.projectx.datareference.repository.inbound.QuarantineRepository;
import ru.projectx.datareference.service.inbound.InboundMessageService;
import ru.projectx.datareference.service.inbound.QuarantineService;
import ru.projectx.datareference.support.DatabaseCleaner;
import ru.projectx.datareference.support.IntegrationTest;
import ru.projectx.datareference.support.TestJdbc;

@IntegrationTest
class AgedInboundMessagesQueryTest {

    private static final String QUEUE = "1c-usk_to_rvl-online_policies";
    private static final String REASON = "причина";
    private static final UUID OLD_TRANSFER = UUID.fromString("c1d2e3f4-0000-4000-8000-000000000001");
    private static final UUID OLDER_TRANSFER = UUID.fromString("c1d2e3f4-0000-4000-8000-000000000002");
    private static final UUID FRESH_TRANSFER = UUID.fromString("c1d2e3f4-0000-4000-8000-000000000003");

    @Autowired
    private AgedInboundMessages agedMessages;

    @Autowired
    private TransactionOperations transactions;

    @Autowired
    private InboundMessageService inboundMessageService;

    @Autowired
    private InboundMessageRepository inboundMessageRepository;

    @Autowired
    private QuarantineService quarantineService;

    @Autowired
    private QuarantineRepository quarantineRepository;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestJdbc testJdbc;

    @Autowired
    private DatabaseClockRepository databaseClock;

    private Instant databaseNow;

    @BeforeEach
    void clearTables() {
        databaseCleaner.clear();
        databaseNow = databaseClock.currentMoment();
    }

    @Test
    void forgetPayloads_erasesOldestRowsUpToLimit() {
        UUID oldest = store(OneCStream.POLICIES, Duration.ofDays(40));
        UUID older = store(OneCStream.POLICIES, Duration.ofDays(35));
        UUID old = store(OneCStream.POLICIES, Duration.ofDays(31));

        int erased = forget(2);

        assertThat(erased).isEqualTo(2);
        assertThat(payloadOf(oldest)).isNull();
        assertThat(payloadOf(older)).isNull();
        assertThat(payloadOf(old)).isNotNull();
    }

    @Test
    void forgetPayloads_skipsFreshOtherStreamAndErasedRows() {
        UUID fresh = store(OneCStream.POLICIES, Duration.ofDays(30).minusMinutes(1));
        UUID reference = store(OneCStream.MKB10, Duration.ofDays(40));
        store(OneCStream.POLICIES, Duration.ofDays(40));
        forget(10);

        assertThat(forget(10)).isZero();
        assertThat(payloadOf(fresh)).isNotNull();
        assertThat(payloadOf(reference)).isNotNull();
    }

    @Test
    void forgetPayloads_erasesTheReasonTogetherWithTheBody() {
        UUID policy = store(OneCStream.POLICIES, Duration.ofDays(40));
        UUID reference = store(OneCStream.MKB10, Duration.ofDays(40));
        testJdbc.update("update inbound_message set status = 'FAILED', last_error = 'причина' where message_id in (?, ?)",
                policy, reference);

        forget(10);

        InboundOutcome erased = inboundMessageRepository.findById(policy).orElseThrow().outcome();
        assertThat(payloadOf(policy)).isNull();
        assertThat(erased.lastError()).isNull();
        assertThat(erased.status()).isEqualTo(InboundStatus.FAILED);
        assertThat(inboundMessageRepository.findById(reference).orElseThrow().outcome().lastError())
                .isEqualTo("причина");
    }

    @Test
    void forgetPayloads_erasesInsuranceContractBodiesInEveryStatusAndKeepsTheOutcome() {
        List<UUID> contracts = Stream.of("NEW", "RETRY", "PROCESSED", "SKIPPED", "FAILED")
                .map(status -> storeWithOutcome(OneCStream.INSURANCE_CONTRACTS, status))
                .toList();

        assertThat(forget(10)).isEqualTo(contracts.size());

        assertThat(contracts).allSatisfy(contract -> {
            assertThat(payloadOf(contract)).isNull();
            assertThat(outcomeOf(contract).lastError()).isEqualTo(REASON);
            assertThat(outcomeOf(contract).attempt()).isEqualTo(2);
        });
        assertThat(contracts).extracting(contract -> outcomeOf(contract).status().name())
                .containsExactly("NEW", "RETRY", "PROCESSED", "SKIPPED", "FAILED");
    }

    @Test
    void forgetPayloads_erasesSkippedBodiesOfEveryStreamAndKeepsOtherReferenceBodies() {
        UUID skipped = storeWithOutcome(OneCStream.MKB10, "SKIPPED");
        List<UUID> kept = Stream.of("NEW", "RETRY", "PROCESSED", "FAILED", "BUFFERED")
                .map(status -> storeWithOutcome(OneCStream.MKB10, status))
                .toList();

        assertThat(forget(10)).isEqualTo(1);

        assertThat(payloadOf(skipped)).isNull();
        assertThat(outcomeOf(skipped).lastError()).isEqualTo(REASON);
        assertThat(kept).allSatisfy(reference -> assertThat(payloadOf(reference)).isNotNull());
    }

    @Test
    void forgetPayloads_erasesTheReasonOfSkippedPolicyOnly() {
        UUID policy = storeWithOutcome(OneCStream.POLICIES, "SKIPPED");
        UUID reference = storeWithOutcome(OneCStream.CONTRAGENTS, "SKIPPED");

        forget(10);

        assertThat(outcomeOf(policy).lastError()).isNull();
        assertThat(outcomeOf(reference).lastError()).isEqualTo(REASON);
    }

    @Test
    void countPayloadsToForget_matchesWhatTheErasePassErases() {
        Stream.of(OneCStream.POLICIES, OneCStream.INSURANCE_CONTRACTS).forEach(stream -> storeWithOutcome(stream, "FAILED"));
        Stream.of("SKIPPED", "PROCESSED", "FAILED").forEach(status -> storeWithOutcome(OneCStream.PROGRAMS, status));
        store(OneCStream.INSURANCE_CONTRACTS, Duration.ofDays(29));
        Instant receivedBefore = databaseNow.minus(Duration.ofDays(30));

        long counted = inboundMessageRepository.countPayloadsToForgetReceivedBefore(receivedBefore);

        assertThat(counted).isEqualTo(3);
        assertThat(forget(10)).isEqualTo(counted);
        assertThat(inboundMessageRepository.countPayloadsToForgetReceivedBefore(receivedBefore)).isZero();
    }

    @Test
    void transfersBufferedLongerThan_groupsPartsAndOrdersByFirstPart() {
        buffer(OLD_TRANSFER, Duration.ofHours(3));
        buffer(OLD_TRANSFER, Duration.ofMinutes(10));
        buffer(OLDER_TRANSFER, Duration.ofHours(5));
        buffer(FRESH_TRANSFER, Duration.ofMinutes(30));

        assertThat(agedMessages.transfersBufferedLongerThan(OneCStream.PRICE_LISTS, Duration.ofHours(1), 10))
                .extracting(BufferedTransfer::getCorrelationId, BufferedTransfer::getFirstPartReceivedAt,
                        BufferedTransfer::getParts)
                .containsExactly(
                        tuple(OLDER_TRANSFER,
                                databaseNow.minus(Duration.ofHours(5)), 1L),
                        tuple(OLD_TRANSFER,
                                databaseNow.minus(Duration.ofHours(3)), 2L));
        assertThat(agedMessages.transfersBufferedLongerThan(OneCStream.PRICE_LISTS, Duration.ofHours(1), 1))
                .extracting(BufferedTransfer::getCorrelationId).containsExactly(OLDER_TRANSFER);
    }

    @Test
    void transfersBufferedLongerThan_ignoresRowsOutsideBuffer() {
        UUID processed = store(OneCStream.PRICE_LISTS, Duration.ofHours(5));
        testJdbc.update("update inbound_message set status = 'PROCESSED', correlation_id = ? where message_id = ?",
                OLD_TRANSFER, processed);

        assertThat(agedMessages.transfersBufferedLongerThan(OneCStream.PRICE_LISTS, Duration.ofHours(1), 10)).isEmpty();
    }

    @Test
    void transfersBufferedLongerThan_ignoresTransfersOfAnotherStream() {
        buffer(OLD_TRANSFER, Duration.ofHours(3));

        assertThat(agedMessages.transfersBufferedLongerThan(OneCStream.INSURED_ASSOCIATIONS, Duration.ofHours(1), 10))
                .isEmpty();
    }

    @Test
    void forgetQuarantineBodies_erasesOldestBodiesUpToLimitAndKeepsRows() {
        UUID oldest = quarantine(Duration.ofDays(40));
        UUID older = quarantine(Duration.ofDays(35));
        UUID fresh = quarantine(Duration.ofDays(29));

        int erased = transactions.execute(status -> agedMessages.forgetQuarantineBodies(Duration.ofDays(30), 1));

        assertThat(erased).isEqualTo(1);
        assertThat(quarantineRepository.findById(oldest)).hasValueSatisfying(row -> {
            assertThat(row.body()).isEmpty();
            assertThat(row.reason()).isEqualTo("22P05");
        });
        assertThat(quarantineRepository.findById(older).orElseThrow().body()).isPresent();
        assertThat(quarantineRepository.findById(fresh).orElseThrow().body()).isPresent();
    }

    @Test
    void quarantineBody_withZeroByte_isStoredByteForByte() {
        byte[] poisoned = {'{', '"', 'a', '"', ':', '"', 0, '"', '}'};
        UUID messageId = UUID.randomUUID();

        quarantineService.quarantine(new RejectedMessage(messageId, OneCStream.POLICIES, QUEUE, poisoned), "22021");

        assertThat(quarantineRepository.findById(messageId).orElseThrow().body()).contains(poisoned);
    }

    private UUID quarantine(Duration age) {
        UUID messageId = UUID.randomUUID();
        quarantineService.quarantine(new RejectedMessage(messageId, OneCStream.POLICIES, QUEUE, new byte[] {1}), "22P05");
        testJdbc.update("update quarantine set received_at = ? where message_id = ?",
                Timestamp.from(databaseNow.minus(age)), messageId);
        return messageId;
    }

    private int forget(int limit) {
        return transactions.execute(status -> agedMessages.forgetPayloads(Duration.ofDays(30), limit));
    }

    private void buffer(UUID transfer, Duration age) {
        UUID part = store(OneCStream.PRICE_LISTS, age);
        testJdbc.update("update inbound_message set status = 'BUFFERED', correlation_id = ? where message_id = ?",
                transfer, part);
    }

    private UUID store(OneCStream stream, Duration age) {
        UUID messageId = UUID.randomUUID();
        inboundMessageService.store(messageId, stream, QUEUE, envelope("{\"id\":\"%s\"}".formatted(messageId)));
        testJdbc.update("update inbound_message set received_at = ? where message_id = ?",
                Timestamp.from(databaseNow.minus(age)), messageId);
        return messageId;
    }

    private UUID storeWithOutcome(OneCStream stream, String status) {
        UUID messageId = store(stream, Duration.ofDays(40));
        testJdbc.update("update inbound_message set status = ?, last_error = ?, attempt = 2 where message_id = ?",
                status, REASON, messageId);
        return messageId;
    }

    private InboundOutcome outcomeOf(UUID messageId) {
        return inboundMessageRepository.findById(messageId).orElseThrow().outcome();
    }

    private String payloadOf(UUID messageId) {
        return inboundMessageRepository.findById(messageId).orElseThrow().payload();
    }
}
