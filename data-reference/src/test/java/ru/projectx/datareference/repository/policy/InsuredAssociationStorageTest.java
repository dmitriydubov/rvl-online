package ru.projectx.datareference.repository.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.projectx.datareference.entity.policy.InsuredAssociation;
import ru.projectx.datareference.entity.policy.InsuredAssociationFilter;
import ru.projectx.datareference.support.DatabaseCleaner;
import ru.projectx.datareference.support.IntegrationTest;

@IntegrationTest
class InsuredAssociationStorageTest {

    private static final Instant NOW = Instant.parse("2026-11-15T09:00:00Z");
    private static final LocalDate JANUARY = LocalDate.of(2026, 1, 1);
    private static final LocalDate NOVEMBER = LocalDate.of(2026, 11, 13);
    private static final UUID INSURED = UUID.fromString("11111111-2222-4333-8444-555555555501");
    private static final UUID RELATED = UUID.fromString("11111111-2222-4333-8444-555555555502");
    private static final UUID OTHER_RELATED = UUID.fromString("11111111-2222-4333-8444-555555555503");
    private static final UUID OTHER_INSURED = UUID.fromString("11111111-2222-4333-8444-555555555504");
    private static final UUID TYPE = UUID.fromString("797093be-2c51-11e8-8123-005056b43f21");
    private static final UUID OTHER_TYPE = UUID.fromString("4b4e0f45-2c50-11e8-8123-005056b43f21");

    @Autowired
    private InsuredAssociationRepository associations;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void storeAssociations() {
        databaseCleaner.clear();
        associations.saveAll(List.of(
                association(INSURED, RELATED, TYPE, JANUARY),
                association(INSURED, RELATED, TYPE, NOVEMBER),
                association(INSURED, OTHER_RELATED, OTHER_TYPE, NOVEMBER),
                association(OTHER_INSURED, RELATED, TYPE, JANUARY)));
    }

    @Test
    void filterByOneDimension_removesEveryAssociationOfThatValueOnly() {
        long removed = deleteCoveredBy(new InsuredAssociationFilter(null, INSURED, null, null));

        assertThat(removed).isEqualTo(3);
        assertThat(remaining()).containsExactly(association(OTHER_INSURED, RELATED, TYPE, JANUARY).row());
    }

    @Test
    void fullFilter_removesExactlyOneRecord() {
        long removed = deleteCoveredBy(new InsuredAssociationFilter(NOVEMBER, INSURED, RELATED, TYPE));

        assertThat(removed).isEqualTo(1);
        assertThat(remaining()).hasSize(3).noneMatch(row -> row.equals(
                new InsuredAssociation.Row(INSURED, RELATED, TYPE, NOVEMBER, null)));
    }

    @Test
    void filterOfStartAndRelatedPolicy_combinesItsConditions() {
        long removed = deleteCoveredBy(new InsuredAssociationFilter(JANUARY, null, RELATED, null));

        assertThat(removed).isEqualTo(2);
        assertThat(remaining()).extracting(InsuredAssociation.Row::validFrom).containsOnly(NOVEMBER);
    }

    @Test
    void emptyFilter_isRefused_insteadOfRemovingEverything() {
        assertThatThrownBy(() -> deleteCoveredBy(new InsuredAssociationFilter(null, null, null, null)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
        assertThat(remaining()).hasSize(4);
    }

    private long deleteCoveredBy(InsuredAssociationFilter filter) {
        return new TransactionTemplate(transactions).execute(status -> associations.deleteCoveredBy(filter));
    }

    private List<InsuredAssociation.Row> remaining() {
        return associations.findAll().stream().map(InsuredAssociation::row).toList();
    }

    private static InsuredAssociation association(UUID insured, UUID related, UUID type, LocalDate start) {
        return InsuredAssociation.of(new InsuredAssociation.Row(insured, related, type, start, null), NOW);
    }
}
