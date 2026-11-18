package ru.projectx.datareference.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.PredicateSpecification;
import ru.projectx.datareference.entity.policy.InsuredAssociationFilter;

class InsuredAssociationDimensionsTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 13);
    private static final UUID INSURED = UUID.fromString("11111111-2222-4333-8444-555555555501");
    private static final UUID RELATED = UUID.fromString("11111111-2222-4333-8444-555555555502");
    private static final UUID TYPE = UUID.fromString("797093be-2c51-11e8-8123-005056b43f21");

    private final List<String> constrained = new ArrayList<>();
    private final List<PredicateSpecification<Object>> made = new ArrayList<>();

    @Test
    void everyDimensionOfAFullFilter_isConstrainedByItsAttributeAndValue() {
        List<PredicateSpecification<Object>> conditions = InsuredAssociationDimensions.constrained(
                new InsuredAssociationFilter(START, INSURED, RELATED, TYPE), this::condition);

        assertThat(conditions).hasSize(4);
        assertThat(constrained).containsExactly("validFrom=" + START, "insuredPolicyExternalId=" + INSURED,
                "relatedPolicyExternalId=" + RELATED, "relationTypeExternalId=" + TYPE);
    }

    @Test
    void dimensionsMissingFromTheFilter_areNotConstrained() {
        List<PredicateSpecification<Object>> conditions = InsuredAssociationDimensions.constrained(
                new InsuredAssociationFilter(null, INSURED, null, null), this::condition);

        assertThat(conditions).hasSize(1);
        assertThat(constrained).containsExactly("insuredPolicyExternalId=" + INSURED);
    }

    @Test
    void constrainedDimensions_areTheConditionsMadeForThem_inTheOrderOfTheDimensions() {
        List<PredicateSpecification<Object>> conditions = InsuredAssociationDimensions.constrained(
                new InsuredAssociationFilter(START, null, RELATED, TYPE), this::condition);

        assertThat(made).hasSize(3);
        assertThat(conditions).containsExactlyElementsOf(made);
    }

    private PredicateSpecification<Object> condition(String attribute, Object value) {
        constrained.add(attribute + "=" + value);
        PredicateSpecification<Object> condition = (from, builder) -> builder.equal(from.get(attribute), value);
        made.add(condition);
        return condition;
    }
}
