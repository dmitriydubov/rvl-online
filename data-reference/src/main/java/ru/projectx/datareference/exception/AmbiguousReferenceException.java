package ru.projectx.datareference.exception;

import java.io.Serial;
import java.util.List;
import java.util.UUID;

public final class AmbiguousReferenceException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String AMBIGUOUS_POLICY = "AMBIGUOUS_POLICY";
    public static final String AMBIGUOUS_PRICE_CONTRACT = "AMBIGUOUS_PRICE_CONTRACT";
    private static final String AMBIGUOUS_INSURANCE_CONTRACT = "AMBIGUOUS_INSURANCE_CONTRACT";

    public enum CandidateKind {
        POLICY,
        LPU_CONTRACT,
        INSURANCE_CONTRACT
    }

    private final String code;
    private final CandidateKind candidateKind;
    private final transient List<UUID> candidateIds;

    private AmbiguousReferenceException(String code, String message, CandidateKind kind, List<UUID> candidateIds) {
        super(message);
        this.code = code;
        this.candidateKind = kind;
        this.candidateIds = List.copyOf(candidateIds);
    }

    public static AmbiguousReferenceException policy(List<UUID> policyIds) {
        return new AmbiguousReferenceException(AMBIGUOUS_POLICY,
                "по номеру полиса в договоре найдено больше одной записи", CandidateKind.POLICY, policyIds);
    }

    public static AmbiguousReferenceException relativePolicy(List<UUID> policyIds) {
        return new AmbiguousReferenceException(AMBIGUOUS_POLICY,
                "по номеру полиса среди родственников застрахованного найдено больше одной записи", CandidateKind.POLICY,
                policyIds);
    }

    public static AmbiguousReferenceException priceContract(UUID lpuId, List<UUID> lpuContractIds) {
        return new AmbiguousReferenceException(AMBIGUOUS_PRICE_CONTRACT,
                "у ЛПУ " + lpuId + " больше одного действующего кандидата на прайсовый договор",
                CandidateKind.LPU_CONTRACT, lpuContractIds);
    }

    public static AmbiguousReferenceException insuranceContract(UUID insurerExternalId, List<UUID> contractIds) {
        return new AmbiguousReferenceException(AMBIGUOUS_INSURANCE_CONTRACT,
                "у страхователя " + insurerExternalId + " больше одного действующего договора страхования",
                CandidateKind.INSURANCE_CONTRACT, contractIds);
    }

    public String code() {
        return code;
    }

    public CandidateKind candidateKind() {
        return candidateKind;
    }

    public List<UUID> candidateIds() {
        return candidateIds;
    }
}
