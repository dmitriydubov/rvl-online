package ru.projectx.datareference.repository.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InsuredAssociationFinalization;
import ru.projectx.datareference.entity.policy.InsuredAssociationFilter;
import ru.projectx.datareference.support.DatabaseCleaner;
import ru.projectx.datareference.support.IntegrationTest;
import ru.projectx.datareference.support.TestJdbc;

@IntegrationTest
class InsuredAssociationFinalizationStorageTest {

    private static final Instant APPLIED_AT = Instant.parse("2026-11-15T10:00:00Z");
    private static final UUID APPLIED_CID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeee09");
    private static final LocalDate START = LocalDate.of(2026, 11, 13);
    private static final UUID INSURED = UUID.fromString("11111111-2222-4333-8444-555555555501");
    private static final UUID OTHER_INSURED = UUID.fromString("11111111-2222-4333-8444-555555555504");
    private static final UUID RELATED = UUID.fromString("11111111-2222-4333-8444-555555555502");

    @Autowired
    private InsuredAssociationFinalizationRepository finalizations;

    @Autowired
    private InboundMessageRepository inbound;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestJdbc testJdbc;

    @BeforeEach
    void clearTables() {
        databaseCleaner.clear();
    }

    @Test
    void appliedTransferWithALaterFinalAndOverlappingFilter_isFound() {
        applied(new InsuredAssociationFilter(null, INSURED, null, null));

        assertThat(newerOverlapping(new InsuredAssociationFilter(START, INSURED, RELATED, null),
                APPLIED_AT.minusSeconds(1))).contains(APPLIED_CID);
    }

    @Test
    void appliedTransferWithAnEarlierOrEqualFinal_isNotNewer() {
        applied(new InsuredAssociationFilter(null, INSURED, null, null));
        InsuredAssociationFilter filter = new InsuredAssociationFilter(null, INSURED, null, null);

        assertThat(newerOverlapping(filter, APPLIED_AT)).isEmpty();
        assertThat(newerOverlapping(filter, APPLIED_AT.plusSeconds(1))).isEmpty();
    }

    @Test
    void appliedTransferOfAnotherInsured_doesNotOverlap() {
        applied(new InsuredAssociationFilter(null, INSURED, null, null));

        assertThat(newerOverlapping(new InsuredAssociationFilter(null, OTHER_INSURED, null, null),
                APPLIED_AT.minusSeconds(1))).isEmpty();
    }

    @Test
    void dimensionMissingFromEitherFilter_overlapsAnyValue() {
        applied(new InsuredAssociationFilter(START, INSURED, RELATED, null));

        assertThat(newerOverlapping(new InsuredAssociationFilter(null, INSURED, null, null),
                APPLIED_AT.minusSeconds(1))).contains(APPLIED_CID);
        assertThat(newerOverlapping(new InsuredAssociationFilter(START.plusDays(1), INSURED, null, null),
                APPLIED_AT.minusSeconds(1))).isEmpty();
    }

    private void applied(InsuredAssociationFilter filter) {
        UUID finalId = UUID.randomUUID();
        testJdbc.update("""
                insert into inbound_message (message_id, stream, source_queue, payload, status, attempt, received_at,
                                             correlation_id, batch_count, row_count)
                values (?, 'INSURED_ASSOCIATIONS', 'очередь', cast('{"data":{}}' as jsonb), 'PROCESSED', 0, ?, ?, 0, 0)
                """, finalId, Timestamp.from(APPLIED_AT), APPLIED_CID);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            InboundMessage finalMessage = inbound.findById(finalId).orElseThrow();
            finalizations.save(InsuredAssociationFinalization.of(finalMessage, filter));
        });
    }

    private Optional<UUID> newerOverlapping(InsuredAssociationFilter filter, Instant finalReceivedAt) {
        return new TransactionTemplate(transactions).execute(
                status -> finalizations.findNewerOverlapping(filter, finalReceivedAt));
    }
}
