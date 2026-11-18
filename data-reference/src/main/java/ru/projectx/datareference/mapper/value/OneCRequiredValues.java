package ru.projectx.datareference.mapper.value;

import java.math.BigDecimal;
import ru.projectx.datareference.exception.OneCFieldException;
import tools.jackson.databind.JsonNode;

public final class OneCRequiredValues {

    private static final String NO_REQUIRED_FIELD_MESSAGE = "нет обязательного поля ";
    private static final BigDecimal FULL_PERCENT = BigDecimal.valueOf(100);

    private OneCRequiredValues() {
    }

    public static int integer(JsonNode data, String field) {
        requirePresent(data, field);
        return OneCInteger.read(data, field).orElseThrow(() -> wrongType(field, "целым числом"));
    }

    public static int nonNegativeInteger(JsonNode data, String field) {
        int value = integer(data, field);
        if (value < 0) {
            throw new OneCFieldException("поле %s отрицательное".formatted(field));
        }
        return value;
    }

    public static boolean flag(JsonNode data, String field) {
        return requirePresent(data, field).booleanValueOpt()
                .orElseThrow(() -> wrongType(field, "логическим значением"));
    }

    public static JsonNode array(JsonNode data, String field) {
        JsonNode value = requirePresent(data, field);
        if (!value.isArray()) {
            throw wrongType(field, "массивом");
        }
        return value;
    }

    public static BigDecimal percent(JsonNode data, String field) {
        requirePresent(data, field);
        BigDecimal value = OneCMoneyScale.read(data, field).orElseThrow(() -> wrongType(field, "числом"));
        if (value.signum() < 0 || value.compareTo(FULL_PERCENT) > 0) {
            throw new OneCFieldException("поле %s вне диапазона от 0 до %s".formatted(field, FULL_PERCENT));
        }
        return value;
    }

    private static JsonNode requirePresent(JsonNode data, String field) {
        JsonNode value = data.path(field);
        if (value.isMissingNode() || value.isNull()) {
            throw new OneCFieldException(NO_REQUIRED_FIELD_MESSAGE + field);
        }
        return value;
    }

    private static OneCFieldException wrongType(String field, String expectedType) {
        return new OneCFieldException("поле %s не является %s".formatted(field, expectedType));
    }
}
