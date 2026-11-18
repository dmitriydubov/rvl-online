package ru.projectx.datareference.service.policy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import ru.projectx.datareference.entity.policy.InsuredAssociation;
import ru.projectx.datareference.entity.policy.Policy;

final class FamilyFixtures {

    static final UUID CONTRACT = UUID.fromString("77ff17b5-e659-4720-b57f-e8a19c00fcea");
    static final UUID INSURED = UUID.fromString("11111111-2222-4333-8444-555555555501");
    static final UUID TYPE = UUID.fromString("797093be-2c51-11e8-8123-005056b43f21");
    static final UUID OTHER_TYPE = UUID.fromString("4b4e0f45-2c50-11e8-8123-005056b43f21");
    static final LocalDate TODAY = LocalDate.of(2026, 11, 15);
    static final Instant NOW = Instant.parse("2026-11-15T09:00:00Z");

    private FamilyFixtures() {
    }

    static Policy policy(UUID externalId, String number) {
        return policy(externalId, number, LocalDate.of(2026, 1, 1), null);
    }

    static Policy policy(UUID externalId, String number, LocalDate begin, @Nullable LocalDate end) {
        var details = new Policy.Details(CONTRACT, number, "Тестова", "Проба", "Тестовна", LocalDate.of(2015, 1, 1),
                "Ж", "relative@example.test", begin, end, true, false);
        return Policy.create(externalId, new Policy.Snapshot(details, List.of(), List.of()), NOW);
    }

    static InsuredAssociation.Row link(UUID related, UUID type, LocalDate validFrom, @Nullable LocalDate validTo) {
        return new InsuredAssociation.Row(INSURED, related, type, validFrom, validTo);
    }
}
