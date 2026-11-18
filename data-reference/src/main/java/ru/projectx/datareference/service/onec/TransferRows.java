package ru.projectx.datareference.service.onec;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.exception.OneCFieldException;
import ru.projectx.datareference.mapper.value.OneCRequiredValues;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

final class TransferRows {

    private static final String RECORD_SET = "recordSet";

    private final StoredEnvelope envelope;

    TransferRows(ObjectMapper json) {
        this.envelope = new StoredEnvelope(json);
    }

    ObjectNode dataOf(InboundMessage message, ParsedTransferParts parsed) {
        return parsed.dataOf(message).or(() -> envelope.dataOf(message)).orElseThrow(
                () -> new OneCFieldException("тело сообщения передачи стёрто, собрать её нечем"));
    }

    List<JsonNode> inOrder(CorrelatedTransfer.Assembled transfer, ParsedTransferParts parsed) {
        return transfer.batches().stream()
                .flatMap(batch -> rowsOf(dataOf(batch, parsed)))
                .sorted(Comparator.comparingInt(TransferRowNumber::of))
                .toList();
    }

    private static Stream<JsonNode> rowsOf(ObjectNode data) {
        return OneCRequiredValues.array(data, RECORD_SET).valueStream();
    }
}
