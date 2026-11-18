package ru.projectx.datareference.service.onec;

import java.util.UUID;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.mapper.ImportResult;
import ru.projectx.datareference.mapper.StreamMapper;
import ru.projectx.datareference.service.inbound.InboundMessageService;
import tools.jackson.databind.node.ObjectNode;

class MappedStreamHandler implements StreamHandler {

    private static final String APPLIED = "применено";
    private static final String SKIPPED = "пропущено";

    private final StreamMapper mapper;
    private final InboundMessageService inboundMessageService;
    private final SupersededParentWaits supersededWaits;
    private final ParentArrivals parentArrivals;

    MappedStreamHandler(StreamMapper mapper, InboundMessageService inboundMessageService,
            SupersededParentWaits supersededWaits, ParentArrivals parentArrivals) {

        this.mapper = mapper;
        this.inboundMessageService = inboundMessageService;
        this.supersededWaits = supersededWaits;
        this.parentArrivals = parentArrivals;
    }

    @Override
    public OneCStream stream() {
        return mapper.stream();
    }

    @Override
    public ImportOutcome handle(InboundMessage message, ObjectNode data) {
        UUID messageId = message.receipt().messageId();
        return switch (mapper.apply(data)) {
            case ImportResult.Applied ignored -> {
                inboundMessageService.markProcessed(messageId);
                parentArrivals.arrived(mapper.stream(), data);
                yield closingOlderWaits(message, data, APPLIED, ImportOutcome.applied());
            }
            case ImportResult.Skipped skipped -> {
                inboundMessageService.markSkipped(messageId, skipped.reason());
                yield closingOlderWaits(message, data, SKIPPED, ImportOutcome.skipped(skipped.reason()));
            }
            case ImportResult.AwaitingParent awaiting -> {
                inboundMessageService.markAwaitingParent(messageId, awaiting.parent(), mapper.recordOf(data).orElse(null));
                yield ImportOutcome.awaitingParent(awaiting.parent().waitingReason());
            }
        };
    }

    private ImportOutcome closingOlderWaits(
            InboundMessage message, ObjectNode data, String newerOutcome, ImportOutcome outcome) {

        mapper.recordOf(data)
                .ifPresent(record -> supersededWaits.closeOlderThan(message.receipt(), record, newerOutcome));
        return outcome;
    }
}
