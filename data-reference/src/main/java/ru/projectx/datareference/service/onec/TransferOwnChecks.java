package ru.projectx.datareference.service.onec;

import java.util.List;
import java.util.UUID;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import tools.jackson.databind.node.ObjectNode;

interface TransferOwnChecks {

    default void lockTransfer(UUID cid, ObjectNode part) {
    }

    void check(InboundMessage message, TransferMarkup markup, ObjectNode part, List<InboundMessage> parts);
}
