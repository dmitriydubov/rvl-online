package ru.projectx.datareference.repository.inbound;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.domain.PredicateSpecification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import ru.projectx.datareference.entity.inbound.InsuredAssociationFinalization;
import ru.projectx.datareference.entity.policy.InsuredAssociationFilter;
import ru.projectx.datareference.repository.InsuredAssociationDimensions;

@Repository
public interface InsuredAssociationFinalizationRepository extends JpaRepository<InsuredAssociationFinalization, UUID>,
        JpaSpecificationExecutor<InsuredAssociationFinalization> {

    default Optional<UUID> findNewerOverlapping(InsuredAssociationFilter filter, Instant finalReceivedAt) {
        List<PredicateSpecification<InsuredAssociationFinalization>> conditions = new ArrayList<>(
                InsuredAssociationDimensions.<InsuredAssociationFinalization>constrained(filter,
                        (attribute, value) -> (from, builder) -> builder.or(
                                builder.isNull(from.get(attribute)), builder.equal(from.get(attribute), value))));
        conditions.add((from, builder) -> builder.greaterThan(
                from.join("finalMessage").get("receivedAt"), finalReceivedAt));
        return findAll(PredicateSpecification.allOf(conditions)).stream()
                .map(finalization -> Objects.requireNonNull(finalization.finalMessage().transferPart().correlationId()))
                .findFirst();
    }
}
