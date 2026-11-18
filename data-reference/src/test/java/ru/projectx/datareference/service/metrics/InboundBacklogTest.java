package ru.projectx.datareference.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.entity.inbound.OneCStream;

class InboundBacklogTest {

    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final Instant OLDER = Instant.parse("2026-10-05T09:00:00Z");
    private static final Instant OLDEST = Instant.parse("2026-10-04T23:00:00Z");

    private final InboundBacklog backlog = new InboundBacklog(List.of(
            new InboundBacklog.StatusCount(OneCStream.POLICIES, InboundStatus.NEW, 7, OLDER),
            new InboundBacklog.StatusCount(OneCStream.POLICIES, InboundStatus.RETRY, 2, OLDEST),
            new InboundBacklog.StatusCount(OneCStream.POLICIES, InboundStatus.FAILED, 3, Instant.EPOCH),
            new InboundBacklog.StatusCount(OneCStream.MKB10, InboundStatus.NEW, 5, NOW),
            new InboundBacklog.StatusCount(OneCStream.PRICE_LISTS, InboundStatus.BUFFERED, 4, Instant.EPOCH)),
            new InboundBacklog.StuckTransfers(1, 0), new InboundBacklog.Overdue(0, 0, 0), NOW);

    @Test
    void messages_areCountedForTheExactStreamAndStatus() {
        assertThat(backlog.messages(OneCStream.POLICIES, InboundStatus.NEW)).isEqualTo(7);
        assertThat(backlog.messages(OneCStream.POLICIES, InboundStatus.RETRY)).isEqualTo(2);
        assertThat(backlog.messages(OneCStream.MKB10, InboundStatus.NEW)).isEqualTo(5);
        assertThat(backlog.messages(OneCStream.MKB10, InboundStatus.RETRY)).isZero();
        assertThat(backlog.messages(OneCStream.LPU_CONTACTS, InboundStatus.NEW)).isZero();
    }

    @Test
    void oldestPending_isTheEarliestOfNewAndRetryOnly() {
        assertThat(backlog.oldestPending(OneCStream.POLICIES)).contains(OLDEST);
        assertThat(backlog.oldestPending(OneCStream.MKB10)).contains(NOW);
        assertThat(backlog.oldestPending(OneCStream.PRICE_LISTS)).isEmpty();
    }

    @Test
    void emptyBacklog_hasNothingAndKeepsItsMoment() {
        InboundBacklog empty = InboundBacklog.empty(NOW);

        assertThat(empty.statuses()).isEmpty();
        assertThat(empty.stuckTransfers()).isEqualTo(new InboundBacklog.StuckTransfers(0, 0));
        assertThat(empty.overdue()).isEqualTo(new InboundBacklog.Overdue(0, 0, 0));
        assertThat(empty.takenAt()).isEqualTo(NOW);
    }

    @Test
    void statuses_areCopied() {
        List<InboundBacklog.StatusCount> source = new ArrayList<>();
        source.add(new InboundBacklog.StatusCount(OneCStream.MKB10, InboundStatus.NEW, 1, NOW));

        InboundBacklog copy = new InboundBacklog(source, new InboundBacklog.StuckTransfers(0, 0),
                new InboundBacklog.Overdue(0, 0, 0), NOW);
        source.clear();

        assertThat(copy.messages(OneCStream.MKB10, InboundStatus.NEW)).isEqualTo(1);
    }
}
