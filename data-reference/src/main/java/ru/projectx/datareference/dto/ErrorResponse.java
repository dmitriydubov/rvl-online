package ru.projectx.datareference.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ErrorResponse(Body error) {

    public static ErrorResponse of(String code, String message, @Nullable String requestId) {
        return new ErrorResponse(new Body(code, message, List.of(), requestId));
    }

    public static ErrorResponse of(
            String code, String message, List<? extends Detail> details, @Nullable String requestId) {

        return new ErrorResponse(new Body(code, message, List.copyOf(details), requestId));
    }

    public record Body(String code, String message, List<? extends Detail> details, @Nullable String requestId) {
    }

    public sealed interface Detail permits Violation, PolicyCandidate, LpuContractCandidate, InsuranceContractCandidate,
            PriceRowCandidate, UncoveredInterval {
    }

    public record Violation(String field, String reason) implements Detail {
    }

    public record PolicyCandidate(
            UUID policyId, @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable UUID contractExternalId)
            implements Detail {

        public PolicyCandidate(UUID policyId) {
            this(policyId, null);
        }
    }

    public record LpuContractCandidate(UUID lpuContractId) implements Detail {
    }

    public record InsuranceContractCandidate(UUID insuranceContractId) implements Detail {
    }

    public record PriceRowCandidate(LocalDate validFrom, @Nullable LocalDate validTo) implements Detail {
    }

    public record UncoveredInterval(LocalDate dateFrom, LocalDate dateTo) implements Detail {
    }
}
