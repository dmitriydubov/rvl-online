package ru.projectx.datareference.support;

import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

public final class InsuredAssociationData {

    public static final UUID INSURED = UUID.fromString("11111111-2222-4333-8444-555555555501");
    public static final UUID RELATED = UUID.fromString("11111111-2222-4333-8444-555555555502");
    public static final UUID OTHER_RELATED = UUID.fromString("11111111-2222-4333-8444-555555555503");
    public static final UUID RELATION_TYPE = UUID.fromString("797093be-2c51-11e8-8123-005056b43f21");
    public static final UUID OTHER_RELATION_TYPE = UUID.fromString("4b4e0f45-2c50-11e8-8123-005056b43f21");
    public static final String REPRESENTATION = "Тестов Пётр Иванович";

    private InsuredAssociationData() {
    }

    public static String row(int rowNumber, UUID insured, UUID related, UUID relationType, String period,
            String periodTo) {
        return """
                {"rowNumber":%d,"idInsured":"%s","otherSide":%s,"relationType":"%s",\
                "period":"%s","periodTo":"%s"}"""
                .formatted(rowNumber, insured, otherSide(related.toString()), relationType, period, periodTo);
    }

    public static String otherSide(String value) {
        return """
                {"type":"CatalogRef.ПолисаЗастрахованных","representation":"%s","value":"%s"}"""
                .formatted(REPRESENTATION, value);
    }

    public static String element(String fieldName, String value, boolean active) {
        String json = "otherSide".equals(fieldName) ? otherSide(value) : "\"" + value + "\"";
        return "{\"value\":%s,\"isActive\":%s,\"fieldName\":\"%s\"}".formatted(json, active, fieldName);
    }

    public static String filter(String... elements) {
        return "[" + String.join(",", elements) + "]";
    }

    public static String byInsured(UUID insured) {
        return filter(element("idInsured", insured.toString(), true));
    }

    public static String batch(UUID cid, String filter, String... rows) {
        return "{\"recordSet\":[%s],\"filter\":%s,\"mode\":\"Замещение\",\"cid\":\"%s\"}"
                .formatted(Arrays.stream(rows).collect(Collectors.joining(",")), filter, cid);
    }

    public static String finalPart(UUID cid, String filter, int countMessages, int countRows) {
        return "{\"countMessages\":%d,\"countRows\":%d,\"filter\":%s,\"mode\":\"Замещение\",\"cid\":\"%s\"}"
                .formatted(countMessages, countRows, filter, cid);
    }
}
