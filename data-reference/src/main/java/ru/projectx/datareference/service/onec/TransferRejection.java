package ru.projectx.datareference.service.onec;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.exception.OneCFieldException;
import ru.projectx.datareference.repository.inbound.InboundMessageRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

final class TransferRejection {

    private final InboundMessageRepository repository;
    private final TransferOwnChecks ownChecks;
    private final StoredEnvelope envelope;

    TransferRejection(InboundMessageRepository repository, ObjectMapper json, TransferOwnChecks ownChecks) {
        this.repository = repository;
        this.ownChecks = ownChecks;
        this.envelope = new StoredEnvelope(json);
    }

    List<InboundMessage> reject(InboundMessage message, String reason) {
        return partOf(message)
                .map(part -> rejectTransfer(message, part, reason))
                .orElseGet(() -> {
                    message.markFailed(reason);
                    return List.of(message);
                });
    }

    private List<InboundMessage> rejectTransfer(InboundMessage message, TransferPart part, String reason) {
        ownChecks.lockTransfer(part.cid(), part.data());
        List<InboundMessage> parts = repository.findByCorrelationId(part.cid());
        Reasons reasons = reasonsFor(message, part, parts, reason);
        part.markupOrTransferOnly().applyTo(message);
        message.markFailed(reasons.own());
        List<InboundMessage> others = bufferedOthers(message, parts);
        others.forEach(other -> other.markFailed(reasons.others()));
        return Stream.concat(Stream.of(message), others.stream()).toList();
    }

    private Reasons reasonsFor(InboundMessage message, TransferPart part, List<InboundMessage> parts, String reason) {
        UUID cid = part.cid();
        if (hasOwnDefect(message, part, parts)) {
            return new Reasons(reason, "передача %s отклонена: ошибка в сообщении %s: %s"
                    .formatted(cid, message.receipt().messageId(), reason));
        }
        String transferReason = "передача %s отклонена: %s".formatted(cid, reason);
        return new Reasons(transferReason, transferReason);
    }

    private static List<InboundMessage> bufferedOthers(InboundMessage message, List<InboundMessage> parts) {
        UUID messageId = message.receipt().messageId();
        return parts.stream()
                .filter(CorrelatedTransfer::isCollected)
                .filter(other -> !other.receipt().messageId().equals(messageId))
                .toList();
    }

    private boolean hasOwnDefect(InboundMessage message, TransferPart part, List<InboundMessage> parts) {
        return part.markup()
                .map(markup -> failsOwnChecks(message, part, markup, parts))
                .orElse(true);
    }

    private boolean failsOwnChecks(
            InboundMessage message, TransferPart part, TransferMarkup markup, List<InboundMessage> parts) {
        try {
            ownChecks.check(message, markup, part.data(), parts);
            return false;
        }
        catch (OneCFieldException defect) {
            return true;
        }
    }

    private Optional<TransferPart> partOf(InboundMessage message) {
        try {
            return envelope.dataOf(message)
                    .map(data -> new TransferPart(TransferMarkup.cidOf(data), data));
        }
        catch (OneCFieldException notTransferPart) {
            return Optional.empty();
        }
    }

    private record TransferPart(UUID cid, ObjectNode data) {

        Optional<TransferMarkup> markup() {
            try {
                return Optional.of(TransferMarkup.of(data));
            }
            catch (OneCFieldException unreadableCounter) {
                return Optional.empty();
            }
        }

        TransferMarkup markupOrTransferOnly() {
            return markup().orElseGet(() -> new TransferMarkup(cid, null, 0));
        }
    }

    private record Reasons(String own, String others) {
    }
}
