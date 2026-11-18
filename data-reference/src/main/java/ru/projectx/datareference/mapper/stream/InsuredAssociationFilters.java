package ru.projectx.datareference.mapper.stream;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import ru.projectx.datareference.entity.policy.InsuredAssociationFilter;
import ru.projectx.datareference.exception.OneCFieldException;
import ru.projectx.datareference.mapper.value.OneCNamedValues;
import ru.projectx.datareference.mapper.value.OneCValues;
import tools.jackson.databind.JsonNode;

final class InsuredAssociationFilters {

    private static final String VALUE = "value";

    private InsuredAssociationFilters() {
    }

    static InsuredAssociationFilter of(JsonNode data) {
        Map<Dimension, JsonNode> active = activeElements(data.path("filter"));
        InsuredAssociationFilter filter = new InsuredAssociationFilter(
                valueOf(active, Dimension.PERIOD,
                        element -> OneCNamedValues.requiredDate(element, VALUE, "filter.period")),
                valueOf(active, Dimension.INSURED,
                        element -> OneCNamedValues.requiredUuid(element, VALUE, "filter.idInsured")),
                valueOf(active, Dimension.OTHER_SIDE,
                        element -> OneCNamedValues.requiredUuid(element.path(VALUE), VALUE, "filter.otherSide.value")),
                valueOf(active, Dimension.RELATION_TYPE,
                        element -> OneCNamedValues.requiredUuid(element, VALUE, "filter.relationType")));
        if (filter.isEmpty()) {
            throw new OneCFieldException("в отборе передачи нет ни одного действующего элемента");
        }
        return filter;
    }

    private static Map<Dimension, JsonNode> activeElements(JsonNode filter) {
        return StreamSupport.stream(filter.spliterator(), false)
                .filter(InsuredAssociationFilters::isActive)
                .collect(Collectors.toMap(element -> Dimension.of(OneCValues.requiredText(element, "fieldName")),
                        Function.identity(), (first, repeated) -> {
                            throw new OneCFieldException("в отборе передачи одно поле задано дважды");
                        }, () -> new EnumMap<>(Dimension.class)));
    }

    private static boolean isActive(JsonNode element) {
        JsonNode active = element.path("isActive");
        if (!active.isBoolean()) {
            throw new OneCFieldException("в отборе передачи у элемента нет признака isActive");
        }
        return active.booleanValue();
    }

    private static <T> @Nullable T valueOf(Map<Dimension, JsonNode> active, Dimension dimension,
            Function<JsonNode, T> reader) {
        return Optional.ofNullable(active.get(dimension)).map(reader).orElse(null);
    }

    private enum Dimension {

        PERIOD("period"),
        INSURED("idInsured"),
        OTHER_SIDE("otherSide"),
        RELATION_TYPE("relationType");

        private final String fieldName;

        Dimension(String fieldName) {
            this.fieldName = fieldName;
        }

        static Dimension of(String fieldName) {
            return Arrays.stream(values())
                    .filter(dimension -> dimension.fieldName.equals(fieldName))
                    .findFirst()
                    .orElseThrow(() -> new OneCFieldException("в отборе передачи неизвестное поле"));
        }
    }
}
