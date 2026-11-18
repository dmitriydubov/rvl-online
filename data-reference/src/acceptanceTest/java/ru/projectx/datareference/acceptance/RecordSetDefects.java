package ru.projectx.datareference.acceptance;

import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import tools.jackson.databind.node.ObjectNode;

final class RecordSetDefects {

    private static final String RECORD_SET = "recordSet";

    private static final Map<String, BiConsumer<ObjectNode, ObjectNode>> DEFECTS = Map.of(
            "нет поля recordSet", (data, row) -> data.remove(RECORD_SET),
            "recordSet — null", (data, row) -> data.putNull(RECORD_SET),
            "recordSet — объект", (data, row) -> data.set(RECORD_SET, row),
            "recordSet — строка", (data, row) -> data.put(RECORD_SET, "[]"));

    private RecordSetDefects() {
    }

    static ObjectNode withoutRowsArray(ObjectNode data, String defect, ObjectNode singleRow) {
        Optional.ofNullable(DEFECTS.get(defect))
                .orElseThrow(() -> new IllegalArgumentException(
                        "дефект массива строк — не из " + new TreeSet<>(DEFECTS.keySet()) + ": " + defect))
                .accept(data, singleRow);
        return data;
    }
}
