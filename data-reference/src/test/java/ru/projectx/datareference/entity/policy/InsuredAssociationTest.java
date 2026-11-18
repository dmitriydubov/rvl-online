package ru.projectx.datareference.entity.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InsuredAssociationTest {

    private static final UUID INSURED = UUID.fromString("11111111-2222-4333-8444-555555555501");
    private static final UUID RELATED = UUID.fromString("11111111-2222-4333-8444-555555555502");
    private static final UUID TYPE = UUID.fromString("797093be-2c51-11e8-8123-005056b43f21");

    @Test
    void association_keepsEveryValueOfItsRow() {
        InsuredAssociation.Row row = new InsuredAssociation.Row(INSURED, RELATED, TYPE, LocalDate.of(2026, 11, 13),
                LocalDate.of(2027, 3, 31));

        assertThat(InsuredAssociation.of(row, Instant.parse("2026-11-15T10:00:00Z")).row()).isEqualTo(row);
    }

    @Test
    void row_namesThePolicyOfTheInsuredFirst() {
        InsuredAssociation.Row row = new InsuredAssociation.Row(INSURED, RELATED, TYPE, LocalDate.of(2026, 11, 13),
                null);

        assertThat(row.policies()).containsExactly(INSURED, RELATED);
    }

    @ParameterizedTest(name = "запись {0}…{1} на {2}: {3}")
    @CsvSource(nullValues = "бессрочно", value = {
            "2026-11-15, 2026-11-15, 2026-11-15, true",
            "2026-11-15, 2026-11-15, 2026-11-14, false",
            "2026-11-15, 2026-11-15, 2026-11-16, false",
            "2026-01-01, 2026-12-31, 2026-11-15, true",
            "2026-01-01, бессрочно, 2026-11-15, true",
            "2026-11-16, бессрочно, 2026-11-15, false"})
    void row_isValidOnDay_fromItsFirstToItsLastDayInclusive(
            LocalDate validFrom, @Nullable LocalDate validTo, LocalDate day, boolean valid) {

        InsuredAssociation.Row row = new InsuredAssociation.Row(INSURED, RELATED, TYPE, validFrom, validTo);

        assertThat(row.validOn(day)).isEqualTo(valid);
    }
}
