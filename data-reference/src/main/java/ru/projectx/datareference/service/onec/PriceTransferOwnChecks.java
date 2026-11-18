package ru.projectx.datareference.service.onec;

import java.util.List;
import java.util.UUID;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import tools.jackson.databind.node.ObjectNode;

final class PriceTransferOwnChecks implements TransferOwnChecks {

    private final PriceTransferServiceLock serviceLock;
    private final PriceTransferServiceCheck serviceCheck;
    private final TransferPartCheck partCheck;

    PriceTransferOwnChecks(
            PriceTransferServiceLock serviceLock, PriceTransferServiceCheck serviceCheck, TransferPartCheck partCheck) {

        this.serviceLock = serviceLock;
        this.serviceCheck = serviceCheck;
        this.partCheck = partCheck;
    }

    @Override
    public void lockTransfer(UUID cid, ObjectNode part) {
        serviceLock.lockTransferService(cid, part);
    }

    @Override
    public void check(InboundMessage message, TransferMarkup markup, ObjectNode part, List<InboundMessage> parts) {
        serviceCheck.serviceOf(markup.cid(), part);
        partCheck.check(message.receipt().messageId(), markup, part, parts);
    }
}
