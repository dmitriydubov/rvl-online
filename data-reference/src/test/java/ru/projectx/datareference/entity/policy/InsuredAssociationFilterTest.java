package ru.projectx.datareference.entity.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class InsuredAssociationFilterTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 13);
    private static final UUID INSURED = UUID.fromString("11111111-2222-4333-8444-555555555501");
    private static final UUID RELATED = UUID.fromString("11111111-2222-4333-8444-555555555502");
    private static final UUID TYPE = UUID.fromString("797093be-2c51-11e8-8123-005056b43f21");
    private static final UUID OTHER = UUID.fromString("11111111-2222-4333-8444-555555555599");
    private static final InsuredAssociation.Row ROW = new InsuredAssociation.Row(INSURED, RELATED, TYPE, START, null);

    @Test
    void filterWithoutConstraints_isEmpty() {
        assertThat(new InsuredAssociationFilter(null, null, null, null).isEmpty()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("singleConstraints")
    void filterWithAnyConstraint_isNotEmpty(InsuredAssociationFilter filter) {
        assertThat(filter.isEmpty()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("singleConstraints")
    void filterConstrainingAnyDimensionWithTheRowValue_coversTheRow(InsuredAssociationFilter filter) {
        assertThat(filter.covers(ROW)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("mismatchedConstraints")
    void filterConstrainingAnyDimensionWithAnotherValue_doesNotCoverTheRow(InsuredAssociationFilter filter) {
        assertThat(filter.covers(ROW)).isFalse();
    }

    @Test
    void fullFilterOfTheRow_coversIt_andEqualsItsDimensions() {
        InsuredAssociationFilter full = new InsuredAssociationFilter(START, INSURED, RELATED, TYPE);

        assertThat(full.covers(ROW)).isTrue();
        assertThat(ROW.dimensions()).isEqualTo(full);
    }

    static Stream<Arguments> singleConstraints() {
        return Stream.of(
                Arguments.of(new InsuredAssociationFilter(START, null, null, null)),
                Arguments.of(new InsuredAssociationFilter(null, INSURED, null, null)),
                Arguments.of(new InsuredAssociationFilter(null, null, RELATED, null)),
                Arguments.of(new InsuredAssociationFilter(null, null, null, TYPE)));
    }

    static Stream<Arguments> mismatchedConstraints() {
        return Stream.of(
                Arguments.of(new InsuredAssociationFilter(START.plusDays(1), INSURED, RELATED, TYPE)),
                Arguments.of(new InsuredAssociationFilter(START, OTHER, RELATED, TYPE)),
                Arguments.of(new InsuredAssociationFilter(START, INSURED, OTHER, TYPE)),
                Arguments.of(new InsuredAssociationFilter(START, INSURED, RELATED, OTHER)));
    }
}
