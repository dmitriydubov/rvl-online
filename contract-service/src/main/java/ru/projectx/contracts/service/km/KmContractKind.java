package ru.projectx.contracts.service.km;

import ru.projectx.contracts.entity.contract.Contract;
import ru.projectx.contracts.entity.contract.ContractStatus;
import ru.projectx.contracts.entity.party.PersonOwner;
import ru.projectx.contracts.message.usk.KmContractEvents;
import ru.projectx.contracts.service.IssueErrors;
import ru.projectx.contracts.service.usk.IssuanceKind;

public final class KmContractKind {

    public static final IssuanceKind<Contract, ContractStatus> KIND = new IssuanceKind<>(KmContractTexts.WORDING,
            KmContractEvents.CONTRACT_AGGREGATE,
            KmContractEvents.KM_CONTRACT_SUBMITTED, KmContractEvents.KM_CONTRACT_REGISTERED,
            ContractStatus.ISSUANCE_FLOW, IssueErrors.kmContractSubmission(), PersonOwner::contract, Contract::markIssued);

    private KmContractKind() {
    }
}
