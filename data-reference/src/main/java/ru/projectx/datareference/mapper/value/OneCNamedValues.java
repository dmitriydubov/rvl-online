package ru.projectx.datareference.mapper.value;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class OneCNamedValues {

    private OneCNamedValues() {
    }

    public static UUID requiredUuid(JsonNode data, String field, String name) {
        return OneCValues.requiredUuid(named(data, field, name), name);
    }

    public static LocalDate requiredDate(JsonNode data, String field, String name) {
        return OneCValues.requiredDate(named(data, field, name), name);
    }

    private static ObjectNode named(JsonNode data, String field, String name) {
        ObjectNode named = JsonNodeFactory.instance.objectNode();
        Optional.ofNullable(data.get(field)).ifPresent(value -> named.set(name, value));
        return named;
    }
}
