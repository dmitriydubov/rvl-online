package ru.projectx.datareference.service.onec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static ru.projectx.datareference.support.OneCMessages.envelope;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundReceipt;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.service.inbound.InboundMessageService;
import ru.projectx.datareference.service.leadership.LeadershipFence;
import tools.jackson.databind.json.JsonMapper;

class MissingMapperTest {

    @Test
    void streamWithoutMapper_isSkippedWithReason() {
        InboundMessageService inboundMessageService = mock(InboundMessageService.class);
        ReferenceImportService importService = new ReferenceImportService(
                List.of(), List.of(), inboundMessageService, mock(SupersededParentWaits.class),
                mock(ParentArrivals.class),
                JsonMapper.builder().build(),
                mock(ApplicationEventPublisher.class), mock(LeadershipFence.class));

        UUID messageId = UUID.randomUUID();
        InboundMessage message = InboundMessage.receive(
                new InboundReceipt(messageId, OneCStream.MKB10, "очередь", Instant.now()),
                envelope("{\"id\":\"" + messageId + "\"}"));

        ImportOutcome result = importService.importMessage(message);

        assertThat(result).isInstanceOf(ImportOutcome.Skipped.class);
        assertThat(((ImportOutcome.Skipped) result).reason()).contains("маппера для потока MKB10 ещё нет");
        verify(inboundMessageService).markSkipped(messageId, ((ImportOutcome.Skipped) result).reason());
    }
}
