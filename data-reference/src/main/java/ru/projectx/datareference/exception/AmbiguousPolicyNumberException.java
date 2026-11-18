package ru.projectx.datareference.exception;

import java.io.Serial;
import java.util.List;

public final class AmbiguousPolicyNumberException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String AMBIGUOUS_POLICY = "AMBIGUOUS_POLICY";

    private final transient List<PolicyNumberCandidate> candidates;

    private AmbiguousPolicyNumberException(List<PolicyNumberCandidate> candidates) {
        super("по номеру полиса найдено больше одной записи");
        this.candidates = List.copyOf(candidates);
    }

    public static AmbiguousPolicyNumberException of(List<PolicyNumberCandidate> candidates) {
        return new AmbiguousPolicyNumberException(candidates);
    }

    public String code() {
        return AMBIGUOUS_POLICY;
    }

    public List<PolicyNumberCandidate> candidates() {
        return candidates;
    }
}
