package ru.projectx.datareference.entity.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PolicyActivityTest {

    private static final UUID CONTRACT = UUID.fromString("77ff17b5-e659-4720-b57f-e8a19c00fcea");

    @ParameterizedTest(name = "полис {0}…{1}, архив {2}, на {3}: {4}")
    @CsvSource(nullValues = "бессрочно", value = {
            "2026-11-15, 2026-11-15, false, 2026-11-15, true",
            "2026-11-15, 2026-11-15, false, 2026-11-14, false",
            "2026-11-15, 2026-11-15, false, 2026-11-16, false",
            "2026-01-01, бессрочно, false, 2026-11-15, true",
            "2026-01-01, бессрочно, true, 2026-11-15, false"})
    void policy_isActiveOnDay_whenNotArchived_fromItsFirstToItsLastDayInclusive(
            LocalDate begin, @Nullable LocalDate end, boolean archived, LocalDate day, boolean active) {

        var details = new Policy.Details(CONTRACT, "СТЕНД-0002", null, null, null, null, null, null, begin, end, true,
                archived);
        Policy policy = Policy.create(UUID.randomUUID(), new Policy.Snapshot(details, List.of(), List.of()),
                Instant.parse("2026-11-15T09:00:00Z"));

        assertThat(policy.isActiveOn(day)).isEqualTo(active);
    }
}
