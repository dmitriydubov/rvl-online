package ru.projectx.datareference.service.metrics;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.entity.inbound.OneCStream;

public record InboundBacklog(
        List<StatusCount> statuses, StuckTransfers stuckTransfers, Overdue overdue, Instant takenAt) {

    private static final Set<InboundStatus> PENDING = Set.of(InboundStatus.NEW, InboundStatus.RETRY);

    public InboundBacklog {
        statuses = List.copyOf(statuses);
    }

    public static InboundBacklog empty(Instant takenAt) {
        return new InboundBacklog(List.of(), new StuckTransfers(0, 0), new Overdue(0, 0, 0), takenAt);
    }

    public long messages(OneCStream stream, InboundStatus status) {
        return statuses.stream()
                .filter(count -> count.stream() == stream && count.status() == status)
                .mapToLong(StatusCount::messages)
                .sum();
    }

    public Optional<Instant> oldestPending(OneCStream stream) {
        return statuses.stream()
                .filter(count -> count.stream() == stream && PENDING.contains(count.status()))
                .map(StatusCount::oldestReceivedAt)
                .min(Comparator.naturalOrder());
    }

    public record StatusCount(OneCStream stream, InboundStatus status, long messages, Instant oldestReceivedAt) {
    }

    public record StuckTransfers(long priceLists, long insuredAssociations) {
    }

    public record Overdue(long payloads, long quarantineBodies, long policies) {
    }
}
