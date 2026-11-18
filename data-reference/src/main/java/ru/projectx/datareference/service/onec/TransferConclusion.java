package ru.projectx.datareference.service.onec;

import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.mapper.ImportResult;
import ru.projectx.datareference.service.inbound.InboundMessageService;

@Component
public class TransferConclusion {

    private static final Logger log = LoggerFactory.getLogger(TransferConclusion.class);

    private final InboundMessageService inboundMessageService;
    private final Clock clock;

    public TransferConclusion(InboundMessageService inboundMessageService, Clock clock) {
        this.inboundMessageService = inboundMessageService;
        this.clock = clock;
    }

    ImportOutcome conclude(CorrelatedTransfer transfer, ParsedTransferParts parsed, TransferReplacement replacement) {
        return switch (transfer) {
            case CorrelatedTransfer.Pending pending -> ImportOutcome.skipped(pending.reason());
            case CorrelatedTransfer.Rejected rejected -> rejectLate(rejected);
            case CorrelatedTransfer.Closed closed -> skipLate(closed);
            case CorrelatedTransfer.Mismatched mismatched -> fail(mismatched);
            case CorrelatedTransfer.Assembled assembled -> apply(assembled, replacement.replace(assembled, parsed));
        };
    }

    private ImportOutcome apply(CorrelatedTransfer.Assembled transfer, ImportResult result) {
        saveAll(transfer.markOutcome(result, clock.instant()));
        return ImportOutcome.of(result);
    }

    private ImportOutcome fail(CorrelatedTransfer.Mismatched transfer) {
        log.error(transfer.reason());
        saveAll(transfer.fail());
        return ImportOutcome.rejected(transfer.reason());
    }

    private ImportOutcome rejectLate(CorrelatedTransfer.Rejected transfer) {
        log.warn(transfer.reason());
        saveAll(transfer.fail());
        return ImportOutcome.rejected(transfer.reason());
    }

    private ImportOutcome skipLate(CorrelatedTransfer.Closed transfer) {
        saveAll(transfer.skip(clock.instant()));
        return ImportOutcome.skipped(transfer.reason());
    }

    private void saveAll(List<InboundMessage> messages) {
        for (InboundMessage message : messages) {
            inboundMessageService.saveTransferPart(message);
        }
    }
}
