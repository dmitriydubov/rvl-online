package ru.projectx.datareference.service.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.projectx.datareference.service.policy.FamilyFixtures.INSURED;
import static ru.projectx.datareference.service.policy.FamilyFixtures.OTHER_TYPE;
import static ru.projectx.datareference.service.policy.FamilyFixtures.TODAY;
import static ru.projectx.datareference.service.policy.FamilyFixtures.TYPE;
import static ru.projectx.datareference.service.policy.FamilyFixtures.link;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RelationTypesTest {

    private static final UUID SPOUSE = UUID.fromString("11111111-2222-4333-8444-555555555502");
    private static final UUID CHILD = UUID.fromString("11111111-2222-4333-8444-555555555503");
    private static final UUID FORMER = UUID.fromString("11111111-2222-4333-8444-555555555505");

    @Test
    void activeOn_keepsRelatedPoliciesOfRowsValidThatDayOnly() {
        RelationTypes types = RelationTypes.activeOn(List.of(
                link(SPOUSE, TYPE, TODAY, TODAY),
                link(FORMER, TYPE, TODAY.minusYears(1), TODAY.minusDays(1))), TODAY);

        assertThat(types.relatedPolicies()).containsExactly(SPOUSE);
    }

    @Test
    void soleType_isTheOnlyTypeValidThatDay_howeverManyRowsCoverIt() {
        RelationTypes types = RelationTypes.activeOn(List.of(
                link(SPOUSE, TYPE, TODAY.minusYears(1), null),
                link(SPOUSE, TYPE, TODAY.minusDays(5), TODAY.plusDays(5)),
                link(SPOUSE, OTHER_TYPE, TODAY.minusYears(1), TODAY.minusDays(1))), TODAY);

        assertThat(types.soleTypeOf(SPOUSE)).isEqualTo(TYPE);
    }

    @Test
    void soleType_isNone_whenTwoTypesAreValidThatDay() {
        RelationTypes types = RelationTypes.activeOn(List.of(
                link(SPOUSE, TYPE, TODAY.minusYears(1), null),
                link(SPOUSE, OTHER_TYPE, TODAY, TODAY),
                link(CHILD, TYPE, TODAY.minusYears(1), null)), TODAY);

        assertThat(types.soleTypeOf(SPOUSE)).isNull();
        assertThat(types.soleTypeOf(CHILD)).isEqualTo(TYPE);
    }

    @Test
    void linkOfTheInsuredPolicyToItself_isLeftOut() {
        RelationTypes types = RelationTypes.activeOn(List.of(
                link(INSURED, TYPE, TODAY, null),
                link(SPOUSE, TYPE, TODAY, null)), TODAY);

        assertThat(types.relatedPolicies()).containsExactly(SPOUSE);
    }

    @Test
    void soleType_ofPolicyWithoutLinks_isNone() {
        assertThat(RelationTypes.activeOn(List.of(), TODAY).soleTypeOf(SPOUSE)).isNull();
    }
}
