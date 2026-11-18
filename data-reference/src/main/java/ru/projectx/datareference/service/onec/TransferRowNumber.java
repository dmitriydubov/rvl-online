package ru.projectx.datareference.service.onec;

import ru.projectx.datareference.mapper.value.OneCRequiredValues;
import tools.jackson.databind.JsonNode;

final class TransferRowNumber {

    private static final String ROW_NUMBER = "rowNumber";

    private TransferRowNumber() {
    }

    static int of(JsonNode row) {
        return OneCRequiredValues.integer(row, ROW_NUMBER);
    }
}
