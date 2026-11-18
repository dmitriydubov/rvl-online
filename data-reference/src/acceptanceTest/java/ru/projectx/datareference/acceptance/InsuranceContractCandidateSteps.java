package ru.projectx.datareference.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.ru.Тогда;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

public class InsuranceContractCandidateSteps {

    private static final String CONTRACT_ID_BY_EXTERNAL_ID =
            "select cast(id as varchar) from insurance_contracts where external_id = :externalId";
    private static final String POLICY_ID_BY_EXTERNAL_ID =
            "select cast(id as varchar) from policies where external_id = :externalId";

    private final ContractsService contracts;
    private final JdbcClient jdbc;

    public InsuranceContractCandidateSteps(ContractsService contracts, JdbcClient jdbc) {
        this.contracts = contracts;
        this.jdbc = jdbc;
    }

    @Тогда("поле {string} элементов {string} ответа — id договоров страхования {string} и {string} в любом порядке")
    public void candidatesAreInsuranceContracts(String field, String list, String first, String second) {
        assertCandidates(field, list, ourId(CONTRACT_ID_BY_EXTERNAL_ID, first), ourId(CONTRACT_ID_BY_EXTERNAL_ID, second));
    }

    @Тогда("поле {string} элементов {string} ответа — id полисов {string} и {string} в любом порядке")
    public void candidatesArePolicies(String field, String list, String first, String second) {
        assertCandidates(field, list, ourId(POLICY_ID_BY_EXTERNAL_ID, first), ourId(POLICY_ID_BY_EXTERNAL_ID, second));
    }

    @Тогда("элементы {string} ответа — полис {string} с договором {string} и полис {string} с договором {string} в любом порядке")
    public void candidatesArePoliciesWithContracts(
            String list, String firstPolicy, String firstContract, String secondPolicy, String secondContract) {

        JsonNode elements = contracts.lastResponse().field(list);
        assertThat(elements.isArray()).as("поле %s — список: %s", list, elements).isTrue();

        List<List<String>> candidates = elements.valueStream()
                .map(element -> List.of(element.path("policy_id").asString(),
                        element.path("contract_external_id").asString()))
                .toList();

        assertThat(candidates).as("полисы и договоры элементов %s", list).containsExactlyInAnyOrder(
                List.of(ourId(POLICY_ID_BY_EXTERNAL_ID, firstPolicy), firstContract),
                List.of(ourId(POLICY_ID_BY_EXTERNAL_ID, secondPolicy), secondContract));
    }

    private void assertCandidates(String field, String list, String first, String second) {
        JsonNode elements = contracts.lastResponse().field(list);
        assertThat(elements.isArray()).as("поле %s — список: %s", list, elements).isTrue();

        List<String> candidates = elements.valueStream().map(element -> element.path(field).asString()).toList();

        assertThat(candidates).as("поле %s элементов %s", field, list)
                .containsExactlyInAnyOrder(first, second);
    }

    private String ourId(String query, String externalId) {
        return jdbc.sql(query)
                .param("externalId", UUID.fromString(externalId))
                .query(String.class)
                .single();
    }
}
