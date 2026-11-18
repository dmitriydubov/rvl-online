package ru.projectx.datareference.entity.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.projectx.datareference.entity.policy.InsuredAssociationFilter;

class InsuredAssociationFinalizationTest {

    private static final InboundMessage FINAL_MESSAGE = finalMessage("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee12");
    private static final InboundMessage OTHER_FINAL_MESSAGE = finalMessage("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee23");
    private static final LocalDate START = LocalDate.of(2026, 11, 13);
    private static final UUID INSURED = UUID.fromString("11111111-2222-4333-8444-555555555501");
    private static final UUID RELATED = UUID.fromString("11111111-2222-4333-8444-555555555502");
    private static final UUID TYPE = UUID.fromString("797093be-2c51-11e8-8123-005056b43f21");
    private static final UUID OTHER = UUID.fromString("11111111-2222-4333-8444-555555555599");
    private static final InsuredAssociationFilter FILTER = new InsuredAssociationFilter(START, INSURED, RELATED, TYPE);

    @Test
    void finalizationsOfTheSameFilterByAnotherFinalMessage_differ() {
        assertThat(InsuredAssociationFinalization.of(FINAL_MESSAGE, FILTER)).usingRecursiveComparison()
                .isNotEqualTo(InsuredAssociationFinalization.of(OTHER_FINAL_MESSAGE, FILTER));
    }

    @ParameterizedTest
    @MethodSource("filtersDifferingInOneDimension")
    void finalizationsByTheSameFinalMessageOfFiltersDifferingInAnyDimension_differ(InsuredAssociationFilter other) {
        assertThat(InsuredAssociationFinalization.of(FINAL_MESSAGE, FILTER)).usingRecursiveComparison()
                .isNotEqualTo(InsuredAssociationFinalization.of(FINAL_MESSAGE, other));
    }

    @Test
    void finalizationsOfTheSameFinalMessageAndFilter_areAlike() {
        assertThat(InsuredAssociationFinalization.of(FINAL_MESSAGE, FILTER)).usingRecursiveComparison()
                .isEqualTo(InsuredAssociationFinalization.of(FINAL_MESSAGE,
                        new InsuredAssociationFilter(START, INSURED, RELATED, TYPE)));
    }

    @Test
    void finalization_keepsItsFinalMessage() {
        assertThat(InsuredAssociationFinalization.of(FINAL_MESSAGE, FILTER).finalMessage()).isSameAs(FINAL_MESSAGE);
    }

    static Stream<InsuredAssociationFilter> filtersDifferingInOneDimension() {
        return Stream.of(
                new InsuredAssociationFilter(START.plusDays(1), INSURED, RELATED, TYPE),
                new InsuredAssociationFilter(null, INSURED, RELATED, TYPE),
                new InsuredAssociationFilter(START, OTHER, RELATED, TYPE),
                new InsuredAssociationFilter(START, INSURED, OTHER, TYPE),
                new InsuredAssociationFilter(START, INSURED, RELATED, OTHER));
    }

    private static InboundMessage finalMessage(String messageId) {
        return InboundMessage.receive(new InboundReceipt(UUID.fromString(messageId), OneCStream.INSURED_ASSOCIATIONS,
                "очередь", Instant.parse("2026-11-15T09:00:00Z")), "{}");
    }
}
