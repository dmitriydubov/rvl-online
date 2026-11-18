package ru.projectx.datareference.exception;

import java.io.Serial;

public final class ReferenceNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    static final String PROGRAM_NOT_FOUND = "PROGRAM_NOT_FOUND";
    static final String POLICY_NOT_FOUND = "POLICY_NOT_FOUND";
    static final String INSURANCE_CONTRACT_NOT_FOUND = "INSURANCE_CONTRACT_NOT_FOUND";
    static final String LPU_NOT_FOUND = "LPU_NOT_FOUND";
    static final String SERVICE_NOT_FOUND = "SERVICE_NOT_FOUND";

    private final String code;

    ReferenceNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static ReferenceNotFoundException policyWithRequestedNumber() {
        return new ReferenceNotFoundException(POLICY_NOT_FOUND, "полис с таким номером не найден");
    }

    public String code() {
        return code;
    }
}
