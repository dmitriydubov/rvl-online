package ru.projectx.datareference.service.onec;

import java.util.UUID;
import ru.projectx.datareference.mapper.value.OneCValues;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

final class PriceListTransferRows {

    private static final String KEY_VALUE = "keyValue";
    private static final String RECORD_SET = "recordSet";

    private final ObjectMapper json;
    private final TransferRows rows;

    PriceListTransferRows(ObjectMapper json) {
        this.json = json;
        this.rows = new TransferRows(json);
    }

    ObjectNode of(CorrelatedTransfer.Assembled transfer, ParsedTransferParts parsed) {
        ObjectNode assembled = json.createObjectNode();
        assembled.set(KEY_VALUE, rows.dataOf(transfer.finalMessage(), parsed).path(KEY_VALUE));
        assembled.putArray(RECORD_SET).addAll(rows.inOrder(transfer, parsed));
        return assembled;
    }

    static UUID serviceOf(ObjectNode assembled) {
        return OneCValues.requiredUuid(assembled, KEY_VALUE);
    }
}
