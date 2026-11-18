package ru.projectx.datareference.mapper.stream;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.projectx.datareference.entity.inbound.AwaitedParent;
import ru.projectx.datareference.entity.inbound.ParentKind;
import ru.projectx.datareference.entity.policy.InsuredAssociation;
import ru.projectx.datareference.entity.policy.InsuredAssociationFilter;
import ru.projectx.datareference.mapper.ImportResult;
import ru.projectx.datareference.repository.policy.InsuredAssociationRepository;
import ru.projectx.datareference.repository.policy.PolicyRepository;
import tools.jackson.databind.JsonNode;

@Component
public class InsuredAssociationMapper {

    private static final Logger log = LoggerFactory.getLogger(InsuredAssociationMapper.class);

    private final InsuredAssociationRepository associations;
    private final PolicyRepository policies;
    private final Clock clock;

    public InsuredAssociationMapper(InsuredAssociationRepository associations, PolicyRepository policies, Clock clock) {
        this.associations = associations;
        this.policies = policies;
        this.clock = clock;
    }

    public void checkRow(JsonNode row) {
        InsuredAssociationRows.rowOf(row);
    }

    public InsuredAssociationFilter filterOf(JsonNode data) {
        return InsuredAssociationFilters.of(data);
    }

    public ImportResult apply(InsuredAssociationFilter filter, List<JsonNode> recordSet) {
        List<InsuredAssociation.Row> rows = recordSet.stream().map(InsuredAssociationRows::rowOf).toList();
        InsuredAssociationRows.requireConsistent(filter, rows);
        return missingPolicy(rows)
                .map(policy -> ImportResult.awaitingParent(new AwaitedParent(ParentKind.POLICY, policy)))
                .orElseGet(() -> replace(filter, rows));
    }

    private Optional<UUID> missingPolicy(List<InsuredAssociation.Row> rows) {
        return rows.stream()
                .flatMap(row -> row.policies().stream())
                .filter(policy -> !policies.existsByExternalId(policy))
                .findFirst();
    }

    private ImportResult replace(InsuredAssociationFilter filter, List<InsuredAssociation.Row> rows) {
        Instant now = clock.instant();
        long removed = associations.deleteCoveredBy(filter);
        associations.saveAll(rows.stream().map(row -> InsuredAssociation.of(row, now)).toList());
        log.debug("связи застрахованных по отбору передачи: снято записей {}, записано {}", removed, rows.size());
        return ImportResult.applied();
    }
}
