package ru.projectx.datareference.mapper.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.projectx.datareference.support.InsuredAssociationData.INSURED;
import static ru.projectx.datareference.support.InsuredAssociationData.RELATED;
import static ru.projectx.datareference.support.InsuredAssociationData.RELATION_TYPE;
import static ru.projectx.datareference.support.InsuredAssociationData.element;
import static ru.projectx.datareference.support.InsuredAssociationData.filter;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.projectx.datareference.entity.policy.InsuredAssociationFilter;
import ru.projectx.datareference.exception.OneCFieldException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class InsuredAssociationFiltersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void activeElements_constrainTheirDimensions_otherSideByItsPolicy() {
        JsonNode data = data(filter(
                element("period", "2026-11-13T00:00:00+03:00", true),
                element("idInsured", INSURED.toString(), true),
                element("otherSide", RELATED.toString(), true),
                element("relationType", RELATION_TYPE.toString(), true)));

        assertThat(InsuredAssociationFilters.of(data))
                .isEqualTo(new InsuredAssociationFilter(LocalDate.of(2026, 11, 13), INSURED, RELATED, RELATION_TYPE));
    }

    @Test
    void inactiveElements_doNotConstrain() {
        JsonNode data = data(filter(
                element("idInsured", INSURED.toString(), true),
                element("otherSide", RELATED.toString(), false),
                element("anotherDimension", "что угодно", false)));

        assertThat(InsuredAssociationFilters.of(data)).isEqualTo(new InsuredAssociationFilter(null, INSURED, null, null));
    }

    @Test
    void filterWithoutActiveElements_isRejected_ratherThanReplacingEveryAssociation() {
        JsonNode data = data(filter(element("idInsured", INSURED.toString(), false)));

        assertThatThrownBy(() -> InsuredAssociationFilters.of(data))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("в отборе передачи нет ни одного действующего элемента");
    }

    @Test
    void messageWithoutFilter_isRejected() {
        assertThatThrownBy(() -> InsuredAssociationFilters.of(JSON.readTree("{\"cid\":\"x\"}")))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("в отборе передачи нет ни одного действующего элемента");
    }

    @Test
    void unknownActiveElement_isRejected_withoutQuotingTheName() {
        JsonNode data = data(filter(element("idInsured", INSURED.toString(), true),
                element("anotherDimension", "значение", true)));

        assertThatThrownBy(() -> InsuredAssociationFilters.of(data))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("в отборе передачи неизвестное поле");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {"value":"11111111-2222-4333-8444-555555555501","fieldName":"idInsured"}
            {"value":"11111111-2222-4333-8444-555555555501","isActive":"true","fieldName":"idInsured"}
            {"value":"11111111-2222-4333-8444-555555555501","isActive":1,"fieldName":"idInsured"}
            """)
    void elementWithoutBooleanActivity_isRejected_ratherThanWideningTheFilter(String element) {
        JsonNode data = data(filter(element("idInsured", INSURED.toString(), true), element));

        assertThatThrownBy(() -> InsuredAssociationFilters.of(data))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("в отборе передачи у элемента нет признака isActive");
    }

    @Test
    void repeatedActiveElement_isRejected() {
        JsonNode data = data(filter(element("idInsured", INSURED.toString(), true),
                element("idInsured", RELATED.toString(), true)));

        assertThatThrownBy(() -> InsuredAssociationFilters.of(data))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("в отборе передачи одно поле задано дважды");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            period       | 13.11.2026 | поле filter.period не является датой
            idInsured    | 1-2-3-4-5  | поле filter.idInsured не является GUID
            otherSide    | 1-2-3-4-5  | поле filter.otherSide.value не является GUID
            relationType | 1-2-3-4-5  | поле filter.relationType не является GUID
            """)
    void brokenElementValue_isRejected_namingTheElementOnly(String field, String value, String reason) {
        JsonNode data = data(filter(element(field, value, true)));

        assertThatThrownBy(() -> InsuredAssociationFilters.of(data))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage(reason);
    }

    private static JsonNode data(String filter) {
        return JSON.readTree("{\"filter\":%s}".formatted(filter));
    }
}
