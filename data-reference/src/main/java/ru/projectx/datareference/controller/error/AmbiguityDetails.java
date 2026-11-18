package ru.projectx.datareference.controller.error;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import ru.projectx.datareference.dto.ErrorResponse;
import ru.projectx.datareference.exception.AmbiguousPolicyNumberException;
import ru.projectx.datareference.exception.AmbiguousReferenceException;

final class AmbiguityDetails {

    private AmbiguityDetails() {
    }

    static List<ErrorResponse.Detail> of(AmbiguousReferenceException ambiguous) {
        Function<UUID, ErrorResponse.Detail> detail = switch (ambiguous.candidateKind()) {
            case POLICY -> ErrorResponse.PolicyCandidate::new;
            case LPU_CONTRACT -> ErrorResponse.LpuContractCandidate::new;
            case INSURANCE_CONTRACT -> ErrorResponse.InsuranceContractCandidate::new;
        };
        return ambiguous.candidateIds().stream().map(detail).toList();
    }

    static List<ErrorResponse.PolicyCandidate> of(AmbiguousPolicyNumberException ambiguous) {
        return ambiguous.candidates().stream()
                .map(candidate -> new ErrorResponse.PolicyCandidate(candidate.policyId(), candidate.contractExternalId()))
                .toList();
    }
}
