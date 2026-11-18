package ru.projectx.datareference.service.onec;

import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.entity.inbound.ParentKind;
import ru.projectx.datareference.mapper.value.OneCValues;
import ru.projectx.datareference.repository.inbound.InboundMessageRepository;
import tools.jackson.databind.node.ObjectNode;

@Component
class ParentArrivals {

    private static final Map<OneCStream, Arrival> ARRIVALS = Map.of(
            OneCStream.POLICIES, new Arrival(ParentKind.POLICY, "id"));

    private final InboundMessageRepository inbound;

    ParentArrivals(InboundMessageRepository inbound) {
        this.inbound = inbound;
    }

    void arrived(OneCStream stream, ObjectNode data) {
        Optional.ofNullable(ARRIVALS.get(stream))
                .ifPresent(arrival -> OneCValues.uuid(data, arrival.idField())
                        .ifPresent(parent -> inbound.releaseAwaitingParent(arrival.kind().name(), parent)));
    }

    private record Arrival(ParentKind kind, String idField) {
    }
}
