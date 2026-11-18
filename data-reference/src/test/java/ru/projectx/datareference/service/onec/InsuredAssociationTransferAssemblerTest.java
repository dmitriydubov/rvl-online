package ru.projectx.datareference.service.onec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.projectx.datareference.config.InboundRetryProperties;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundReceipt;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.exception.OneCFieldException;
import ru.projectx.datareference.mapper.ImportResult;
import ru.projectx.datareference.mapper.stream.InsuredAssociationMapper;
import ru.projectx.datareference.repository.database.DatabaseClockRepository;
import ru.projectx.datareference.repository.inbound.InboundMessageRepository;
import ru.projectx.datareference.service.inbound.FinalRejectionJournal;
import ru.projectx.datareference.service.inbound.InboundMessageService;
import ru.projectx.datareference.service.metrics.InboundTransitionMetrics;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class InsuredAssociationTransferAssemblerTest {

    private static final UUID CID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee01");
    private static final Instant NOW = Instant.parse("2026-11-15T09:00:00Z");
    private static final String ROW = row(1, INSURED, RELATED, RELATION_TYPE, "2026-11-13", "3000-01-01");
    private static final String ABSENT = "absent";

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<InboundMessage> correlated = new ArrayList<>();

    @Mock
    private InboundMessageRepository repository;

    @Mock
    private InsuredAssociationMapper mapper;

    @Mock
    private InsuredAssociationReplacement replacement;

    @Mock
    private TransferPartsReader partsReader;

    @BeforeEach
    void readPartsByTheirRules() {
        lenient().when(mapper.filterOf(any())).thenCallRealMethod();
        lenient().doCallRealMethod().when(mapper).checkRow(any());
    }

    @Test
    void handlesTheInsuredAssociationStream() {
        assertThat(assembler().stream()).isEqualTo(OneCStream.INSURED_ASSOCIATIONS);
    }

    @Test
    void partWithoutActiveFilter_isRejectedBeforeItIsBuffered() {
        InboundMessage part = message(batch(CID, filter(element("idInsured", INSURED.toString(), false)), ROW));

        assertThatThrownBy(() -> receive(part))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("в отборе передачи нет ни одного действующего элемента");
        verify(repository, never()).save(any());
    }

    @Test
    void partWithBrokenRow_isRejected() {
        InboundMessage part = message(batch(CID, byInsured(INSURED), ROW.replace(RELATION_TYPE.toString(), "1-2-3-4-5")));

        assertThatThrownBy(() -> receive(part))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле relationType не является GUID");
    }

    @Test
    void partOfAnUnfinishedTransfer_waitsForTheRest() {
        InboundMessage part = message(batch(CID, byInsured(INSURED), ROW));

        ImportOutcome outcome = receive(part);

        assertThat(outcome).isEqualTo(ImportOutcome.skipped("передача " + CID + " ждёт финального сообщения"));
        assertThat(part.outcome().status()).isEqualTo(InboundStatus.BUFFERED);
        verify(replacement, never()).replace(any(), any());
    }

    @Test
    void assembledTransfer_isAppliedByTheReplacement_andEveryPartIsProcessed() {
        InboundMessage part = message(batch(CID, byInsured(INSURED), ROW));
        part.markBuffered(CID, null, 1);
        correlated.add(part);
        InboundMessage finalMessage = message(finalPart(CID, byInsured(INSURED), 1, 1));
        when(replacement.replace(any(), any())).thenReturn(ImportResult.applied());

        ImportOutcome outcome = receive(finalMessage);

        assertThat(outcome).isEqualTo(ImportOutcome.applied());
        verify(replacement).replace(any(CorrelatedTransfer.Assembled.class), any());
        assertThat(List.of(part, finalMessage)).extracting(message -> message.outcome().status())
                .containsOnly(InboundStatus.PROCESSED);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            absent | нет обязательного поля recordSet
            null   | нет обязательного поля recordSet
            {}     | поле recordSet не является массивом
            "[]"   | поле recordSet не является массивом
            """)
    void partWithoutRowsArray_afterFinalOfOneEmptyPart_isRejected_andAssociationsAreNotReplaced(
            String recordSet, String reason) {

        bufferedFinalOfOneEmptyPart();
        InboundMessage part = message(withRecordSet(recordSet));

        assertThatThrownBy(() -> receive(part))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage(reason);
        verify(replacement, never()).replace(any(), any());
    }

    @Test
    void partWithoutRowsArray_ofRejectedTransfer_isRejectedAsLatePart() {
        bufferedFinalOfOneEmptyPart().markFailed("счётчики передачи не сошлись");

        ImportOutcome outcome = receive(message(withRecordSet(ABSENT)));

        assertThat(outcome).isEqualTo(ImportOutcome.rejected("передача " + CID + " уже отклонена"));
    }

    @Test
    void partWithoutRowsArray_ofSkippedTransfer_isSkippedAsLatePart() {
        bufferedFinalOfOneEmptyPart().markSkipped("по отбору передачи уже применена более новая передача", NOW);

        ImportOutcome outcome = receive(message(withRecordSet(ABSENT)));

        assertThat(outcome).isEqualTo(ImportOutcome.skipped("передача " + CID + " уже пропущена"));
    }

    @Test
    void partWithoutRowsArray_ofAppliedTransfer_isRejectedWithItsOwnDefect() {
        bufferedFinalOfOneEmptyPart().markProcessed(NOW);

        assertThatThrownBy(() -> receive(message(withRecordSet(ABSENT))))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("нет обязательного поля recordSet");
        verify(replacement, never()).replace(any(), any());
    }

    private InboundMessage bufferedFinalOfOneEmptyPart() {
        InboundMessage finalMessage = message(finalPart(CID, byInsured(INSURED), 1, 0));
        finalMessage.markBuffered(CID, 1, 0);
        correlated.add(finalMessage);
        return finalMessage;
    }

    private String withRecordSet(String recordSet) {
        ObjectNode data = (ObjectNode) json.readTree(batch(CID, byInsured(INSURED)));
        data.remove("recordSet");
        if (!ABSENT.equals(recordSet)) {
            data.set("recordSet", json.readTree(recordSet));
        }
        return data.toString();
    }

    private ImportOutcome receive(InboundMessage message) {
        correlated.add(message);
        lenient().when(repository.findByCorrelationId(CID)).thenReturn(correlated);
        lenient().when(partsReader.parts(CID)).thenReturn(correlated);
        return assembler().handle(message, (ObjectNode) json.readTree(message.payload()).get("data"));
    }

    private InboundMessage message(String data) {
        return InboundMessage.receive(
                new InboundReceipt(UUID.randomUUID(), OneCStream.INSURED_ASSOCIATIONS, "очередь", NOW), envelope(data));
    }

    private InsuredAssociationTransferAssembler assembler() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        InboundMessageService inboundMessageService = new InboundMessageService(
                repository, new InboundRetryProperties(List.of(Duration.ofSeconds(30))), clock,
                mock(DatabaseClockRepository.class), new InboundTransitionMetrics(new SimpleMeterRegistry()),
                new FinalRejectionJournal(), List.of());
        TransferAssembly assembly = new TransferAssembly(inboundMessageService,
                new TransferPartsInAdvance(partsReader, json), new TransferConclusion(inboundMessageService, clock));
        return new InsuredAssociationTransferAssembler(assembly, mapper, replacement);
    }
}
