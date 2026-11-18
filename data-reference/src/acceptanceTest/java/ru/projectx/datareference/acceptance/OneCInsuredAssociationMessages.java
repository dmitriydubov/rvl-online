package ru.projectx.datareference.acceptance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.entity.inbound.OneCStream;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class OneCInsuredAssociationMessages {

    static final String REPRESENTATION = "Тестов Пётр Иванович";

    private static final String ROWS_QUEUE = "1c-usk_to_rvl-online_insured-association-changes";
    private static final String FINAL_QUEUE = "1c-usk_to_rvl-online_insured-association-changes-final";
    private static final String OTHER_SIDE = "otherSide";
    private static final String PERIOD = "period";
    private static final String VALUE = "value";
    private static final String INACTIVE = " (неактивен)";
    private static final String FILTER_SEPARATOR = "; ";
    private static final String POLICY_REFERENCE = "CatalogRef.ПолисаЗастрахованных";
    private static final String START_OF_DAY = "T00:00:00+03:00";
    private static final Pattern DATE_WITHOUT_TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Map<String, String> TIME_BY_DATE_KIND = Map.of(
            "момент со смещением", START_OF_DAY,
            "момент со смещением в конце дня", "T23:59:59+03:00",
            "момент без смещения", "T00:00:00",
            "дата", "");
    private static final Map<String, String> SINGLE_ROW = Map.of(
            "застрахованный", "11111111-2222-4333-8444-555555555501",
            "связанный полис", "11111111-2222-4333-8444-555555555502",
            "тип связи", "4b4e0f45-2c50-11e8-8123-005056b43f21",
            "начало", "2026-11-13T00:00:00+03:00",
            "окончание", "3000-01-01T00:00:00+03:00");
    private static final Map<String, String> COLUMN_BY_ROW_FIELD = Map.of(
            "idInsured", "застрахованный",
            "otherSide.value", "связанный полис",
            "relationType", "тип связи");

    private static final JsonMapper JSON = JsonMapper.shared();

    private OneCInsuredAssociationMessages() {
    }

    static OneCMessage rows(UUID messageId, UUID cid, String filter, List<Map<String, String>> rows, int firstRow) {
        ArrayNode recordSet = JSON.createArrayNode();
        for (int index = 0; index < rows.size(); index++) {
            recordSet.add(row(firstRow + index, rows.get(index)));
        }
        ObjectNode data = transfer(cid, filter);
        data.set("recordSet", recordSet);
        return new OneCMessage(OneCStream.INSURED_ASSOCIATIONS, ROWS_QUEUE, data, InboundStatus.PROCESSED, messageId);
    }

    static OneCMessage withoutRowsArray(UUID messageId, UUID cid, String filter, String defect) {
        ObjectNode data = RecordSetDefects.withoutRowsArray(transfer(cid, filter), defect, row(1, SINGLE_ROW));
        return new OneCMessage(OneCStream.INSURED_ASSOCIATIONS, ROWS_QUEUE, data, InboundStatus.FAILED, messageId);
    }

    static OneCMessage finalMessage(UUID messageId, UUID cid, String filter, int messages, int rows) {
        ObjectNode data = transfer(cid, filter).put("countMessages", messages).put("countRows", rows);
        return new OneCMessage(OneCStream.INSURED_ASSOCIATIONS, FINAL_QUEUE, data, InboundStatus.PROCESSED, messageId);
    }

    static Map<String, String> withRowField(Map<String, String> row, String field, String value) {
        String column = Optional.ofNullable(COLUMN_BY_ROW_FIELD.get(field)).orElseThrow(() -> new IllegalArgumentException(
                "поле строки связи — не из " + new TreeSet<>(COLUMN_BY_ROW_FIELD.keySet()) + ": " + field));
        Map<String, String> edited = new HashMap<>(row);
        edited.put(column, value);
        return edited;
    }

    private static ObjectNode transfer(UUID cid, String filter) {
        ObjectNode data = JSON.createObjectNode();
        data.set("filter", filter(filter));
        return data.put("mode", "Замещение").put("cid", cid.toString());
    }

    private static ObjectNode row(int rowNumber, Map<String, String> row) {
        ObjectNode data = JSON.createObjectNode()
                .put("periodTo", dateOf(row, "окончание", "вид окончания"))
                .put("relationType", StepTables.required(row, "тип связи"))
                .put("idInsured", StepTables.required(row, "застрахованный"))
                .put(PERIOD, dateOf(row, "начало", "вид начала"))
                .put("rowNumber", rowNumber);
        data.set(OTHER_SIDE, policyReference(StepTables.required(row, "связанный полис")));
        return data;
    }

    private static String dateOf(Map<String, String> row, String dateColumn, String kindColumn) {
        String date = StepTables.required(row, dateColumn);
        if (!row.containsKey(kindColumn)) {
            return date;
        }
        return date + TIME_BY_DATE_KIND.get(StepTables.oneOf(row, kindColumn, TIME_BY_DATE_KIND.keySet()));
    }

    private static ArrayNode filter(String spec) {
        ArrayNode elements = JSON.createArrayNode();
        for (String element : spec.split(FILTER_SEPARATOR)) {
            elements.add(element(element));
        }
        return elements;
    }

    private static ObjectNode element(String spec) {
        boolean inactive = spec.endsWith(INACTIVE);
        String[] parts = spec.replace(INACTIVE, "").split("=", 2);
        ObjectNode element = JSON.createObjectNode();
        if (OTHER_SIDE.equals(parts[0])) {
            element.set(VALUE, policyReference(parts[1]));
        }
        else {
            element.put(VALUE, filterValue(parts[0], parts[1]));
        }
        return element.put("isActive", !inactive).put("fieldName", parts[0]);
    }

    private static String filterValue(String field, String value) {
        boolean periodWithoutTime = PERIOD.equals(field) && DATE_WITHOUT_TIME.matcher(value).matches();
        return periodWithoutTime ? value + START_OF_DAY : value;
    }

    private static ObjectNode policyReference(String policy) {
        return JSON.createObjectNode().put("type", POLICY_REFERENCE).put("representation", REPRESENTATION)
                .put(VALUE, policy);
    }
}
