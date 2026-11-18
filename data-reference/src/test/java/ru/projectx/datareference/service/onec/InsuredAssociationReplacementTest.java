package ru.projectx.datareference.service.onec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ru.projectx.datareference.support.InsuredAssociationData.OTHER_RELATION_TYPE;
import static ru.projectx.datareference.support.InsuredAssociationData.INSURED;
import static ru.projectx.datareference.support.InsuredAssociationData.OTHER_RELATED;
import static ru.projectx.datareference.support.InsuredAssociationData.RELATED;
import static ru.projectx.datareference.support.InsuredAssociationData.RELATION_TYPE;
import static ru.projectx.datareference.support.InsuredAssociationData.batch;
import static ru.projectx.datareference.support.InsuredAssociationData.byInsured;
import static ru.projectx.datareference.support.InsuredAssociationData.finalPart;
import static ru.projectx.datareference.support.InsuredAssociationData.row;
import static ru.projectx.datareference.support.OneCMessages.envelope;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundReceipt;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.entity.policy.InsuredAssociationFilter;
import ru.projectx.datareference.exception.OneCFieldException;
import ru.projectx.datareference.mapper.ImportResult;
import ru.projectx.datareference.mapper.stream.InsuredAssociationMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class InsuredAssociationReplacementTest {

    private static final UUID CID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee01");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InsuredAssociationMapper mapper = mock(InsuredAssociationMapper.class);
    private final InsuredAssociationOrder order = mock(InsuredAssociationOrder.class);

    @BeforeEach
    void readFiltersByTheirRules() {
        when(mapper.filterOf(any())).thenCallRealMethod();
    }

    @Test
    void transferIsAppliedInOrderOfItsFinalMessage_withRowsOfAllPartsByRowNumber() {
        String second = row(2, INSURED, OTHER_RELATED, OTHER_RELATION_TYPE, "2026-11-13", "3000-01-01");
        String first = row(1, INSURED, RELATED, RELATION_TYPE, "2026-11-13", "3000-01-01");
        CorrelatedTransfer.Assembled transfer = new CorrelatedTransfer.Assembled(
                part(finalPart(CID, byInsured(INSURED), 2, 2)),
                List.of(part(batch(CID, byInsured(INSURED), second)), part(batch(CID, byInsured(INSURED), first))));
        InsuredAssociationFilter filter = new InsuredAssociationFilter(null, INSURED, null, null);
        ArgumentCaptor<Supplier<ImportResult>> replacement = ArgumentCaptor.captor();
        when(order.applyInOrder(eq(filter), eq(transfer.finalMessage()), replacement.capture()))
                .thenReturn(ImportResult.applied());
        when(mapper.apply(any(), any())).thenReturn(ImportResult.applied());

        ImportResult result = replacement().replace(transfer, ParsedTransferParts.NONE);

        assertThat(result).isEqualTo(ImportResult.applied());
        assertThat(replacement.getValue().get()).isEqualTo(ImportResult.applied());
        verify(mapper).apply(filter, List.of(JSON.readTree(first), JSON.readTree(second)));
    }

    @Test
    void partsNamingDifferentFilters_rejectTheTransfer() {
        CorrelatedTransfer.Assembled transfer = new CorrelatedTransfer.Assembled(
                part(finalPart(CID, byInsured(INSURED), 1, 1)),
                List.of(part(batch(CID, byInsured(RELATED), row(1, RELATED, INSURED, RELATION_TYPE, "2026-11-13",
                        "3000-01-01")))));

        assertThatThrownBy(() -> replacement().replace(transfer, ParsedTransferParts.NONE))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("части передачи " + CID + " называют разные отборы");
        verify(order, never()).applyInOrder(any(), any(), any());
    }

    private InsuredAssociationReplacement replacement() {
        return new InsuredAssociationReplacement(mapper, order, JSON);
    }

    private static InboundMessage part(String data) {
        JsonNode parsed = JSON.readTree(data);
        InboundMessage message = InboundMessage.receive(new InboundReceipt(UUID.randomUUID(),
                OneCStream.INSURED_ASSOCIATIONS, "очередь", Instant.parse("2026-11-15T09:00:00Z")), envelope(data));
        message.markBuffered(CID, parsed.has("countMessages") ? parsed.get("countMessages").asInt() : null,
                parsed.path("recordSet").size());
        return message;
    }
}
