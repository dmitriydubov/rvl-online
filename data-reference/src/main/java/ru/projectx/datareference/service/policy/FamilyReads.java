package ru.projectx.datareference.service.policy;

import java.util.UUID;
import ru.projectx.datareference.dto.FamilyMember;
import ru.projectx.datareference.dto.FamilyResponse;

public interface FamilyReads {

    FamilyResponse family(UUID insuredPolicyId);

    FamilyMember member(UUID insuredPolicyId, String number);
}
