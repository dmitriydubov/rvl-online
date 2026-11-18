package ru.projectx.datareference.entity.policy;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

public record InsuredAssociationFilter(
        @Nullable LocalDate validFrom,
        @Nullable UUID insuredPolicy,
        @Nullable UUID relatedPolicy,
        @Nullable UUID relationType) {

    public boolean isEmpty() {
        return Stream.of(validFrom, insuredPolicy, relatedPolicy, relationType).allMatch(Objects::isNull);
    }

    public boolean covers(InsuredAssociation.Row row) {
        return Stream.of(
                        allows(validFrom, row.validFrom()),
                        allows(insuredPolicy, row.insuredPolicy()),
                        allows(relatedPolicy, row.relatedPolicy()),
                        allows(relationType, row.relationType()))
                .allMatch(Boolean::booleanValue);
    }

    private static boolean allows(@Nullable Object constraint, Object value) {
        return constraint == null || constraint.equals(value);
    }
}
