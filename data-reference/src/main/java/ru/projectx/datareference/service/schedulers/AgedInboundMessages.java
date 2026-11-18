package ru.projectx.datareference.service.schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.repository.database.DatabaseClockRepository;
import ru.projectx.datareference.repository.inbound.BufferedTransfer;
import ru.projectx.datareference.repository.inbound.InboundMessageRepository;
import ru.projectx.datareference.repository.inbound.QuarantineRepository;

@Component
public class AgedInboundMessages {

    private final InboundMessageRepository repository;
    private final QuarantineRepository quarantine;
    private final DatabaseClockRepository databaseClock;

    public AgedInboundMessages(InboundMessageRepository repository, QuarantineRepository quarantine,
            DatabaseClockRepository databaseClock) {
        this.repository = repository;
        this.quarantine = quarantine;
        this.databaseClock = databaseClock;
    }

    public int forgetPayloads(Duration age, int limit) {
        return repository.forgetPayloadsReceivedBefore(receivedBefore(age), limit);
    }

    public int forgetQuarantineBodies(Duration age, int limit) {
        return quarantine.forgetBodies(receivedBefore(age), limit);
    }

    public List<BufferedTransfer> transfersBufferedLongerThan(OneCStream stream, Duration age, int limit) {
        return repository.findTransfersBufferedBefore(stream, receivedBefore(age), Limit.of(limit));
    }

    public List<BufferedTransfer> allTransfersBufferedLongerThan(OneCStream stream, Duration age) {
        return repository.findTransfersBufferedBefore(stream, receivedBefore(age), Limit.unlimited());
    }

    private Instant receivedBefore(Duration age) {
        return databaseClock.currentMoment().minus(age);
    }
}
