package ru.projectx.datareference.service.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static ru.projectx.datareference.service.policy.FamilyFixtures.INSURED;
import static ru.projectx.datareference.service.policy.FamilyFixtures.NOW;
import static ru.projectx.datareference.service.policy.FamilyFixtures.TYPE;
import static ru.projectx.datareference.service.policy.FamilyFixtures.link;
import static ru.projectx.datareference.service.policy.FamilyFixtures.policy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.projectx.datareference.entity.policy.InsuredAssociation;
import ru.projectx.datareference.entity.policy.Policy;
import ru.projectx.datareference.exception.AmbiguousReferenceException;
import ru.projectx.datareference.exception.ReferenceNotFoundException;
import ru.projectx.datareference.repository.policy.InsuredAssociationRepository;
import ru.projectx.datareference.repository.policy.PolicyRepository;
import ru.projectx.datareference.service.rest.ReferenceCalendar;
import ru.projectx.datareference.support.TestApiProperties;

@ExtendWith(MockitoExtension.class)
class FamilyServiceTest {

    private static final UUID INSURED_ID = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    private static final UUID SPOUSE = UUID.fromString("11111111-2222-4333-8444-555555555502");
    private static final UUID CHILD = UUID.fromString("11111111-2222-4333-8444-555555555503");
    private static final UUID LATER = UUID.fromString("11111111-2222-4333-8444-555555555506");
    private static final LocalDate MOSCOW_DAY = LocalDate.of(2026, 11, 15);

    @Mock
    private PolicyRepository policies;

    @Mock
    private InsuredAssociationRepository associations;

    private FamilyService service;

    @BeforeEach
    void setUp() {
        Clock justAfterMoscowMidnight = Clock.fixed(Instant.parse("2026-11-14T21:00:01Z"), ZoneOffset.UTC);
        service = new FamilyService(policies, associations,
                new ReferenceCalendar(justAfterMoscowMidnight, TestApiProperties.defaults()));
    }

    @Test
    void family_isReadForTheMoscowDay_fromPoliciesOfLinksValidThatDay() {
        insuredHasLinks(link(SPOUSE, TYPE, MOSCOW_DAY, null), link(LATER, TYPE, MOSCOW_DAY.plusDays(1), null));
        when(policies.findAllByExternalIdIn(Set.of(SPOUSE))).thenReturn(List.of(policy(SPOUSE, "СТЕНД-0002")));

        assertThat(service.family(INSURED_ID).items()).singleElement()
                .satisfies(member -> assertThat(member.relationship()).isEqualTo(TYPE));
    }

    @Test
    void family_withoutValidLinks_isEmpty_withoutReadingPolicies() {
        insuredHasLinks(link(LATER, TYPE, MOSCOW_DAY.plusDays(1), null));

        assertThat(service.family(INSURED_ID).items()).isEmpty();
        verify(policies, never()).findAllByExternalIdIn(any());
    }

    @Test
    void unknownInsuredPolicy_isPolicyNotFound_withoutReadingLinks() {
        when(policies.findById(INSURED_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.family(INSURED_ID))
                .isInstanceOfSatisfying(ReferenceNotFoundException.class, notFound -> {
                    assertThat(notFound.code()).isEqualTo("POLICY_NOT_FOUND");
                    assertThat(notFound.getMessage()).contains(INSURED_ID.toString());
                });
        verifyNoInteractions(associations);
    }

    @Test
    void member_isTheRelativeWithThatNumber() {
        insuredHasLinks(link(SPOUSE, TYPE, MOSCOW_DAY, null), link(CHILD, TYPE, MOSCOW_DAY, null));
        when(policies.findAllByExternalIdIn(Set.of(SPOUSE, CHILD)))
                .thenReturn(List.of(policy(SPOUSE, "СТЕНД-0002"), policy(CHILD, "СТЕНД-0003")));

        assertThat(service.member(INSURED_ID, "СТЕНД-0003").externalId()).isEqualTo(CHILD);
    }

    @Test
    void member_numberNotAmongRelatives_isPolicyNotFound_namingTheInsuredPolicy() {
        insuredHasLinks(link(SPOUSE, TYPE, MOSCOW_DAY, null));
        when(policies.findAllByExternalIdIn(Set.of(SPOUSE))).thenReturn(List.of(policy(SPOUSE, "СТЕНД-0002")));

        assertThatThrownBy(() -> service.member(INSURED_ID, "СТЕНД-0001"))
                .isInstanceOfSatisfying(ReferenceNotFoundException.class, notFound -> {
                    assertThat(notFound.code()).isEqualTo("POLICY_NOT_FOUND");
                    assertThat(notFound.getMessage()).contains(INSURED_ID.toString()).doesNotContain("СТЕНД");
                });
    }

    @Test
    void member_twoRelativesWithThatNumber_isAmbiguousPolicy_listingBoth() {
        insuredHasLinks(link(SPOUSE, TYPE, MOSCOW_DAY, null), link(CHILD, TYPE, MOSCOW_DAY, null));
        Policy spouse = policy(SPOUSE, "СТЕНД-0007");
        Policy child = policy(CHILD, "СТЕНД-0007");
        when(policies.findAllByExternalIdIn(Set.of(SPOUSE, CHILD))).thenReturn(List.of(spouse, child));

        assertThatThrownBy(() -> service.member(INSURED_ID, "СТЕНД-0007"))
                .isInstanceOfSatisfying(AmbiguousReferenceException.class, ambiguous -> {
                    assertThat(ambiguous.code()).isEqualTo("AMBIGUOUS_POLICY");
                    assertThat(ambiguous.candidateKind()).isEqualTo(AmbiguousReferenceException.CandidateKind.POLICY);
                    assertThat(ambiguous.candidateIds()).containsExactlyInAnyOrder(spouse.id(), child.id());
                });
    }

    private void insuredHasLinks(InsuredAssociation.Row... rows) {
        Policy insured = policy(INSURED, "СТЕНД-0001");
        when(policies.findById(INSURED_ID)).thenReturn(Optional.of(insured));
        when(associations.findAllByInsuredPolicyExternalId(INSURED))
                .thenReturn(List.of(rows).stream().map(row -> InsuredAssociation.of(row, NOW)).toList());
    }
}
