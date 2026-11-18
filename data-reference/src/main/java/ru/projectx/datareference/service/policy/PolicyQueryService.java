package ru.projectx.datareference.service.policy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import ru.projectx.datareference.dto.PolicyResponse;
import ru.projectx.datareference.entity.catalog.InsuranceContract;
import ru.projectx.datareference.entity.policy.Policy;
import ru.projectx.datareference.entity.policy.PolicyProgram;
import ru.projectx.datareference.entity.policy.PolicyServiceLine;
import ru.projectx.datareference.entity.catalog.Program;
import ru.projectx.datareference.exception.AmbiguousPolicyNumberException;
import ru.projectx.datareference.exception.AmbiguousReferenceException;
import ru.projectx.datareference.exception.PolicyNumberCandidate;
import ru.projectx.datareference.exception.MissingReference;
import ru.projectx.datareference.exception.ReferenceNotFoundException;
import ru.projectx.datareference.repository.catalog.InsuranceContractRepository;
import ru.projectx.datareference.repository.policy.PolicyRepository;
import ru.projectx.datareference.repository.catalog.ProgramRepository;
import ru.projectx.datareference.service.rest.RestReadTransaction;

@Service
public class PolicyQueryService implements PolicyReads {

    private final PolicyRepository policies;
    private final InsuranceContractRepository insuranceContracts;
    private final ProgramRepository programs;

    public PolicyQueryService(
            PolicyRepository policies, InsuranceContractRepository insuranceContracts, ProgramRepository programs) {

        this.policies = policies;
        this.insuranceContracts = insuranceContracts;
        this.programs = programs;
    }

    @Override
    @RestReadTransaction
    public PolicyResponse byId(UUID id) {
        return policies.findById(id)
                .map(this::assemble)
                .orElseThrow(() -> MissingReference.POLICY.notFound(id));
    }

    @Override
    @RestReadTransaction
    public PolicyResponse byExternalId(UUID externalId) {
        return policies.findByExternalId(externalId)
                .map(this::assemble)
                .orElseThrow(() -> MissingReference.POLICY_BY_EXTERNAL_ID.notFound(externalId));
    }

    @Override
    @RestReadTransaction
    public PolicyResponse byNumber(String number, UUID contractExternalId) {
        List<Policy> found = policies.findAllByNumberAndExternalContractId(number, contractExternalId);
        if (found.size() > 1) {
            throw AmbiguousReferenceException.policy(found.stream().map(Policy::id).toList());
        }
        return found.stream().findFirst()
                .map(this::assemble)
                .orElseThrow(() -> MissingReference.POLICY_BY_NUMBER.notFound(contractExternalId));
    }

    @Override
    @RestReadTransaction
    public PolicyResponse byNumberInAnyContract(String number) {
        List<Policy> found = policies.findAllByNumber(number);
        if (found.size() > 1) {
            throw AmbiguousPolicyNumberException.of(found.stream()
                    .map(policy -> new PolicyNumberCandidate(policy.id(), policy.details().externalContractId()))
                    .toList());
        }
        return found.stream().findFirst()
                .map(this::assemble)
                .orElseThrow(ReferenceNotFoundException::policyWithRequestedNumber);
    }

    private PolicyResponse assemble(Policy policy) {
        InsuranceContract contract = insuranceContracts.findByExternalId(policy.details().externalContractId())
                .orElse(null);
        return PolicyResponse.of(policy, contract, rvlPrograms(policy));
    }

    private Map<UUID, Program> rvlPrograms(Policy policy) {
        Set<UUID> referenced = Stream.concat(
                        policy.programs().stream().map(PolicyProgram::externalProgramId),
                        policy.serviceLines().stream().map(PolicyServiceLine::externalProgramId))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (referenced.isEmpty()) {
            return Map.of();
        }
        return programs.findAllByExternalIdIn(referenced).stream()
                .filter(program -> !program.isArchive())
                .collect(Collectors.toMap(Program::externalId, Function.identity()));
    }
}
