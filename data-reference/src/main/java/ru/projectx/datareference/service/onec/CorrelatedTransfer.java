package ru.projectx.datareference.service.onec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ru.projectx.datareference.entity.inbound.AwaitedParent;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.mapper.ImportResult;

sealed interface CorrelatedTransfer {

    static CorrelatedTransfer of(UUID cid, List<InboundMessage> correlated) {
        List<InboundMessage> buffered = correlated.stream()
                .filter(CorrelatedTransfer::isCollected)
                .toList();
        if (isRejected(correlated)) {
            return new Rejected("передача %s уже отклонена".formatted(cid), buffered);
        }
        if (anyIn(correlated, InboundStatus.SKIPPED)) {
            return new Closed("передача %s уже пропущена".formatted(cid), buffered);
        }
        return assembling(cid, buffered);
    }

    static boolean isCollected(InboundMessage part) {
        InboundStatus status = part.outcome().status();
        return status == InboundStatus.BUFFERED || status == InboundStatus.AWAITING_PARENT;
    }

    static boolean isClosed(List<InboundMessage> correlated) {
        return isRejected(correlated) || anyIn(correlated, InboundStatus.SKIPPED);
    }

    static boolean isRejected(List<InboundMessage> correlated) {
        return anyIn(correlated, InboundStatus.FAILED) && !anyIn(correlated, InboundStatus.PROCESSED);
    }

    private static boolean anyIn(List<InboundMessage> messages, InboundStatus status) {
        return messages.stream().anyMatch(message -> message.outcome().status() == status);
    }

    private static CorrelatedTransfer assembling(UUID cid, List<InboundMessage> buffered) {
        List<InboundMessage> batches = buffered.stream()
                .filter(message -> message.transferPart().batchCount() == null)
                .toList();
        return buffered.stream()
                .filter(message -> message.transferPart().batchCount() != null)
                .findFirst()
                .map(finalMessage -> withFinalMessage(cid, finalMessage, batches))
                .orElseGet(() -> new Pending("передача %s ждёт финального сообщения".formatted(cid)));
    }

    private static CorrelatedTransfer withFinalMessage(
            UUID cid, InboundMessage finalMessage, List<InboundMessage> batches) {

        int expectedBatches = Objects.requireNonNull(finalMessage.transferPart().batchCount());
        if (batches.size() < expectedBatches) {
            return new Pending("передача %s собрана на %d из %d сообщений"
                    .formatted(cid, batches.size(), expectedBatches));
        }
        return checkedCounters(cid, finalMessage, batches, expectedBatches);
    }

    private static CorrelatedTransfer checkedCounters(
            UUID cid, InboundMessage finalMessage, List<InboundMessage> batches, int expectedBatches) {

        int expectedRows = Objects.requireNonNull(finalMessage.transferPart().rowCount());
        int actualRows = batches.stream()
                .mapToInt(batch -> Objects.requireNonNullElse(batch.transferPart().rowCount(), 0))
                .sum();
        if (batches.size() == expectedBatches && actualRows == expectedRows) {
            return new Assembled(finalMessage, batches);
        }
        String reason = "счётчики передачи %s не сошлись: ожидалось %d сообщений / %d строк, получено %d / %d"
                .formatted(cid, expectedBatches, expectedRows, batches.size(), actualRows);
        return new Mismatched(reason, finalMessage, batches);
    }

    private static List<InboundMessage> wholeTransfer(InboundMessage finalMessage, List<InboundMessage> batches) {
        List<InboundMessage> messages = new ArrayList<>();
        messages.add(finalMessage);
        messages.addAll(batches);
        return List.copyOf(messages);
    }

    private static List<InboundMessage> failAll(List<InboundMessage> messages, String reason) {
        for (InboundMessage message : messages) {
            message.markFailed(reason);
        }
        return messages;
    }

    record Pending(String reason) implements CorrelatedTransfer {
    }

    record Rejected(String reason, List<InboundMessage> lateParts) implements CorrelatedTransfer {

        List<InboundMessage> fail() {
            return failAll(lateParts, reason);
        }
    }

    record Closed(String reason, List<InboundMessage> lateParts) implements CorrelatedTransfer {

        List<InboundMessage> skip(Instant processedAt) {
            for (InboundMessage message : lateParts) {
                message.markSkipped(reason, processedAt);
            }
            return lateParts;
        }
    }

    record Mismatched(String reason, InboundMessage finalMessage, List<InboundMessage> batches)
            implements CorrelatedTransfer {

        List<InboundMessage> fail() {
            return failAll(wholeTransfer(finalMessage, batches), reason);
        }
    }

    record Assembled(InboundMessage finalMessage, List<InboundMessage> batches) implements CorrelatedTransfer {

        List<InboundMessage> markOutcome(ImportResult result, Instant at) {
            return switch (result) {
                case ImportResult.Applied ignored -> markProcessed(at);
                case ImportResult.Skipped skipped -> markSkipped(skipped.reason(), at);
                case ImportResult.AwaitingParent awaiting -> markAwaitingParent(awaiting.parent(), at);
            };
        }

        List<InboundMessage> markAwaitingParent(AwaitedParent service, Instant at) {
            List<InboundMessage> messages = wholeTransfer(finalMessage, batches);
            for (InboundMessage message : messages) {
                message.markAwaitingParent(service, null, at);
            }
            return messages;
        }

        List<InboundMessage> markProcessed(Instant processedAt) {
            List<InboundMessage> messages = wholeTransfer(finalMessage, batches);
            for (InboundMessage message : messages) {
                message.markProcessed(processedAt);
            }
            return messages;
        }

        List<InboundMessage> markSkipped(String reason, Instant processedAt) {
            List<InboundMessage> messages = wholeTransfer(finalMessage, batches);
            for (InboundMessage message : messages) {
                message.markSkipped(reason, processedAt);
            }
            return messages;
        }
    }
}
