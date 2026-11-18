package ru.projectx.datareference.service.onec;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.exception.OneCFieldException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class TransferPartsInAdvance {

    private final TransferPartsReader reader;
    private final StoredEnvelope envelope;

    public TransferPartsInAdvance(TransferPartsReader reader, ObjectMapper json) {
        this.reader = reader;
        this.envelope = new StoredEnvelope(json);
    }

    ParsedTransferParts parse(InboundMessage received, TransferMarkup markup, ObjectNode data) {
        List<InboundMessage> parts = withReceivedBuffered(reader.parts(markup.cid()), received, markup);
        if (CorrelatedTransfer.of(markup.cid(), parts) instanceof CorrelatedTransfer.Assembled assembled) {
            return parsed(assembled, received.receipt().messageId(), data);
        }
        return ParsedTransferParts.NONE;
    }

    private static List<InboundMessage> withReceivedBuffered(
            List<InboundMessage> stored, InboundMessage received, TransferMarkup markup) {

        UUID receivedId = received.receipt().messageId();
        InboundMessage buffered = InboundMessage.receive(received.receipt(), received.payload());
        markup.applyTo(buffered);
        return Stream.concat(
                        stored.stream().filter(part -> !part.receipt().messageId().equals(receivedId)),
                        Stream.of(buffered))
                .toList();
    }

    private ParsedTransferParts parsed(CorrelatedTransfer.Assembled transfer, UUID receivedId, ObjectNode receivedData) {
        Map<UUID, ObjectNode> dataByMessage = new HashMap<>();
        for (InboundMessage part : partsOf(transfer)) {
            UUID messageId = part.receipt().messageId();
            Optional<ObjectNode> data = messageId.equals(receivedId) ? Optional.of(receivedData) : readableData(part);
            data.ifPresent(parsedData -> dataByMessage.put(messageId, parsedData));
        }
        return new ParsedTransferParts(dataByMessage);
    }

    private static List<InboundMessage> partsOf(CorrelatedTransfer.Assembled transfer) {
        return Stream.concat(Stream.of(transfer.finalMessage()), transfer.batches().stream()).toList();
    }

    private Optional<ObjectNode> readableData(InboundMessage part) {
        try {
            return envelope.dataOf(part);
        }
        catch (OneCFieldException unreadable) {
            return Optional.empty();
        }
    }
}
