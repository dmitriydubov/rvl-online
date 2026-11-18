package ru.projectx.datareference.service.onec;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundReceipt;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.exception.OneCFieldException;
import ru.projectx.datareference.mapper.StreamMapper;
import ru.projectx.datareference.service.inbound.InboundMessageService;
import ru.projectx.datareference.service.inbound.OneCMessageApplied;
import ru.projectx.datareference.service.leadership.LeadershipFence;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ReferenceImportService {

    private final Map<OneCStream, StreamHandler> handlers;
    private final InboundMessageService inboundMessageService;
    private final StoredEnvelope envelope;
    private final ApplicationEventPublisher events;
    private final LeadershipFence leadership;

    public ReferenceImportService(
            List<StreamMapper> mappers,
            List<StreamHandler> dedicatedHandlers,
            InboundMessageService inboundMessageService,
            SupersededParentWaits supersededWaits,
            ParentArrivals parentArrivals,
            ObjectMapper json,
            ApplicationEventPublisher events,
            LeadershipFence leadership) {

        this.handlers = Stream.concat(
                        mappers.stream().map(mapper ->
                                new MappedStreamHandler(mapper, inboundMessageService, supersededWaits, parentArrivals)),
                        dedicatedHandlers.stream())
                .collect(Collectors.toUnmodifiableMap(StreamHandler::stream, Function.identity()));
        this.inboundMessageService = inboundMessageService;
        this.envelope = new StoredEnvelope(json);
        this.events = events;
        this.leadership = leadership;
    }

    @Transactional
    public ImportOutcome importMessage(InboundMessage message) {
        leadership.holdUntilCommit();
        ObjectNode data = dataOf(message);
        OneCStream stream = message.receipt().stream();
        ImportOutcome result = handlerFor(stream)
                .map(handler -> handler.handle(message, data))
                .orElseGet(() -> skipWithoutHandler(message));
        if (result.mayChangeCatalog()) {
            events.publishEvent(new OneCMessageApplied(stream));
        }
        return result;
    }

    private Optional<StreamHandler> handlerFor(OneCStream stream) {
        return Optional.ofNullable(handlers.get(stream));
    }

    private ImportOutcome skipWithoutHandler(InboundMessage message) {
        InboundReceipt receipt = message.receipt();
        String reason = "маппера для потока " + receipt.stream() + " ещё нет";
        inboundMessageService.markSkipped(receipt.messageId(), reason);
        return ImportOutcome.skipped(reason);
    }

    private ObjectNode dataOf(InboundMessage message) {
        return envelope.dataOf(message).orElseThrow(
                () -> new OneCFieldException("тело сообщения стёрто по сроку хранения, разбирать нечего"));
    }
}
