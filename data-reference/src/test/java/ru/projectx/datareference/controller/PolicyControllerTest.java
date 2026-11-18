package ru.projectx.datareference.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import ru.projectx.datareference.dto.PolicyContractView;
import ru.projectx.datareference.dto.PolicyResponse;
import ru.projectx.datareference.dto.PolicySearchResponse;
import ru.projectx.datareference.service.policy.PolicyContractViews;
import ru.projectx.datareference.service.policy.PolicySearchReads;
import ru.projectx.datareference.service.policy.PolicyReads;

@ExtendWith(MockitoExtension.class)
class PolicyControllerTest {

    private static final String POLICIES = InternalApi.BASE_PATH + "/policies";
    private static final UUID POLICY_ID = UUID.fromString("9011c000-0000-4000-8000-000000000001");
    private static final UUID POLICY_EXTERNAL_ID = UUID.fromString("9011c000-0000-4000-8000-000000000002");
    private static final UUID CONTRACT_EXTERNAL_ID = UUID.fromString("9011c000-0000-4000-8000-000000000004");
    private static final String POLICY_NUMBER = "П-0001";
    private static final String POLICY_NUMBER_IN_URI = "P-0001";
    private static final String NO_KEY_REASON = "нужен external_id либо number";
    private static final String NO_EXTERNAL_ID_REASON = "нужен external_id";
    private static final String MISSING_VALUE_REASON = "обязательный параметр";

    @Mock
    private PolicyReads policies;

    @Mock
    private PolicySearchReads policySearch;

    @Mock
    private PolicyContractViews contractViews;

    private MockMvcTester mvc;

    @BeforeEach
    void setUp() {
        mvc = StandaloneMvc.of(new PolicyController(policies, policySearch, contractViews));
    }

    @Test
    void policyById_returnsPolicyFoundByService_asJson() {
        when(policies.byId(POLICY_ID)).thenReturn(policy());

        MvcTestResult result = mvc.get().uri(POLICIES + "/{id}", POLICY_ID).exchange();

        assertThat(result).hasStatusOk().hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(POLICY_ID.toString());
    }

    @Test
    void policyByExternalId_returnsPolicyFoundByService() {
        when(policies.byExternalId(POLICY_EXTERNAL_ID)).thenReturn(policy());

        MvcTestResult result = mvc.get().uri(POLICIES + "?external_id={external}", POLICY_EXTERNAL_ID).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(POLICY_ID.toString());
    }

    @Test
    void policyByNumber_passesNumberAndContractGuid() {
        when(policies.byNumber(POLICY_NUMBER, CONTRACT_EXTERNAL_ID)).thenReturn(policy());

        MvcTestResult result = mvc.get()
                .uri(POLICIES + "?number={number}&contract_external_id={contract}", POLICY_NUMBER, CONTRACT_EXTERNAL_ID)
                .exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(POLICY_ID.toString());
    }

    @Test
    void policyWithoutKey_isValidationErrorNamingExternalId() {
        MvcTestResult result = mvc.get().uri(POLICIES).exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo("VALIDATION_ERROR");
        assertThat(result).bodyJson().extractingPath("$.error.details[0].field").isEqualTo("external_id");
        assertThat(result).bodyJson().extractingPath("$.error.details[0].reason").isEqualTo(NO_KEY_REASON);
        verifyNoInteractions(policies, policySearch, contractViews);
    }

    @Test
    void numberWithoutContract_isLookupByNumberInAnyContract() {
        when(policies.byNumberInAnyContract(POLICY_NUMBER)).thenReturn(policy());

        MvcTestResult result = mvc.get().uri(POLICIES + "?number={number}", POLICY_NUMBER).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(POLICY_ID.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "%20%20"})
    void blankNumberWithoutContract_isMissingNumber_withoutAskingService(String number) {
        MvcTestResult result = mvc.get().uri(URI.create(POLICIES + "?number=" + number)).exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo("VALIDATION_ERROR");
        assertThat(result).bodyJson().extractingPath("$.error.details").asArray().hasSize(1);
        assertThat(result).bodyJson().extractingPath("$.error.details[0].field").isEqualTo("number");
        assertThat(result).bodyJson().extractingPath("$.error.details[0].reason").isEqualTo(MISSING_VALUE_REASON);
        verifyNoInteractions(policies, policySearch, contractViews);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "%20%20"})
    void numberWithBlankContract_isMissingContractExternalId_notLookupInAnyContract(String contract) {
        MvcTestResult result = mvc.get()
                .uri(URI.create(POLICIES + "?number=" + POLICY_NUMBER_IN_URI + "&contract_external_id=" + contract))
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error.details").asArray().hasSize(1);
        assertThat(result).bodyJson().extractingPath("$.error.details[0].field").isEqualTo("contract_external_id");
        assertThat(result).bodyJson().extractingPath("$.error.details[0].reason").isEqualTo(MISSING_VALUE_REASON);
        verifyNoInteractions(policies, policySearch, contractViews);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "%20%20"})
    void blankNumberWithContract_isMissingNumber_withoutAskingService(String number) {
        MvcTestResult result = mvc.get()
                .uri(URI.create(POLICIES + "?number=" + number + "&contract_external_id=" + CONTRACT_EXTERNAL_ID))
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo("VALIDATION_ERROR");
        assertThat(result).bodyJson().extractingPath("$.error.details").asArray().hasSize(1);
        assertThat(result).bodyJson().extractingPath("$.error.details[0].field").isEqualTo("number");
        assertThat(result).bodyJson().extractingPath("$.error.details[0].reason").isEqualTo(MISSING_VALUE_REASON);
        verifyNoInteractions(policies, policySearch, contractViews);
    }

    @Test
    void contractWithoutNumber_isMissingNumber() {
        MvcTestResult result = mvc.get()
                .uri(POLICIES + "?contract_external_id={contract}", CONTRACT_EXTERNAL_ID)
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error.details").asArray().hasSize(1);
        assertThat(result).bodyJson().extractingPath("$.error.details[0].field").isEqualTo("number");
        assertThat(result).bodyJson().extractingPath("$.error.details[0].reason").isEqualTo(MISSING_VALUE_REASON);
        verifyNoInteractions(policies, policySearch, contractViews);
    }

    @Test
    void search_passesContractQueryAndLimit_andReturnsFoundPolicies_asJson() {
        when(policySearch.search(CONTRACT_EXTERNAL_ID, "П-00", 5)).thenReturn(found());

        MvcTestResult result = mvc.get()
                .uri(POLICIES + "/search?contract_external_id={contract}&query={query}&limit={limit}",
                        CONTRACT_EXTERNAL_ID, "П-00", 5)
                .exchange();

        assertThat(result).hasStatusOk().hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().extractingPath("$.items[*].number").asArray().containsExactly(POLICY_NUMBER);
    }

    @Test
    void search_withoutLimit_leavesLimitToService() {
        when(policySearch.search(CONTRACT_EXTERNAL_ID, "П-00", null)).thenReturn(found());

        MvcTestResult result = mvc.get()
                .uri(POLICIES + "/search?contract_external_id={contract}&query={query}", CONTRACT_EXTERNAL_ID, "П-00")
                .exchange();

        assertThat(result).bodyJson().extractingPath("$.items[*].number").asArray().containsExactly(POLICY_NUMBER);
        verifyNoInteractions(policies);
    }

    @Test
    void search_withoutContract_isValidationErrorNamingContract_withoutAskingService() {
        MvcTestResult result = mvc.get().uri(POLICIES + "/search?query={query}", "П-00").exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error.details[0].field").isEqualTo("contract_external_id");
        verifyNoInteractions(policies, policySearch, contractViews);
    }

    @Test
    void search_withoutQuery_isValidationErrorNamingQuery_withoutAskingService() {
        MvcTestResult result = mvc.get()
                .uri(POLICIES + "/search?contract_external_id={contract}", CONTRACT_EXTERNAL_ID)
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error.details[0].field").isEqualTo("query");
        verifyNoInteractions(policies, policySearch, contractViews);
    }

    @Test
    void policyById_acceptingOnlyXml_isNotAcceptable_withoutAskingService() {
        MvcTestResult result = mvc.get().uri(POLICIES + "/{id}", POLICY_ID).accept(MediaType.APPLICATION_XML)
                .exchange();

        assertThat(result).hasStatus(406);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo("NOT_ACCEPTABLE");
        verifyNoInteractions(policies, policySearch, contractViews);
    }

    @Test
    void contractViewById_returnsViewFoundByService_withoutAskingFullPolicy() {
        when(contractViews.byId(POLICY_ID)).thenReturn(contractView());

        MvcTestResult result = mvc.get().uri(POLICIES + "/{id}/contract-view", POLICY_ID).exchange();

        assertThat(result).hasStatusOk().hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(POLICY_ID.toString());
        verifyNoInteractions(policies, policySearch);
    }

    @Test
    void contractViewByExternalId_returnsViewFoundByService_withoutAskingFullPolicy() {
        when(contractViews.byExternalId(POLICY_EXTERNAL_ID)).thenReturn(contractView());

        MvcTestResult result = mvc.get()
                .uri(POLICIES + "/contract-view?external_id={external}", POLICY_EXTERNAL_ID)
                .exchange();

        assertThat(result).hasStatusOk().hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(POLICY_ID.toString());
        verifyNoInteractions(policies, policySearch);
    }

    @Test
    void contractViewWithoutKey_isValidationErrorNamingExternalId_withoutAskingService() {
        MvcTestResult result = mvc.get().uri(POLICIES + "/contract-view").exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo("VALIDATION_ERROR");
        assertThat(result).bodyJson().extractingPath("$.error.details[0].field").isEqualTo("external_id");
        assertThat(result).bodyJson().extractingPath("$.error.details[0].reason").isEqualTo(NO_EXTERNAL_ID_REASON);
        verifyNoInteractions(policies, policySearch, contractViews);
    }

    @Test
    void contractViewWithEmptyExternalId_isMissingValueOfExternalId_withoutAskingService() {
        MvcTestResult result = mvc.get().uri(POLICIES + "/contract-view?external_id=").exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error.details[0].field").isEqualTo("external_id");
        assertThat(result).bodyJson().extractingPath("$.error.details[0].reason").isEqualTo(MISSING_VALUE_REASON);
        verifyNoInteractions(policies, policySearch, contractViews);
    }

    @Test
    void contractViewWithUnreadableId_isValidationErrorNamingId_withoutAskingService() {
        MvcTestResult result = mvc.get().uri(POLICIES + "/{id}/contract-view", "not-a-uuid").exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error.details[0].field").isEqualTo("id");
        verifyNoInteractions(policies, policySearch, contractViews);
    }

    @Test
    void contractViewWithUnreadableExternalId_isValidationErrorNamingExternalId_withoutAskingService() {
        MvcTestResult result = mvc.get().uri(POLICIES + "/contract-view?external_id=not-a-uuid").exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error.details[0].field").isEqualTo("external_id");
        verifyNoInteractions(policies, policySearch, contractViews);
    }

    private static PolicyContractView contractView() {
        return new PolicyContractView(POLICY_ID, POLICY_EXTERNAL_ID, POLICY_NUMBER, LocalDate.of(2026, 1, 1), null,
                false);
    }

    private static PolicyResponse policy() {
        return new PolicyResponse(POLICY_ID, POLICY_EXTERNAL_ID, POLICY_NUMBER, "Фамилия", "Имя", null,
                LocalDate.of(2000, 1, 1), null, null, LocalDate.of(2026, 1, 1), null, true, false, false, null,
                List.of());
    }

    private static PolicySearchResponse found() {
        return new PolicySearchResponse(List.of(
                new PolicySearchResponse.Item(POLICY_ID, POLICY_EXTERNAL_ID, POLICY_NUMBER, "Фамилия", "Имя", null)));
    }
}
