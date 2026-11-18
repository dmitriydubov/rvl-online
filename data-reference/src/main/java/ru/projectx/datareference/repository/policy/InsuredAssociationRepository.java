package ru.projectx.datareference.repository.policy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.PredicateSpecification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import ru.projectx.datareference.entity.policy.InsuredAssociation;
import ru.projectx.datareference.entity.policy.InsuredAssociationFilter;
import ru.projectx.datareference.repository.InsuredAssociationDimensions;

@Repository
public interface InsuredAssociationRepository
        extends JpaRepository<InsuredAssociation, UUID>, JpaSpecificationExecutor<InsuredAssociation> {

    List<InsuredAssociation> findAllByInsuredPolicyExternalId(UUID insuredPolicyExternalId);

    default long deleteCoveredBy(InsuredAssociationFilter filter) {
        if (filter.isEmpty()) {
            throw new IllegalArgumentException("удаление связей без отбора удалило бы все связи");
        }
        return delete(PredicateSpecification.allOf(InsuredAssociationDimensions.<InsuredAssociation>constrained(filter,
                (attribute, value) -> (from, builder) -> builder.equal(from.get(attribute), value))));
    }
}
