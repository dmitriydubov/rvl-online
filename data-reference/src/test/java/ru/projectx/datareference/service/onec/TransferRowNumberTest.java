package ru.projectx.datareference.service.onec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.projectx.datareference.exception.OneCFieldException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TransferRowNumberTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"1", "0", "-3", "2147483647"})
    void integralNumber_isTheRowNumber(String number) {
        assertThat(TransferRowNumber.of(row("{\"rowNumber\":" + number + "}"))).isEqualTo(Integer.parseInt(number));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"rowNumber\":null}"})
    void absentRowNumber_isMissingField(String row) {
        assertThatThrownBy(() -> TransferRowNumber.of(row(row)))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("нет обязательного поля rowNumber");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"вторая\"", "\"2\"", "2.5", "2.0", "10000000000.5", "true"})
    void nonIntegralRowNumber_isNotAnInteger(String value) {
        assertThatThrownBy(() -> TransferRowNumber.of(row("{\"rowNumber\":" + value + "}")))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле rowNumber не является целым числом");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "10000000000", "-2147483649"})
    void rowNumberBeyondInt_isTooLarge(String value) {
        assertThatThrownBy(() -> TransferRowNumber.of(row("{\"rowNumber\":" + value + "}")))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле rowNumber больше допустимого");
    }

    @Test
    void otherFieldsOfTheRow_doNotMatter() {
        assertThat(TransferRowNumber.of(row("{\"price\":\"много\",\"rowNumber\":7}"))).isEqualTo(7);
    }

    private static JsonNode row(String text) {
        return JSON.readTree(text);
    }
}
