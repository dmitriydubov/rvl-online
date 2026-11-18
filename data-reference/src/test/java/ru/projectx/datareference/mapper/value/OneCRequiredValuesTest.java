package ru.projectx.datareference.mapper.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.projectx.datareference.exception.OneCFieldException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class OneCRequiredValuesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"layer\":null}"})
    void absentInteger_isMissingField(String json) {
        assertThatThrownBy(() -> OneCRequiredValues.integer(node(json), "layer"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("нет обязательного поля layer");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"7\"", "7.5", "2.0", "10000000000.5", "true", "{}"})
    void integerOfAnotherType_isRejectedWithoutValue(String value) {
        assertThatThrownBy(() -> OneCRequiredValues.integer(node("{\"layer\":" + value + "}"), "layer"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле layer не является целым числом");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "-2147483649", "10000000000", "123456789012345678901234567890"})
    void integerBeyondInt_isRejectedAsTooLargeWithoutValue(String value) {
        assertThatThrownBy(() -> OneCRequiredValues.integer(node("{\"layer\":" + value + "}"), "layer"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле layer больше допустимого");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483647", "-2147483648"})
    void integerAtIntLimit_isRead(String value) {
        assertThat(OneCRequiredValues.integer(node("{\"layer\":" + value + "}"), "layer"))
                .isEqualTo(Integer.parseInt(value));
    }

    @Test
    void integralNumber_isRead() {
        assertThat(OneCRequiredValues.integer(node("{\"layer\":7}"), "layer")).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"direct\":null}"})
    void absentFlag_isMissingFieldRatherThanFalse(String json) {
        assertThatThrownBy(() -> OneCRequiredValues.flag(node(json), "direct"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("нет обязательного поля direct");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"true\"", "1", "0"})
    void flagOfAnotherType_isRejectedWithoutValue(String value) {
        assertThatThrownBy(() -> OneCRequiredValues.flag(node("{\"direct\":" + value + "}"), "direct"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле direct не является логическим значением");
    }

    @Test
    void booleanFlag_isReadAsSent() {
        assertThat(OneCRequiredValues.flag(node("{\"direct\":true}"), "direct")).isTrue();
        assertThat(OneCRequiredValues.flag(node("{\"direct\":false}"), "direct")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"share\":null}"})
    void absentPercent_isMissingField(String json) {
        assertThatThrownBy(() -> OneCRequiredValues.percent(node(json), "share"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("нет обязательного поля share");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"10.00\"", "true", "[]"})
    void percentOfAnotherType_isRejectedWithoutValue(String value) {
        assertThatThrownBy(() -> OneCRequiredValues.percent(node("{\"share\":" + value + "}"), "share"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле share не является числом");
    }

    @Test
    void percentWithFractionOrInteger_isReadAsSent() {
        assertThat(OneCRequiredValues.percent(node("{\"share\":12.34}"), "share")).isEqualByComparingTo("12.34");
        assertThat(OneCRequiredValues.percent(node("{\"share\":0}"), "share")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "2147483647"})
    void zeroOrPositiveInteger_isReadAsNonNegative(String value) {
        assertThat(OneCRequiredValues.nonNegativeInteger(node("{\"layer\":" + value + "}"), "layer"))
                .isEqualTo(Integer.parseInt(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-2147483648"})
    void negativeInteger_isRejectedWithoutValue(String value) {
        assertThatThrownBy(() -> OneCRequiredValues.nonNegativeInteger(node("{\"layer\":" + value + "}"), "layer"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле layer отрицательное");
    }

    @Test
    void nonNegativeInteger_keepsTheTypeRule() {
        assertThatThrownBy(() -> OneCRequiredValues.nonNegativeInteger(node("{\"layer\":\"1\"}"), "layer"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле layer не является целым числом");
    }

    @Test
    void negativeIntegerBeyondInt_isRejectedAsTooLargeBeforeTheSign() {
        assertThatThrownBy(() -> OneCRequiredValues.nonNegativeInteger(node("{\"layer\":-10000000000}"), "layer"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле layer больше допустимого");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "0.01", "55.5", "99.99", "100", "100.00"})
    void percentFromZeroToHundred_isReadAsSent(String value) {
        assertThat(OneCRequiredValues.percent(node("{\"share\":" + value + "}"), "share"))
                .isEqualByComparingTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "-1", "100.01", "101", "1000"})
    void percentOutsideZeroToHundred_isRejectedWithoutValue(String value) {
        assertThatThrownBy(() -> OneCRequiredValues.percent(node("{\"share\":" + value + "}"), "share"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле share вне диапазона от 0 до 100");
    }

    @Test
    void percent_keepsTheTypeRule() {
        assertThatThrownBy(() -> OneCRequiredValues.percent(node("{\"share\":\"10\"}"), "share"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле share не является числом");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"recordSet\":null}"})
    void absentArray_isMissingFieldRatherThanEmpty(String json) {
        assertThatThrownBy(() -> OneCRequiredValues.array(node(json), "recordSet"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("нет обязательного поля recordSet");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"rowNumber\":1}", "{}", "\"[]\"", "0", "true"})
    void arrayOfAnotherType_isRejectedWithoutValue(String value) {
        assertThatThrownBy(() -> OneCRequiredValues.array(node("{\"recordSet\":" + value + "}"), "recordSet"))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле recordSet не является массивом");
    }

    @Test
    void array_isReadWithItsElementsInSourceOrder() {
        ObjectNode data = node("{\"recordSet\":[{\"rowNumber\":2},{\"rowNumber\":1}]}");

        assertThat(OneCRequiredValues.array(data, "recordSet"))
                .containsExactly(data.path("recordSet").get(0), data.path("recordSet").get(1));
    }

    @Test
    void emptyArray_isReadAsArrayWithoutElements() {
        assertThat(OneCRequiredValues.array(node("{\"recordSet\":[]}"), "recordSet")).isEmpty();
    }

    private static ObjectNode node(String json) {
        return (ObjectNode) JSON.readTree(json);
    }
}
