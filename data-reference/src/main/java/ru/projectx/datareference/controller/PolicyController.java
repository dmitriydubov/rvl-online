package ru.projectx.datareference.controller;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import ru.projectx.datareference.controller.error.RequestErrorAdvice;
import ru.projectx.datareference.dto.PolicyContractView;
import ru.projectx.datareference.dto.PolicyResponse;
import ru.projectx.datareference.dto.PolicySearchResponse;
import ru.projectx.datareference.exception.InvalidRequestException;
import ru.projectx.datareference.service.policy.PolicyContractViews;
import ru.projectx.datareference.service.policy.PolicySearchReads;
import ru.projectx.datareference.service.policy.PolicyReads;

@InternalApi
public class PolicyController {

    private static final String EXTERNAL_ID = "external_id";
    private static final String NUMBER = "number";
    private static final String CONTRACT_EXTERNAL_ID = "contract_external_id";
    private static final String LEGACY_CONTRACT_ID = "contract_id";

    private final PolicyReads policies;
    private final PolicySearchReads policySearch;
    private final PolicyContractViews contractViews;

    public PolicyController(
            PolicyReads policies, PolicySearchReads policySearch, PolicyContractViews contractViews) {

        this.policies = policies;
        this.policySearch = policySearch;
        this.contractViews = contractViews;
    }

    @GetMapping("/policies/{id}")
    public PolicyResponse policy(@PathVariable UUID id) {
        return policies.byId(id);
    }

    @GetMapping(path = "/policies",
            params = {EXTERNAL_ID, "!" + NUMBER, "!" + CONTRACT_EXTERNAL_ID, "!" + LEGACY_CONTRACT_ID})
    public PolicyResponse policyByExternalId(@RequestParam(EXTERNAL_ID) UUID externalId) {
        return policies.byExternalId(externalId);
    }

    @GetMapping(path = "/policies",
            params = {NUMBER, CONTRACT_EXTERNAL_ID, "!" + EXTERNAL_ID, "!" + LEGACY_CONTRACT_ID})
    public PolicyResponse policyByNumber(
            @RequestParam String number, @RequestParam(CONTRACT_EXTERNAL_ID) UUID contractExternalId) {

        return policies.byNumber(RequiredText.of(NUMBER, number), contractExternalId);
    }

    @GetMapping(path = "/policies",
            params = {NUMBER, "!" + CONTRACT_EXTERNAL_ID, "!" + EXTERNAL_ID, "!" + LEGACY_CONTRACT_ID})
    public PolicyResponse policyByNumberInAnyContract(@RequestParam String number) {
        return policies.byNumberInAnyContract(RequiredText.of(NUMBER, number));
    }

    @GetMapping(path = "/policies",
            params = {CONTRACT_EXTERNAL_ID, "!" + NUMBER, "!" + EXTERNAL_ID, "!" + LEGACY_CONTRACT_ID})
    public PolicyResponse policyByContractWithoutNumber() {
        throw new InvalidRequestException(NUMBER, RequestErrorAdvice.REQUIRED_PARAMETER);
    }

    @GetMapping(path = "/policies", params = EXTERNAL_ID)
    public PolicyResponse policyByConflictingKeys() {
        throw new InvalidRequestException(EXTERNAL_ID,
                "external_id не передаётся вместе с number или contract_external_id");
    }

    @GetMapping(path = "/policies", params = {LEGACY_CONTRACT_ID, "!" + EXTERNAL_ID})
    public PolicyResponse policyByLegacyContractId() {
        throw new InvalidRequestException(LEGACY_CONTRACT_ID,
                "не принимается: договор передаётся параметром contract_external_id");
    }

    @GetMapping("/policies")
    public PolicyResponse policyWithoutKey() {
        throw new InvalidRequestException(EXTERNAL_ID, "нужен external_id либо number");
    }

    @GetMapping("/policies/{id}/contract-view")
    public PolicyContractView contractView(@PathVariable UUID id) {
        return contractViews.byId(id);
    }

    @GetMapping(path = "/policies/contract-view", params = EXTERNAL_ID)
    public PolicyContractView contractViewByExternalId(@RequestParam(EXTERNAL_ID) UUID externalId) {
        return contractViews.byExternalId(externalId);
    }

    @GetMapping("/policies/contract-view")
    public PolicyContractView contractViewWithoutKey() {
        throw new InvalidRequestException(EXTERNAL_ID, "нужен external_id");
    }

    @GetMapping("/policies/search")
    public PolicySearchResponse searchPolicies(
            @RequestParam("contract_external_id") UUID contractExternalId,
            @RequestParam String query,
            @RequestParam(required = false) @Nullable Integer limit) {

        return policySearch.search(contractExternalId, query, limit);
    }
}
