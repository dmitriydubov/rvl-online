package ru.projectx.datareference.service.onec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static ru.projectx.datareference.support.InsuredAssociationData.INSURED;
import static ru.projectx.datareference.support.InsuredAssociationData.RELATED;
import static ru.projectx.datareference.support.InsuredAssociationData.RELATION_TYPE;
import static ru.projectx.datareference.support.InsuredAssociationData.batch;
import static ru.projectx.datareference.support.InsuredAssociationData.byInsured;
import static ru.projectx.datareference.support.InsuredAssociationData.element;
import static ru.projectx.datareference.support.InsuredAssociationData.filter;
import static ru.projectx.datareference.support.InsuredAssociationData.finalPart;
import static ru.projectx.datareference.support.InsuredAssociationData.row;
import static ru.projectx.datareference.support.OneCMessages.envelope;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundReceipt;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.mapper.stream.InsuredAssociationMapper;
import ru.projectx.datareference.repository.inbound.InboundMessageRepository;
import ru.projectx.datareference.repository.policy.InsuredAssociationRepository;
import ru.projectx.datareference.repository.policy.PolicyRepository;
import tools.jackson.databind.json.JsonMapper;

class InsuredAssociationTransferRejectionTest {

    private static final UUID CID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee01");
    private static final String ROW = row(1, INSURED, RELATED, RELATION_TYPE, "2026-11-13", "3000-01-01");

    private final InboundMessageRepository repository = mock(InboundMessageRepository.class);
    private final InsuredAssociationTransferRejection rejection = new InsuredAssociationTransferRejection(repository,
            new InsuredAssociationMapper(mock(InsuredAssociationRepository.class), mock(PolicyRepository.class),
                    Clock.systemUTC()),
            JsonMapper.builder().build());

    @Test
    void rejectsPartsOfTheInsuredAssociationStream() {
        assertThat(rejection.stream()).isEqualTo(OneCStream.INSURED_ASSOCIATIONS);
    }

    @Test
    void partWithBrokenRow_keepsItsOwnReason_andTheBufferedPartsPointToIt() {
        InboundMessage finalMessage = buffered(finalPart(CID, byInsured(INSURED), 1, 1), 1);
        InboundMessage broken = received(batch(CID, byInsured(INSURED), ROW.replace(RELATION_TYPE.toString(), "1-2-3-4-5")));
        when(repository.findByCorrelationId(CID)).thenReturn(List.of(finalMessage));

        List<InboundMessage> rejected = rejection.reject(broken, "поле relationType не является GUID");

        assertThat(rejected).containsExactly(broken, finalMessage);
        assertThat(broken.outcome().lastError()).isEqualTo("поле relationType не является GUID");
        assertThat(finalMessage.outcome().lastError()).isEqualTo("передача " + CID + " отклонена: ошибка в сообщении "
                + broken.receipt().messageId() + ": поле relationType не является GUID");
        assertThat(rejected).extracting(message -> message.outcome().status()).containsOnly(InboundStatus.FAILED);
    }

    @Test
    void partWithoutActiveFilter_isItselfTheDefect() {
        InboundMessage finalMessage = buffered(finalPart(CID, byInsured(INSURED), 1, 1), 1);
        InboundMessage broken = received(batch(CID, filter(element("idInsured", INSURED.toString(), false)), ROW));
        when(repository.findByCorrelationId(CID)).thenReturn(List.of(finalMessage));
        String reason = "в отборе передачи нет ни одного действующего элемента";

        rejection.reject(broken, reason);

        assertThat(broken.outcome().lastError()).isEqualTo(reason);
        assertThat(finalMessage.outcome().lastError()).endsWith(broken.receipt().messageId() + ": " + reason);
    }

    @Test
    void soundPart_rejectedForTheTransfer_givesEveryPartTheTransferReason() {
        InboundMessage finalMessage = buffered(finalPart(CID, byInsured(INSURED), 1, 1), 1);
        InboundMessage last = received(batch(CID, byInsured(INSURED), ROW));
        when(repository.findByCorrelationId(CID)).thenReturn(List.of(finalMessage));
        String reason = "части передачи " + CID + " называют разные отборы";

        rejection.reject(last, reason);

        assertThat(List.of(last, finalMessage)).extracting(message -> message.outcome().lastError())
                .containsOnly("передача " + CID + " отклонена: " + reason);
    }

    private static InboundMessage buffered(String data, Integer batchCount) {
        InboundMessage message = received(data);
        message.markBuffered(CID, batchCount, 0);
        return message;
    }

    private static InboundMessage received(String data) {
        return InboundMessage.receive(new InboundReceipt(UUID.randomUUID(), OneCStream.INSURED_ASSOCIATIONS, "очередь",
                Instant.parse("2026-11-15T09:00:00Z")), envelope(data));
    }
}
