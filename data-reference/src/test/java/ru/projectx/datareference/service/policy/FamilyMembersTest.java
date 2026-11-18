package ru.projectx.datareference.service.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.projectx.datareference.service.policy.FamilyFixtures.TODAY;
import static ru.projectx.datareference.service.policy.FamilyFixtures.TYPE;
import static ru.projectx.datareference.service.policy.FamilyFixtures.link;
import static ru.projectx.datareference.service.policy.FamilyFixtures.policy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.projectx.datareference.dto.FamilyMember;
import ru.projectx.datareference.entity.policy.Policy;

class FamilyMembersTest {

    private static final UUID SPOUSE = UUID.fromString("11111111-2222-4333-8444-555555555502");
    private static final UUID CHILD = UUID.fromString("11111111-2222-4333-8444-555555555503");
    private static final UUID SAME_NUMBER = UUID.fromString("f1111111-2222-4333-8444-555555555507");

    @Test
    void member_carriesRequisitesOfThePolicyAndTheSoleTypeOfItsLink() {
        Policy spouse = policy(SPOUSE, "СТЕНД-0002");

        List<FamilyMember> members = FamilyMembers.of(List.of(spouse),
                RelationTypes.activeOn(List.of(link(SPOUSE, TYPE, TODAY, null)), TODAY), TODAY);

        assertThat(members).containsExactly(new FamilyMember(spouse.id(), SPOUSE, "СТЕНД-0002", "Тестова", "Проба",
                "Тестовна", LocalDate.of(2015, 1, 1), "Ж", TYPE));
    }

    @Test
    void policiesNotActiveThatDay_areLeftOut() {
        List<FamilyMember> members = FamilyMembers.of(List.of(
                        policy(SPOUSE, "СТЕНД-0002"),
                        policy(CHILD, "СТЕНД-0003", TODAY.plusDays(1), null)),
                RelationTypes.activeOn(List.of(), TODAY), TODAY);

        assertThat(members).extracting(FamilyMember::externalId).containsExactly(SPOUSE);
    }

    @Test
    void members_goByPolicyNumber_thenByPolicyGuidText() {
        List<FamilyMember> members = FamilyMembers.of(List.of(
                        policy(SAME_NUMBER, "СТЕНД-0003"),
                        policy(CHILD, "СТЕНД-0003"),
                        policy(SPOUSE, "СТЕНД-0004")),
                RelationTypes.activeOn(List.of(), TODAY), TODAY);

        assertThat(members).extracting(FamilyMember::externalId).containsExactly(CHILD, SAME_NUMBER, SPOUSE);
    }
}
