package ru.projectx.datareference.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.projectx.datareference.support.FixedClockConfig.NOW;

import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import ru.projectx.datareference.entity.policy.InsuredAssociation;
import ru.projectx.datareference.entity.policy.Policy;
import ru.projectx.datareference.repository.policy.InsuredAssociationRepository;
import ru.projectx.datareference.repository.policy.PolicyRepository;
import ru.projectx.datareference.support.DatabaseCleaner;
import ru.projectx.datareference.support.IntegrationTest;

@IntegrationTest
class FamilyApiTest {

    private static final String FAMILY = InternalApi.BASE_PATH + "/policies/{id}/family";
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 14);
    private static final UUID CONTRACT = UUID.fromString("5a1e0000-0000-4000-8000-0000000000c1");
    private static final UUID INSURED = UUID.fromString("5a1e0000-0000-4000-8000-000000000001");
    private static final UUID SPOUSE = UUID.fromString("5a1e0000-0000-4000-8000-000000000002");
    private static final UUID CHILD = UUID.fromString("5a1e0000-0000-4000-8000-000000000003");
    private static final UUID PARENT = UUID.fromString("5a1e0000-0000-4000-8000-000000000004");
    private static final UUID ARCHIVED = UUID.fromString("5a1e0000-0000-4000-8000-000000000005");
    private static final UUID NOT_STARTED = UUID.fromString("5a1e0000-0000-4000-8000-000000000006");
    private static final UUID NOT_ARRIVED = UUID.fromString("5a1e0000-0000-4000-8000-000000000007");
    private static final UUID TYPE = UUID.fromString("797093be-2c51-11e8-8123-005056b43f21");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private PolicyRepository policies;

    @Autowired
    private InsuredAssociationRepository associations;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    private UUID insuredId;

    @BeforeEach
    void prepareReference(@Autowired DatabaseCleaner databaseCleaner) {
        databaseCleaner.clear();
        insuredId = savePolicy(INSURED, "СТЕНД-0001", TODAY.minusYears(1), false).id();
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    @AfterEach
    void stopCounting() {
        statistics.setStatisticsEnabled(false);
    }

    @Test
    void family_givesActiveRelativePoliciesOfLinksValidToday_inSnakeCase_withoutEmail() {
        savePolicy(SPOUSE, "СТЕНД-0002", TODAY, false);
        savePolicy(ARCHIVED, "СТЕНД-0005", TODAY, true);
        savePolicy(NOT_STARTED, "СТЕНД-0006", TODAY.plusDays(1), false);
        List.of(SPOUSE, ARCHIVED, NOT_STARTED, NOT_ARRIVED).forEach(related -> link(related, TODAY, null));

        MvcTestResult result = mvc.get().uri(FAMILY, insuredId).exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.items[*].number").asArray().containsExactly("СТЕНД-0002");
        assertThat(result).bodyJson().extractingPath("$.items[0].relationship").isEqualTo(TYPE.toString());
        assertThat(result).bodyJson().extractingPath("$.items[0].date_of_birth").isEqualTo("2015-01-01");
        assertThat(result).bodyJson().extractingPath("$.items[0]").asMap()
                .doesNotContainKeys("email", "relation_type_external_id");
    }

    @Test
    void family_isReadWithTheSameNumberOfStatements_howeverManyRelativesThereAre() {
        savePolicy(SPOUSE, "СТЕНД-0002", TODAY, false);
        link(SPOUSE, TODAY, null);
        long forOne = statementsToRead(1);
        savePolicy(CHILD, "СТЕНД-0003", TODAY, false);
        savePolicy(PARENT, "СТЕНД-0004", TODAY, false);
        link(CHILD, TODAY, null);
        link(PARENT, TODAY, null);

        assertThat(statementsToRead(3)).isEqualTo(forOne);
    }

    private long statementsToRead(int relatives) {
        statistics.clear();
        MvcTestResult result = mvc.get().uri(FAMILY, insuredId).exchange();
        assertThat(result).bodyJson().extractingPath("$.items").asArray().hasSize(relatives);
        return statistics.getPrepareStatementCount();
    }

    private void link(UUID related, LocalDate validFrom, @Nullable LocalDate validTo) {
        associations.save(InsuredAssociation.of(new InsuredAssociation.Row(INSURED, related, TYPE, validFrom, validTo),
                NOW));
    }

    private Policy savePolicy(UUID externalId, String number, LocalDate begin, boolean archived) {
        var details = new Policy.Details(CONTRACT, number, "Тестова", "Проба", null, LocalDate.of(2015, 1, 1), "Ж",
                "relative@example.test", begin, null, true, archived);
        return policies.save(Policy.create(externalId, new Policy.Snapshot(details, List.of(), List.of()), NOW));
    }
}
