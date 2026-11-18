package ru.projectx.datareference.repository.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;
import ru.projectx.datareference.entity.policy.Policy;
import ru.projectx.datareference.support.DatabaseCleaner;
import ru.projectx.datareference.support.IntegrationTest;

@IntegrationTest
class PolicyNumberLookupStorageTest {

    private static final String NUMBER = "П-0001";
    private static final Instant UPDATED = Instant.parse("2026-02-01T10:00:00Z");
    private static final String INSERT = """
            insert into policies (id, external_id, external_contract_id, number, last_name, first_name, date_of_birth,
                                  begin_date, availability, is_archive, created_at, updated_at, archived_at)
            values (:id, :id, :contract, :number, 'Тестов', 'Тест', date '1990-01-01', date '2026-01-01', true,
                    :archive, :updated, :updated, case when cast(:archive as boolean) then cast(:updated as timestamptz) end)
            """;
    private static final String NUMBER_INDEX = """
            select pg_get_indexdef(index_class.oid)
            from pg_index index_row
            join pg_class index_class on index_class.oid = index_row.indexrelid
            where index_row.indrelid = 'policies'::regclass and index_class.relname = 'idx_policies_number'
            """;

    @Autowired
    private PolicyRepository policies;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionOperations transactions;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @BeforeEach
    void clearTables() {
        databaseCleaner.clear();
    }

    @Test
    void policiesHaveBtreeIndexOnNumberAlone() {
        String definition = jdbc.sql(NUMBER_INDEX).query(String.class).single();

        assertThat(definition).isEqualTo("CREATE INDEX idx_policies_number ON public.policies USING btree (number)");
    }

    @Test
    void findAllByNumber_findsPoliciesOfEveryContractIncludingArchived_byExactNumberOnly() {
        UUID active = insert(NUMBER, false);
        UUID archivedInOtherContract = insert(NUMBER, true);
        insert("п-0001", false);
        insert(" П-0001", false);
        insert("П-00011", false);

        List<UUID> found = transactions.execute(status -> policies.findAllByNumber(NUMBER).stream()
                .map(Policy::id)
                .toList());

        assertThat(found).containsExactlyInAnyOrder(active, archivedInOtherContract);
    }

    private UUID insert(String number, boolean archive) {
        UUID id = UUID.randomUUID();
        jdbc.sql(INSERT)
                .param("id", id)
                .param("contract", UUID.randomUUID())
                .param("number", number)
                .param("archive", archive)
                .param("updated", Timestamp.from(UPDATED))
                .update();
        return id;
    }
}
