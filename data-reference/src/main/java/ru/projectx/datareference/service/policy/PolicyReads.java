package ru.projectx.datareference.service.policy;

import java.util.UUID;
import ru.projectx.datareference.dto.PolicyResponse;

public interface PolicyReads {

    PolicyResponse byId(UUID id);

    PolicyResponse byExternalId(UUID externalId);

    PolicyResponse byNumber(String number, UUID contractExternalId);

    PolicyResponse byNumberInAnyContract(String number);
}
