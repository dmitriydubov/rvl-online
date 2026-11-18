package ru.projectx.datareference.service.onec;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.service.inbound.InboundMessageService;
import tools.jackson.databind.node.ObjectNode;

@Component
public class TransferAssembly {

    private final InboundMessageService inboundMessageService;
    private final TransferPartsInAdvance partsInAdvance;
    private final TransferConclusion conclusion;

    public TransferAssembly(InboundMessageService inboundMessageService, TransferPartsInAdvance partsInAdvance,
            TransferConclusion conclusion) {

        this.inboundMessageService = inboundMessageService;
        this.partsInAdvance = partsInAdvance;
        this.conclusion = conclusion;
    }

    ImportOutcome assemble(InboundMessage message, ObjectNode data, TransferRules rules) {
        TransferMarkup markup = TransferMarkup.of(data);
        UUID cid = markup.cid();
        ParsedTransferParts parsed = partsInAdvance.parse(message, markup, data);
        rules.preparation().accept(cid, data);
        markup.applyTo(message);
        inboundMessageService.saveTransferPart(message);
        List<InboundMessage> parts = inboundMessageService.transferParts(cid);
        rules.partCheck().check(message.receipt().messageId(), markup, data, parts);
        return conclusion.conclude(CorrelatedTransfer.of(cid, parts), parsed, rules.replacement());
    }
}
