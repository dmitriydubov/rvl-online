package ru.projectx.datareference.repository;

import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.PredicateSpecification;
import ru.projectx.datareference.entity.policy.InsuredAssociationFilter;

public final class InsuredAssociationDimensions {

    private InsuredAssociationDimensions() {
    }

    public static <T> List<PredicateSpecification<T>> constrained(InsuredAssociationFilter filter,
            BiFunction<String, Object, PredicateSpecification<T>> condition) {

        return Stream.of(
                        new Dimension("validFrom", filter.validFrom()),
                        new Dimension("insuredPolicyExternalId", filter.insuredPolicy()),
                        new Dimension("relatedPolicyExternalId", filter.relatedPolicy()),
                        new Dimension("relationTypeExternalId", filter.relationType()))
                .flatMap(dimension -> Stream.ofNullable(dimension.value())
                        .map(value -> condition.apply(dimension.attribute(), value)))
                .toList();
    }

    private record Dimension(String attribute, @Nullable Object value) {
    }
}
