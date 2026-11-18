package ru.projectx.datareference.service.onec;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.mapper.value.OneCValues;
import tools.jackson.databind.node.ObjectNode;

record TransferMarkup(UUID cid, @Nullable Integer batchCount, int rowCount) {

    private static final String CID = "cid";
    private static final String COUNT_MESSAGES = "countMessages";
    private static final String COUNT_ROWS = "countRows";
    private static final String RECORD_SET = "recordSet";

    static TransferMarkup of(ObjectNode data) {
        UUID cid = cidOf(data);
        if (data.has(COUNT_MESSAGES)) {
            return new TransferMarkup(cid, counter(data, COUNT_MESSAGES), counter(data, COUNT_ROWS));
        }
        return new TransferMarkup(cid, null, data.path(RECORD_SET).size());
    }

    static UUID cidOf(ObjectNode data) {
        return OneCValues.requiredUuid(data, CID);
    }

    private static int counter(ObjectNode data, String field) {
        return OneCValues.integer(data, field).orElseGet(() -> data.path(field).asInt());
    }

    void applyTo(InboundMessage message) {
        message.markBuffered(cid, batchCount, rowCount);
    }
}
