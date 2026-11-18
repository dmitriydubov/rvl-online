package ru.projectx.datareference.dto;

import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import ru.projectx.datareference.entity.policy.Policy;

public record FamilyMember(
        UUID id,
        UUID externalId,
        String number,
        @Nullable String lastName,
        @Nullable String firstName,
        @Nullable String middleName,
        @Nullable LocalDate dateOfBirth,
        @Nullable String gender,
        @Nullable UUID relationship) implements FamilyAnswer {

    public static FamilyMember of(Policy policy, @Nullable UUID relationship) {
        Policy.Details details = policy.details();
        return new FamilyMember(policy.id(), policy.externalId(), details.number(),
                details.lastName(), details.firstName(), details.middleName(), details.dateOfBirth(), details.gender(),
                relationship);
    }
}
