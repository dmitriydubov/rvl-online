package ru.projectx.datareference.service.policy;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import ru.projectx.datareference.dto.FamilyMember;
import ru.projectx.datareference.entity.policy.Policy;

final class FamilyMembers {

    private static final Comparator<FamilyMember> ORDER = Comparator.comparing(FamilyMember::number)
            .thenComparing(member -> member.externalId().toString());

    private FamilyMembers() {
    }

    static List<FamilyMember> of(Collection<Policy> relativePolicies, RelationTypes types, LocalDate day) {
        return relativePolicies.stream()
                .filter(policy -> policy.isActiveOn(day))
                .map(policy -> FamilyMember.of(policy, types.soleTypeOf(policy.externalId())))
                .sorted(ORDER)
                .toList();
    }
}
