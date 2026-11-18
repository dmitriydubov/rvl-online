package ru.projectx.datareference.mapper.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.projectx.datareference.exception.OneCFieldException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class OneCNamedValuesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void labeledRequiredGuid_isReadFromItsField() {
        ObjectNode otherSide = node("{\"value\":\"F3876C53-C7D8-4620-B364-9538164BA5D4\"}");

        assertThat(OneCNamedValues.requiredUuid(otherSide, "value", "otherSide.value"))
                .isEqualTo(UUID.fromString("f3876c53-c7d8-4620-b364-9538164ba5d4"));
    }

    @Test
    void labeledRequiredGuid_notAGuid_namesTheLabelWithoutTheValue() {
        assertThatThrownBy(() -> OneCNamedValues.requiredUuid(node("{\"value\":\"1-2-3-4-5\"}"), "value", "otherSide.value"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле otherSide.value не является GUID");
    }

    @Test
    void labeledRequiredGuid_zeroGuid_isMissingUnderTheLabel() {
        ObjectNode otherSide = node("{\"value\":\"00000000-0000-0000-0000-000000000000\"}");

        assertThatThrownBy(() -> OneCNamedValues.requiredUuid(otherSide, "value", "otherSide.value"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("нет обязательного GUID в поле otherSide.value");
    }

    @Test
    void labeledRequiredDate_isReadFromItsField() {
        ObjectNode element = node("{\"value\":\"2026-11-13T00:00:00+03:00\"}");

        assertThat(OneCNamedValues.requiredDate(element, "value", "filter.period")).isEqualTo(LocalDate.of(2026, 11, 13));
    }

    @Test
    void labeledRequiredDate_notADate_namesTheLabel() {
        assertThatThrownBy(() -> OneCNamedValues.requiredDate(node("{\"value\":\"13.11.2026\"}"), "value", "filter.period"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле filter.period не является датой");
    }

    @Test
    void labeledRequiredDate_openDate_isMissingUnderTheLabel() {
        assertThatThrownBy(() -> OneCNamedValues.requiredDate(node("{\"value\":\"3000-01-01\"}"), "value", "filter.period"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("нет обязательной даты в поле filter.period");
    }

    @Test
    void missingField_isMissingUnderTheName() {
        assertThatThrownBy(() -> OneCNamedValues.requiredUuid(node("{}"), "value", "otherSide.value"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("нет обязательного GUID в поле otherSide.value");
    }

    private static ObjectNode node(String json) {
        return (ObjectNode) JSON.readTree(json);
    }
}
