package ru.projectx.datareference.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.ru.Тогда;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

public class InsuredRelativesSteps {

    private static final String NONE = "нет";
    private static final Pattern LIST = Pattern.compile("\\s*,\\s*");

    private final ContractsService contracts;

    public InsuredRelativesSteps(ContractsService contracts) {
        this.contracts = contracts;
    }

    @Тогда("полисы родственников в ответе по порядку — {string}")
    public void relativePolicyNumbersInOrder(String numbers) {
        assertThat(itemValues("number")).as("номера полисов родственников").containsExactlyElementsOf(listed(numbers));
    }

    @Тогда("поле {string} ответа состоит ровно из полей {string}")
    public void fieldHasExactlyFields(String field, String fields) {
        JsonNode node = contracts.lastResponse().field(field);
        assertThat(node.isObject()).as("поле %s — объект JSON: %s", field, node).isTrue();
        assertThat(node.propertyNames()).as("поля объекта %s", field)
                .containsExactlyInAnyOrder(LIST.split(fields.strip()));
    }

    private List<String> itemValues(String pointer) {
        ServiceResponse response = contracts.lastResponse();
        assertThat(response.status()).as("статус ответа: %s", response.body()).isEqualTo(200);
        JsonNode items = response.field("items");
        assertThat(items.isArray()).as("поле items — список: %s", response.body()).isTrue();
        return items.valueStream().map(item -> item.at("/" + pointer).asString()).toList();
    }

    private static List<String> listed(String values) {
        return NONE.equals(values.strip()) ? List.of() : List.of(LIST.split(values.strip()));
    }
}
