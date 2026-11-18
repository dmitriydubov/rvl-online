package ru.projectx.datareference.service.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.projectx.datareference.dto.PolicyResponse;
import ru.projectx.datareference.entity.policy.Policy;
import ru.projectx.datareference.exception.AmbiguousPolicyNumberException;
import ru.projectx.datareference.exception.AmbiguousReferenceException;
import ru.projectx.datareference.exception.PolicyNumberCandidate;
import ru.projectx.datareference.exception.ReferenceNotFoundException;
import ru.projectx.datareference.repository.catalog.InsuranceContractRepository;
import ru.projectx.datareference.repository.policy.PolicyRepository;
import ru.projectx.datareference.repository.catalog.ProgramRepository;
import ru.projectx.datareference.service.ReadFixtures;

@ExtendWith(MockitoExtension.class)
class PolicyQueryServiceTest {

    private static final UUID ID = UUID.fromString("d0000000-0000-4000-8000-000000000001");
    private static final UUID EXTERNAL_ID = UUID.fromString("d0000000-0000-4000-8000-000000000002");
    private static final UUID CONTRACT_EXTERNAL_ID = UUID.fromString("d0000000-0000-4000-8000-000000000004");
    private static final UUID RVL_PROGRAM = UUID.fromString("d0000000-0000-4000-8000-000000000005");
    private static final UUID ARCHIVED_PROGRAM = UUID.fromString("d0000000-0000-4000-8000-000000000006");
    private static final UUID LINE_ONLY_PROGRAM = UUID.fromString("d0000000-0000-4000-8000-000000000007");
    private static final String NUMBER = "П-0001";

    @Mock
    private PolicyRepository policies;

    @Mock
    private InsuranceContractRepository insuranceContracts;

    @Mock
    private ProgramRepository programs;

    @InjectMocks
    private PolicyQueryService service;

    @Test
    void byId_carriesContractAndOnlyActiveRvlPrograms_lookedUpByAllReferencedGuids() {
        Policy policy = ReadFixtures.policy(CONTRACT_EXTERNAL_ID,
                List.of(RVL_PROGRAM, ARCHIVED_PROGRAM), List.of(RVL_PROGRAM, LINE_ONLY_PROGRAM));
        when(policies.findById(ID)).thenReturn(Optional.of(policy));
        when(insuranceContracts.findByExternalId(CONTRACT_EXTERNAL_ID))
                .thenReturn(Optional.of(ReadFixtures.contract(CONTRACT_EXTERNAL_ID, null)));
        when(programs.findAllByExternalIdIn(Set.of(RVL_PROGRAM, ARCHIVED_PROGRAM, LINE_ONLY_PROGRAM))).thenReturn(List.of(
                ReadFixtures.program(RVL_PROGRAM, false), ReadFixtures.program(ARCHIVED_PROGRAM, true)));

        PolicyResponse response = service.byId(ID);

        assertThat(response.contract()).isNotNull()
                .extracting(PolicyResponse.ContractRef::externalId).isEqualTo(CONTRACT_EXTERNAL_ID);
        assertThat(response.programs()).extracting(line -> line.program().externalId()).containsExactly(RVL_PROGRAM);
        assertThat(response.programs().getFirst().services()).hasSize(1);
    }

    @Test
    void policyWithoutPrograms_doesNotQueryPrograms_andMissingContractIsEmpty() {
        when(policies.findByExternalId(EXTERNAL_ID))
                .thenReturn(Optional.of(ReadFixtures.policy(CONTRACT_EXTERNAL_ID, List.of(), List.of())));
        when(insuranceContracts.findByExternalId(CONTRACT_EXTERNAL_ID)).thenReturn(Optional.empty());

        PolicyResponse response = service.byExternalId(EXTERNAL_ID);

        assertThat(response.contract()).isNull();
        assertThat(response.programs()).isEmpty();
        verify(programs, never()).findAllByExternalIdIn(any());
    }

    @Test
    void byId_unknown_isPolicyNotFound() {
        when(policies.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.byId(ID))
                .isInstanceOfSatisfying(ReferenceNotFoundException.class, notFound -> {
                    assertThat(notFound.code()).isEqualTo("POLICY_NOT_FOUND");
                    assertThat(notFound.getMessage()).contains(ID.toString());
                });
    }

    @Test
    void byExternalId_unknown_isPolicyNotFound_namingExternalId() {
        when(policies.findByExternalId(EXTERNAL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.byExternalId(EXTERNAL_ID))
                .isInstanceOfSatisfying(ReferenceNotFoundException.class,
                        notFound -> assertThat(notFound.getMessage()).contains("external_id " + EXTERNAL_ID));
    }

    @Test
    void byNumber_findsTheOnlyPolicyOfContract_byContractGuid() {
        when(insuranceContracts.findByExternalId(CONTRACT_EXTERNAL_ID))
                .thenReturn(Optional.of(ReadFixtures.contract(CONTRACT_EXTERNAL_ID, null)));
        when(policies.findAllByNumberAndExternalContractId(NUMBER, CONTRACT_EXTERNAL_ID))
                .thenReturn(List.of(ReadFixtures.policy(CONTRACT_EXTERNAL_ID, List.of(), List.of())));

        assertThat(service.byNumber(NUMBER, CONTRACT_EXTERNAL_ID).number()).isEqualTo(NUMBER);
    }

    @Test
    void byNumber_policyArrivedBeforeItsContract_isFound_withoutContract() {
        when(insuranceContracts.findByExternalId(CONTRACT_EXTERNAL_ID)).thenReturn(Optional.empty());
        when(policies.findAllByNumberAndExternalContractId(NUMBER, CONTRACT_EXTERNAL_ID))
                .thenReturn(List.of(ReadFixtures.policy(CONTRACT_EXTERNAL_ID, List.of(), List.of())));

        PolicyResponse found = service.byNumber(NUMBER, CONTRACT_EXTERNAL_ID);

        assertThat(found.number()).isEqualTo(NUMBER);
        assertThat(found.contract()).isNull();
    }

    @Test
    void byNumber_twoPolicies_isAmbiguous() {
        when(policies.findAllByNumberAndExternalContractId(NUMBER, CONTRACT_EXTERNAL_ID)).thenReturn(List.of(
                ReadFixtures.policy(CONTRACT_EXTERNAL_ID, List.of(), List.of()),
                ReadFixtures.policy(CONTRACT_EXTERNAL_ID, List.of(), List.of())));

        assertThatThrownBy(() -> service.byNumber(NUMBER, CONTRACT_EXTERNAL_ID))
                .isInstanceOfSatisfying(AmbiguousReferenceException.class, ambiguous -> {
                    assertThat(ambiguous.code()).isEqualTo("AMBIGUOUS_POLICY");
                    assertThat(ambiguous.candidateIds()).hasSize(2);
                });
    }

    @Test
    void byNumber_none_isPolicyNotFound_namingContractNotNumber_withoutLookingUpContract() {
        when(policies.findAllByNumberAndExternalContractId(NUMBER, CONTRACT_EXTERNAL_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.byNumber(NUMBER, CONTRACT_EXTERNAL_ID))
                .isInstanceOfSatisfying(ReferenceNotFoundException.class, notFound -> {
                    assertThat(notFound.code()).isEqualTo("POLICY_NOT_FOUND");
                    assertThat(notFound.getMessage()).contains(CONTRACT_EXTERNAL_ID.toString()).doesNotContain(NUMBER);
                });
        verifyNoInteractions(insuranceContracts);
    }

    @Test
    void byNumberInAnyContract_findsTheOnlyPolicyWithThisNumber_withItsContract() {
        Policy policy = ReadFixtures.policy(CONTRACT_EXTERNAL_ID, List.of(), List.of());
        when(policies.findAllByNumber(NUMBER)).thenReturn(List.of(policy));
        when(insuranceContracts.findByExternalId(CONTRACT_EXTERNAL_ID))
                .thenReturn(Optional.of(ReadFixtures.contract(CONTRACT_EXTERNAL_ID, null)));

        PolicyResponse found = service.byNumberInAnyContract(NUMBER);

        assertThat(found.id()).isEqualTo(policy.id());
        assertThat(found.contract()).isNotNull()
                .extracting(PolicyResponse.ContractRef::externalId).isEqualTo(CONTRACT_EXTERNAL_ID);
    }

    @Test
    void byNumberInAnyContract_none_isPolicyNotFound_withoutNumberInMessage() {
        when(policies.findAllByNumber(NUMBER)).thenReturn(List.of());

        assertThatThrownBy(() -> service.byNumberInAnyContract(NUMBER))
                .isInstanceOfSatisfying(ReferenceNotFoundException.class, notFound -> {
                    assertThat(notFound.code()).isEqualTo("POLICY_NOT_FOUND");
                    assertThat(notFound.getMessage()).isNotBlank().doesNotContain(NUMBER);
                });
        verifyNoInteractions(insuranceContracts);
    }

    @Test
    void byNumberInAnyContract_several_isAmbiguous_namingEachPolicyWithItsContractGuid_withoutNumber() {
        UUID otherContract = UUID.fromString("d0000000-0000-4000-8000-000000000008");
        Policy first = ReadFixtures.policy(CONTRACT_EXTERNAL_ID, List.of(), List.of());
        Policy second = ReadFixtures.policy(otherContract, List.of(), List.of());
        when(policies.findAllByNumber(NUMBER)).thenReturn(List.of(first, second));

        assertThatThrownBy(() -> service.byNumberInAnyContract(NUMBER))
                .isInstanceOfSatisfying(AmbiguousPolicyNumberException.class, ambiguous -> {
                    assertThat(ambiguous.code()).isEqualTo("AMBIGUOUS_POLICY");
                    assertThat(ambiguous.candidates()).containsExactly(
                            new PolicyNumberCandidate(first.id(), CONTRACT_EXTERNAL_ID),
                            new PolicyNumberCandidate(second.id(), otherContract));
                    assertThat(ambiguous.getMessage()).isNotBlank().doesNotContain(NUMBER);
                });
        verifyNoInteractions(insuranceContracts);
    }
}
