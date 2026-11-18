package ru.projectx.datareference.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.repository.database.DatabaseClockRepository;
import ru.projectx.datareference.repository.inbound.InboundMessageRepository;
import ru.projectx.datareference.repository.inbound.QuarantineRepository;
import ru.projectx.datareference.repository.inbound.StatusBacklog;
import ru.projectx.datareference.service.retention.ExpiredPolicyPersonalData;
import ru.projectx.datareference.support.ImportSettings;

@ExtendWith(MockitoExtension.class)
class InboundBacklogSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-03-14T09:15:00Z");
    private static final Instant DATABASE_NOW = Instant.parse("2026-10-20T08:30:00.123456Z");
    private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(90);
    private static final Duration PAYLOAD_TTL = Duration.ofDays(31);
    private static final Duration QUARANTINE_TTL = Duration.ofDays(45);

    @Mock
    private InboundMessageRepository repository;

    @Mock
    private QuarantineRepository quarantine;

    @Mock
    private ExpiredPolicyPersonalData expiredPolicies;

    @Mock
    private DatabaseClockRepository databaseClock;

    @Test
    void snapshot_carriesEveryStatusRowCountsAndTheCurrentMoment() {
        List<StatusBacklog> rows = List.of(
                row(OneCStream.POLICIES, InboundStatus.NEW, 2, DATABASE_NOW.minus(Duration.ofMinutes(40))),
                row(OneCStream.MKB10, InboundStatus.FAILED, 1, DATABASE_NOW.minus(Duration.ofDays(2))));
        when(databaseClock.currentMoment()).thenReturn(DATABASE_NOW);
        when(repository.countUnfinishedByStreamAndStatus()).thenReturn(rows);
        when(repository.countTransfersBufferedBefore(OneCStream.PRICE_LISTS, DATABASE_NOW.minus(STUCK_THRESHOLD)))
                .thenReturn(3L);
        when(repository.countTransfersBufferedBefore(OneCStream.INSURED_ASSOCIATIONS, DATABASE_NOW.minus(STUCK_THRESHOLD)))
                .thenReturn(7L);
        when(repository.countPayloadsToForgetReceivedBefore(DATABASE_NOW.minus(PAYLOAD_TTL))).thenReturn(4L);
        when(quarantine.countBodiesReceivedBefore(DATABASE_NOW.minus(QUARANTINE_TTL))).thenReturn(5L);
        when(expiredPolicies.countAt(NOW)).thenReturn(6L);

        InboundBacklog backlog = reads().read();

        assertThat(backlog).isEqualTo(new InboundBacklog(
                List.of(new InboundBacklog.StatusCount(OneCStream.POLICIES, InboundStatus.NEW, 2,
                                NOW.minus(Duration.ofMinutes(40))),
                        new InboundBacklog.StatusCount(OneCStream.MKB10, InboundStatus.FAILED, 1,
                                NOW.minus(Duration.ofDays(2)))),
                new InboundBacklog.StuckTransfers(3, 7), new InboundBacklog.Overdue(4, 5, 6), NOW));
    }

    private InboundBacklogReads reads() {
        return new InboundBacklogReads(repository, quarantine, expiredPolicies,
                ImportSettings.defaults().stuckTransferThreshold(STUCK_THRESHOLD).payloadTtl(PAYLOAD_TTL)
                        .quarantineTtl(QUARANTINE_TTL).build(),
                Clock.fixed(NOW, ZoneOffset.UTC), databaseClock);
    }

    private static StatusBacklog row(OneCStream stream, InboundStatus status, long messages, Instant oldest) {
        StatusBacklog row = mock(StatusBacklog.class);
        when(row.getStream()).thenReturn(stream);
        when(row.getStatus()).thenReturn(status);
        when(row.getMessages()).thenReturn(messages);
        when(row.getOldestReceivedAt()).thenReturn(oldest);
        return row;
    }
}
