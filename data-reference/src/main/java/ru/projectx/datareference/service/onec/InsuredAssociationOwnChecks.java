package ru.projectx.datareference.service.onec;

import java.util.List;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.mapper.stream.InsuredAssociationMapper;
import tools.jackson.databind.node.ObjectNode;

final class InsuredAssociationOwnChecks implements TransferOwnChecks {

    private final InsuredAssociationMapper mapper;
    private final TransferPartCheck partCheck;

    InsuredAssociationOwnChecks(InsuredAssociationMapper mapper, TransferPartCheck partCheck) {
        this.mapper = mapper;
        this.partCheck = partCheck;
    }

    @Override
    public void check(InboundMessage message, TransferMarkup markup, ObjectNode part, List<InboundMessage> parts) {
        mapper.filterOf(part);
        partCheck.check(message.receipt().messageId(), markup, part, parts);
    }
}
