package ru.projectx.datareference.service.onec;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.repository.inbound.InboundMessageRepository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ParentArrivalsTest {

    private static final UUID POLICY = UUID.fromString("11111111-2222-4333-8444-555555555506");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InboundMessageRepository inbound = mock(InboundMessageRepository.class);
    private final ParentArrivals arrivals = new ParentArrivals(inbound);

    @Test
    void appliedPolicy_releasesMessagesWaitingForIt() {
        arrivals.arrived(OneCStream.POLICIES, data("{\"id\":\"%s\"}".formatted(POLICY)));

        verify(inbound).releaseAwaitingParent("POLICY", POLICY);
    }

    @Test
    void streamThatIsNoAwaitedParent_releasesNothing() {
        arrivals.arrived(OneCStream.MKB10, data("{\"id\":\"%s\"}".formatted(POLICY)));

        verifyNoInteractions(inbound);
    }

    @Test
    void policyWithoutReadableGuid_releasesNothing() {
        arrivals.arrived(OneCStream.POLICIES, data("{\"number\":\"СТЕНД-0001\"}"));

        verify(inbound, never()).releaseAwaitingParent(any(), any());
    }

    private static ObjectNode data(String json) {
        return (ObjectNode) JSON.readTree(json);
    }
}
