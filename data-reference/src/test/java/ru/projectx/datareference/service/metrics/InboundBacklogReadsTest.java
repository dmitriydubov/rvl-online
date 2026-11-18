package ru.projectx.datareference.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static ru.projectx.datareference.support.OneCMessages.envelope;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.assertj.core.data.TemporalUnitOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.entity.inbound.RejectedMessage;
import ru.projectx.datareference.repository.database.DatabaseClockRepository;
import ru.projectx.datareference.service.inbound.InboundMessageService;
import ru.projectx.datareference.service.inbound.QuarantineService;
import ru.projectx.datareference.support.DatabaseCleaner;
import ru.projectx.datareference.support.FixedClockConfig;
import ru.projectx.datareference.support.IntegrationTest;
import ru.projectx.datareference.support.TestJdbc;

@IntegrationTest
class InboundBacklogReadsTest {

    private static final String QUEUE = "1c-usk_to_rvl-online_policies";
    private static final Instant NOW = FixedClockConfig.NOW;
    private static final TemporalUnitOffset ON_TIME = within(1, ChronoUnit.SECONDS);

    @Autowired
    private InboundBacklogReads reads;

    @Autowired
    private InboundMessageService inboundMessageService;

    @Autowired
    private QuarantineService quarantineService;

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
    void emptyTables_giveEmptySnapshotAtTheCurrentMoment() {
        InboundBacklog backlog = reads.read();

        assertThat(backlog.statuses()).isEmpty();
        assertThat(backlog.stuckTransfers()).isEqualTo(new InboundBacklog.StuckTransfers(0, 0));
        assertThat(backlog.overdue()).isEqualTo(new InboundBacklog.Overdue(0, 0, 0));
        assertThat(backlog.takenAt()).isEqualTo(NOW);
    }

    @Test
    void unfinishedMessages_areCountedByStreamAndStatusWithTheOldest() {
        store(OneCStream.POLICIES, InboundStatus.NEW, Duration.ofMinutes(10));
        store(OneCStream.POLICIES, InboundStatus.NEW, Duration.ofMinutes(40));
        store(OneCStream.POLICIES, InboundStatus.FAILED, Duration.ofDays(2));
        store(OneCStream.MKB10, InboundStatus.RETRY, Duration.ofMinutes(5));
        store(OneCStream.MKB10, InboundStatus.PROCESSED, Duration.ofDays(3));
        store(OneCStream.MKB10, InboundStatus.SKIPPED, Duration.ofDays(3));

        InboundBacklog backlog = reads.read();

        assertThat(backlog.statuses())
                .extracting(InboundBacklog.StatusCount::stream, InboundBacklog.StatusCount::status,
                        InboundBacklog.StatusCount::messages)
                .containsExactlyInAnyOrder(tuple(OneCStream.POLICIES, InboundStatus.NEW, 2L),
                        tuple(OneCStream.POLICIES, InboundStatus.FAILED, 1L), tuple(OneCStream.MKB10, InboundStatus.RETRY, 1L));
        assertThat(oldest(backlog, OneCStream.POLICIES, InboundStatus.NEW)).isCloseTo(NOW.minus(Duration.ofMinutes(40)), ON_TIME);
        assertThat(oldest(backlog, OneCStream.POLICIES, InboundStatus.FAILED)).isCloseTo(NOW.minus(Duration.ofDays(2)), ON_TIME);
        assertThat(oldest(backlog, OneCStream.MKB10, InboundStatus.RETRY)).isCloseTo(NOW.minus(Duration.ofMinutes(5)), ON_TIME);
    }

    @Test
    void stuckTransfers_areCountedOncePerTransfer() {
        UUID stuck = UUID.randomUUID();
        buffer(stuck, Duration.ofHours(3));
        buffer(stuck, Duration.ofHours(2));
        buffer(stuck, Duration.ofMinutes(5));
        buffer(UUID.randomUUID(), Duration.ofHours(2));
        buffer(UUID.randomUUID(), Duration.ofMinutes(30));

        assertThat(reads.read().stuckTransfers()).isEqualTo(new InboundBacklog.StuckTransfers(2, 0));
    }

    @Test
    void stuckTransfers_areCountedPerStream() {
        buffer(UUID.randomUUID(), Duration.ofHours(3));
        buffer(OneCStream.INSURED_ASSOCIATIONS, UUID.randomUUID(), Duration.ofHours(2));
        buffer(OneCStream.INSURED_ASSOCIATIONS, UUID.randomUUID(), Duration.ofHours(4));

        assertThat(reads.read().stuckTransfers()).isEqualTo(new InboundBacklog.StuckTransfers(1, 2));
    }

    @Test
    void overduePayloads_areCountedForPoliciesAndQuarantine() {
        store(OneCStream.POLICIES, InboundStatus.PROCESSED, Duration.ofDays(31));
        store(OneCStream.POLICIES, InboundStatus.PROCESSED, Duration.ofDays(29));
        store(OneCStream.MKB10, InboundStatus.PROCESSED, Duration.ofDays(40));
        UUID erased = store(OneCStream.POLICIES, InboundStatus.PROCESSED, Duration.ofDays(40));
        testJdbc.update("update inbound_message set payload = null where message_id = ?", erased);
        quarantine(Duration.ofDays(31));
        quarantine(Duration.ofDays(35));
        quarantine(Duration.ofDays(1));

        assertThat(reads.read().overdue()).isEqualTo(new InboundBacklog.Overdue(1, 2, 0));
    }

    private static Instant oldest(InboundBacklog backlog, OneCStream stream, InboundStatus status) {
        return backlog.statuses().stream()
                .filter(count -> count.stream() == stream && count.status() == status)
                .map(InboundBacklog.StatusCount::oldestReceivedAt)
                .findFirst()
                .orElseThrow();
    }

    private UUID store(OneCStream stream, InboundStatus status, Duration age) {
        UUID messageId = UUID.randomUUID();
        inboundMessageService.store(messageId, stream, QUEUE, envelope("{\"id\":\"%s\"}".formatted(messageId)));
        testJdbc.update("update inbound_message set status = ?, received_at = ? where message_id = ?",
                status.name(), Timestamp.from(databaseNow.minus(age)), messageId);
        return messageId;
    }

    private void buffer(UUID transfer, Duration age) {
        buffer(OneCStream.PRICE_LISTS, transfer, age);
    }

    private void buffer(OneCStream stream, UUID transfer, Duration age) {
        UUID part = store(stream, InboundStatus.BUFFERED, age);
        testJdbc.update("update inbound_message set correlation_id = ? where message_id = ?", transfer, part);
    }

    private void quarantine(Duration age) {
        UUID messageId = UUID.randomUUID();
        quarantineService.quarantine(new RejectedMessage(messageId, OneCStream.POLICIES, QUEUE, new byte[] {1}), "22P05");
        testJdbc.update("update quarantine set received_at = ? where message_id = ?",
                Timestamp.from(databaseNow.minus(age)), messageId);
    }
}
