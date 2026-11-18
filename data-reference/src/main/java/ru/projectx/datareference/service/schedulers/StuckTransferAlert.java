package ru.projectx.datareference.service.schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import ru.projectx.datareference.config.ImportProperties;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.repository.database.DatabaseClockRepository;
import ru.projectx.datareference.repository.inbound.BufferedTransfer;
import ru.projectx.datareference.service.database.SqlStates;
import ru.projectx.datareference.service.onec.SupersededTransferClosure;

@Component
public class StuckTransferAlert {

    private static final Logger log = LoggerFactory.getLogger(StuckTransferAlert.class);

    private static final List<StuckTransferNotice> NOTICES = List.of(
            new StuckTransferNotice(OneCStream.PRICE_LISTS, "передачи прайсов",
                    "Прайс по их услугам остался прежним"),
            new StuckTransferNotice(OneCStream.INSURED_ASSOCIATIONS, "передачи связей застрахованных",
                    "Связи по их отбору остались прежними"));

    private final AgedInboundMessages agedMessages;
    private final SupersededTransferClosure closure;
    private final ImportProperties properties;
    private final DatabaseClockRepository databaseClock;

    public StuckTransferAlert(AgedInboundMessages agedMessages, SupersededTransferClosure closure,
            ImportProperties properties, DatabaseClockRepository databaseClock) {

        this.agedMessages = agedMessages;
        this.closure = closure;
        this.properties = properties;
        this.databaseClock = databaseClock;
    }

    public void reportStuckTransfers() {
        Duration threshold = properties.stuckTransferThreshold();
        for (BufferedTransfer transfer : agedMessages.allTransfersBufferedLongerThan(OneCStream.PRICE_LISTS, threshold)) {
            closeIfSuperseded(transfer.getCorrelationId());
        }
        for (StuckTransferNotice notice : NOTICES) {
            report(notice, agedMessages.transfersBufferedLongerThan(notice.stream(), threshold,
                    properties.stuckTransferReportLimit()));
        }
    }

    private void closeIfSuperseded(UUID cid) {
        try {
            closure.closeIfSuperseded(cid);
        }
        catch (DataAccessException failure) {
            log.warn("передачу прайса {} не удалось проверить на закрытие: {}, SQLSTATE {}; "
                            + "следующая проверка попробует снова",
                    cid, failure.getClass().getSimpleName(), SqlStates.deepestOrNone(failure));
        }
    }

    private void report(StuckTransferNotice notice, List<BufferedTransfer> stuck) {
        if (stuck.isEmpty()) {
            return;
        }
        Instant now = databaseClock.currentMoment();
        String transfers = stuck.stream()
                .map(transfer -> describe(transfer, now))
                .collect(Collectors.joining(", "));
        log.warn("{} не собрались за {}: {}. {}; недостающие сообщения может прислать только источник{}",
                notice.transfers(), properties.stuckTransferThreshold(), transfers, notice.consequence(),
                limitNote(stuck.size()));
    }

    private static String describe(BufferedTransfer transfer, Instant now) {
        return "%s (ждёт %s, частей %d)".formatted(transfer.getCorrelationId(),
                Duration.between(transfer.getFirstPartReceivedAt(), now), transfer.getParts());
    }

    private String limitNote(int reported) {
        int limit = properties.stuckTransferReportLimit();
        return reported < limit ? "" : ". Названы %d самых давних, зависших может быть больше".formatted(limit);
    }

    private record StuckTransferNotice(OneCStream stream, String transfers, String consequence) {
    }
}
