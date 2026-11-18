package ru.projectx.datareference.exception;

import static ru.projectx.datareference.exception.ReferenceNotFoundException.INSURANCE_CONTRACT_NOT_FOUND;
import static ru.projectx.datareference.exception.ReferenceNotFoundException.LPU_NOT_FOUND;
import static ru.projectx.datareference.exception.ReferenceNotFoundException.POLICY_NOT_FOUND;
import static ru.projectx.datareference.exception.ReferenceNotFoundException.PROGRAM_NOT_FOUND;
import static ru.projectx.datareference.exception.ReferenceNotFoundException.SERVICE_NOT_FOUND;

import java.util.UUID;

public enum MissingReference {
    PROGRAM(PROGRAM_NOT_FOUND, "программа страхования %s не найдена"),
    POLICY(POLICY_NOT_FOUND, "полис %s не найден"),
    POLICY_BY_EXTERNAL_ID(POLICY_NOT_FOUND, "полис с external_id %s не найден"),
    POLICY_BY_NUMBER(POLICY_NOT_FOUND, "в договоре страхования с external_id %s нет полиса с таким номером"),
    RELATIVE_POLICY_BY_NUMBER(POLICY_NOT_FOUND, "у застрахованного по полису %s нет родственника с таким номером полиса"),
    INSURANCE_CONTRACT(INSURANCE_CONTRACT_NOT_FOUND, "договор страхования %s не найден"),
    INSURANCE_CONTRACT_BY_EXTERNAL_ID(INSURANCE_CONTRACT_NOT_FOUND, "договор страхования с external_id %s не найден"),
    ACTIVE_INSURANCE_CONTRACT_OF_INSURER(INSURANCE_CONTRACT_NOT_FOUND,
            "у страхователя %s нет действующего договора страхования"),
    LPU(LPU_NOT_FOUND, "ЛПУ %s не найдено"),
    SERVICE(SERVICE_NOT_FOUND, "услуга %s не найдена");

    private final String code;
    private final String messageTemplate;

    MissingReference(String code, String messageTemplate) {
        this.code = code;
        this.messageTemplate = messageTemplate;
    }

    public ReferenceNotFoundException notFound(UUID id) {
        return new ReferenceNotFoundException(code, messageTemplate.formatted(id));
    }
}
