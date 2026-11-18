package ru.projectx.datareference.service.policy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ru.projectx.datareference.dto.FamilyMember;
import ru.projectx.datareference.dto.FamilyResponse;
import ru.projectx.datareference.entity.policy.InsuredAssociation;
import ru.projectx.datareference.entity.policy.Policy;
import ru.projectx.datareference.exception.AmbiguousReferenceException;
import ru.projectx.datareference.exception.MissingReference;
import ru.projectx.datareference.repository.policy.InsuredAssociationRepository;
import ru.projectx.datareference.repository.policy.PolicyRepository;
import ru.projectx.datareference.service.rest.ReferenceCalendar;
import ru.projectx.datareference.service.rest.RestReadTransaction;

@Service
public class FamilyService implements FamilyReads {

    private final PolicyRepository policies;
    private final InsuredAssociationRepository associations;
    private final ReferenceCalendar calendar;

    public FamilyService(
            PolicyRepository policies, InsuredAssociationRepository associations, ReferenceCalendar calendar) {

        this.policies = policies;
        this.associations = associations;
        this.calendar = calendar;
    }

    @Override
    @RestReadTransaction
    public FamilyResponse family(UUID insuredPolicyId) {
        return new FamilyResponse(membersOf(insuredPolicyId));
    }

    @Override
    @RestReadTransaction
    public FamilyMember member(UUID insuredPolicyId, String number) {
        List<FamilyMember> found = membersOf(insuredPolicyId).stream()
                .filter(member -> member.number().equals(number))
                .toList();
        if (found.size() > 1) {
            throw AmbiguousReferenceException.relativePolicy(found.stream().map(FamilyMember::id).toList());
        }
        return found.stream().findFirst()
                .orElseThrow(() -> MissingReference.RELATIVE_POLICY_BY_NUMBER.notFound(insuredPolicyId));
    }

    private List<FamilyMember> membersOf(UUID insuredPolicyId) {
        Policy insured = policies.findById(insuredPolicyId)
                .orElseThrow(() -> MissingReference.POLICY.notFound(insuredPolicyId));
        LocalDate today = calendar.today();
        List<InsuredAssociation.Row> rows = associations.findAllByInsuredPolicyExternalId(insured.externalId()).stream()
                .map(InsuredAssociation::row)
                .toList();
        RelationTypes types = RelationTypes.activeOn(rows, today);
        if (types.relatedPolicies().isEmpty()) {
            return List.of();
        }
        return FamilyMembers.of(policies.findAllByExternalIdIn(types.relatedPolicies()), types, today);
    }
}
