package ru.projectx.datareference.service.metrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.projectx.datareference.config.ImportProperties;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.repository.database.DatabaseClockRepository;
import ru.projectx.datareference.repository.inbound.InboundMessageRepository;
import ru.projectx.datareference.repository.inbound.QuarantineRepository;
import ru.projectx.datareference.repository.inbound.StatusBacklog;
import ru.projectx.datareference.service.retention.ExpiredPolicyPersonalData;

@Component
public class InboundBacklogReads {

    private final InboundMessageRepository inboundMessages;
    private final QuarantineRepository quarantine;
    private final ExpiredPolicyPersonalData expiredPolicies;
    private final ImportProperties properties;
    private final Clock clock;
    private final DatabaseClockRepository databaseClock;

    public InboundBacklogReads(InboundMessageRepository inboundMessages, QuarantineRepository quarantine,
            ExpiredPolicyPersonalData expiredPolicies, ImportProperties properties, Clock clock,
            DatabaseClockRepository databaseClock) {

        this.inboundMessages = inboundMessages;
        this.quarantine = quarantine;
        this.expiredPolicies = expiredPolicies;
        this.properties = properties;
        this.clock = clock;
        this.databaseClock = databaseClock;
    }

    @Transactional(readOnly = true)
    public InboundBacklog read() {
        Instant now = clock.instant();
        Instant databaseNow = databaseClock.currentMoment();
        Duration instanceClockAhead = Duration.between(databaseNow, now);
        return new InboundBacklog(
                inboundMessages.countUnfinishedByStreamAndStatus().stream()
                        .map(row -> statusCount(row, instanceClockAhead))
                        .toList(),
                stuckTransfers(databaseNow.minus(properties.stuckTransferThreshold())),
                new InboundBacklog.Overdue(
                        inboundMessages.countPayloadsToForgetReceivedBefore(databaseNow.minus(properties.payloadTtl())),
                        quarantine.countBodiesReceivedBefore(databaseNow.minus(properties.quarantineTtl())),
                        expiredPolicies.countAt(now)),
                now);
    }

    private InboundBacklog.StuckTransfers stuckTransfers(Instant bufferedBefore) {
        return new InboundBacklog.StuckTransfers(
                inboundMessages.countTransfersBufferedBefore(OneCStream.PRICE_LISTS, bufferedBefore),
                inboundMessages.countTransfersBufferedBefore(OneCStream.INSURED_ASSOCIATIONS, bufferedBefore));
    }

    private static InboundBacklog.StatusCount statusCount(StatusBacklog row, Duration instanceClockAhead) {
        return new InboundBacklog.StatusCount(row.getStream(), row.getStatus(), row.getMessages(),
                row.getOldestReceivedAt().plus(instanceClockAhead));
    }
}
