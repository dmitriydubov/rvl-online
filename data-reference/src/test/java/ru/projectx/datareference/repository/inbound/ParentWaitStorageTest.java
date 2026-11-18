package ru.projectx.datareference.repository.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.projectx.datareference.entity.catalog.ContragentService;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.entity.catalog.Insurer;
import ru.projectx.datareference.entity.catalog.Lpu;
import ru.projectx.datareference.entity.catalog.LpuContract;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.entity.inbound.AwaitedParent;
import ru.projectx.datareference.entity.inbound.ParentKind;
import ru.projectx.datareference.repository.catalog.ContragentServiceRepository;
import ru.projectx.datareference.repository.catalog.InsurerRepository;
import ru.projectx.datareference.repository.catalog.LpuContractRepository;
import ru.projectx.datareference.repository.catalog.LpuRepository;
import ru.projectx.datareference.support.DatabaseCleaner;
import ru.projectx.datareference.support.IntegrationTest;
import ru.projectx.datareference.support.MigrationSql;
import ru.projectx.datareference.support.TestJdbc;

@IntegrationTest
class ParentWaitStorageTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final Instant WAITING_SINCE = NOW.minusSeconds(3600);
    private static final UUID PARENT = UUID.fromString("6d0e4a7b-2c95-4f18-8b3d-e9a1c7f50d62");
    private static final UUID RECORD = UUID.fromString("5f14a8d2-c3e7-4b90-a1d6-9e2b7c4f0a83");
    private static final UUID CID = UUID.fromString("7d3e9a10-4b2c-4f8e-9a61-0c5b8e2f4d17");

    @Autowired
    private InboundMessageRepository inbound;

    @Autowired
    private ParentWaitRepository waits;

    @Autowired
    private ForeignReferenceRepository foreign;

    @Autowired
    private LpuRepository lpus;

    @Autowired
    private LpuContractRepository contracts;

    @Autowired
    private ContragentServiceRepository services;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private TestJdbc testJdbc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private InsurerRepository insurers;

    @BeforeEach
    void clearTables() {
        databaseCleaner.clear();
    }

    @Test
    void waitingMessage_isNotDueWhileItsParentIsUnknown() {
        waiting(OneCStream.LPU_CONTRACTS, ParentKind.CONTRAGENT, PARENT, null, NOW.minusSeconds(60));

        assertThat(due()).isEmpty();
    }

    @Test
    void waitingMessage_isDueOnceItsParentIsInTheCatalog() {
        UUID contract = waiting(OneCStream.LPU_CONTRACTS, ParentKind.CONTRAGENT, PARENT, null, NOW.minusSeconds(60));
        Lpu sanatorium = lpus.save(sanatorium(PARENT));
        UUID service = waiting(OneCStream.CONTRAGENT_SERVICES, ParentKind.LPU_CONTRACT, RECORD, null,
                NOW.minusSeconds(50));
        LpuContract lpuContract = contracts.save(LpuContract.create(RECORD, sanatorium,
                new LpuContract.Details("Д-1", null, null, false, false, LocalDate.of(2020, 1, 1), null, false), NOW));
        UUID serviceId = UUID.randomUUID();
        UUID prices = waitingPart(serviceId, 1, NOW.minusSeconds(40));
        services.save(ContragentService.create(serviceId, lpuContract,
                new ContragentService.Details(null, null, "Путёвка", false), NOW));

        assertThat(due()).containsExactly(contract, service, prices);
    }

    @Test
    void waitingMessage_isDueOnceItsParentIsRecognisedAsForeign_ofTheSameKind() {
        UUID contract = waiting(OneCStream.LPU_CONTRACTS, ParentKind.CONTRAGENT, PARENT, null, NOW.minusSeconds(60));
        remember(PARENT, "LPU_CONTRACT");

        assertThat(due()).isEmpty();

        testJdbc.update("update foreign_reference set kind = 'CONTRAGENT' where external_id = ?", PARENT);

        assertThat(due()).containsExactly(contract);
    }

    @Test
    void waitingTransfer_isDueByItsFinalMessageOnly() {
        UUID service = UUID.randomUUID();
        waitingPart(service, null, NOW.minusSeconds(60));
        UUID finalMessage = waitingPart(service, 1, NOW.minusSeconds(50));
        remember(service, "CONTRAGENT_SERVICE");

        assertThat(due()).containsExactly(finalMessage);
    }

    @Test
    void expiredWait_isFoundOnlyPastTheDeadline_andOnlyWhileTheParentIsUnresolved() {
        UUID overdue = waiting(OneCStream.LPU_CONTACTS, ParentKind.CONTRAGENT, PARENT, null, NOW.minusSeconds(60));
        UUID resolvedParent = UUID.randomUUID();
        waiting(OneCStream.LPU_CONTACTS, ParentKind.CONTRAGENT, resolvedParent, null, NOW.minusSeconds(50));
        remember(resolvedParent, "CONTRAGENT");

        assertThat(idsOf(waits.findExpiredUnresolved(WAITING_SINCE, Limit.of(10)))).containsExactly(overdue);
        assertThat(waits.findExpiredUnresolved(WAITING_SINCE.minusSeconds(1), Limit.of(10))).isEmpty();
    }

    @Test
    void expiredTransfer_isFoundByItsFinalMessageOnly() {
        UUID service = UUID.randomUUID();
        waitingPart(service, null, NOW.minusSeconds(60));
        UUID finalMessage = waitingPart(service, 1, NOW.minusSeconds(50));

        assertThat(idsOf(waits.findExpiredUnresolved(WAITING_SINCE, Limit.of(10)))).containsExactly(finalMessage);
    }

    @Test
    void transferWaitingForAPolicy_isNotDue_untilThePolicyReleasesItsFinalMessage() {
        UUID part = waitingAssociationPart(null, NOW.minusSeconds(60));
        UUID finalMessage = waitingAssociationPart(1, NOW.minusSeconds(50));
        UUID otherPolicyWait = waiting(OneCStream.INSURED_ASSOCIATIONS, ParentKind.POLICY, UUID.randomUUID(), null,
                NOW.minusSeconds(40));
        assertThat(due()).isEmpty();

        int released = new TransactionTemplate(transactions).execute(
                status -> inbound.releaseAwaitingParent(ParentKind.POLICY.name(), PARENT));

        assertThat(released).isEqualTo(1);
        assertThat(due()).containsExactly(finalMessage);
        assertThat(statusOf(part)).isEqualTo(InboundStatus.AWAITING_PARENT.name());
        assertThat(statusOf(otherPolicyWait)).isEqualTo(InboundStatus.AWAITING_PARENT.name());
    }

    @Test
    void releasingAPolicy_doesNotTouchMessagesWaitingForAnotherKindWithTheSameGuid() {
        UUID contact = waiting(OneCStream.LPU_CONTACTS, ParentKind.CONTRAGENT, PARENT, null, NOW.minusSeconds(60));

        new TransactionTemplate(transactions).executeWithoutResult(
                status -> inbound.releaseAwaitingParent(ParentKind.POLICY.name(), PARENT));

        assertThat(statusOf(contact)).isEqualTo(InboundStatus.AWAITING_PARENT.name());
    }

    @Test
    void policyRecognisedAsForeign_neitherWakesNorShieldsTheWaitingTransfer() {
        UUID finalMessage = waitingAssociationPart(1, NOW.minusSeconds(50));
        remember(PARENT, "POLICY");

        assertThat(due()).isEmpty();
        assertThat(idsOf(waits.findExpiredUnresolved(WAITING_SINCE, Limit.of(10)))).containsExactly(finalMessage);
    }

    @Test
    void policyWaitPastTheDeadline_expiresWithoutLookingIntoPolicies() {
        UUID finalMessage = waitingAssociationPart(1, NOW.minusSeconds(50));

        assertThat(idsOf(waits.findExpiredUnresolved(WAITING_SINCE, Limit.of(10)))).containsExactly(finalMessage);
    }

    @Test
    void awaitingOfRecord_isOnlyEarlierWaitingMessagesOfTheSameStreamAndRecord() {
        UUID earlier = waiting(OneCStream.LPU_CONTRACTS, ParentKind.CONTRAGENT, PARENT, RECORD, NOW.minusSeconds(60));
        waiting(OneCStream.LPU_CONTRACTS, ParentKind.CONTRAGENT, PARENT, UUID.randomUUID(), NOW.minusSeconds(55));
        waiting(OneCStream.LPU_CONTACTS, ParentKind.CONTRAGENT, PARENT, RECORD, NOW.minusSeconds(50));
        waiting(OneCStream.LPU_CONTRACTS, ParentKind.CONTRAGENT, PARENT, RECORD, NOW.plusSeconds(10));

        assertThat(idsOf(waits.findAwaitingOfRecordBefore(OneCStream.LPU_CONTRACTS, RECORD, NOW)))
                .containsExactly(earlier);
    }

    @Test
    void retention_erasesTheBodyOfAnExpiredWaitOnly() {
        Instant received = NOW.minusSeconds(86_400 * 40L);
        UUID expired = row("FAILED", OneCStream.LPU_CONTRACTS, received, WAITING_SINCE);
        UUID failed = row("FAILED", OneCStream.LPU_CONTRACTS, received, null);
        UUID stillWaiting = waiting(OneCStream.LPU_CONTRACTS, ParentKind.CONTRAGENT, PARENT, null, received);

        assertThat(inbound.countPayloadsToForgetReceivedBefore(NOW)).isEqualTo(1);
        int forgotten = new TransactionTemplate(transactions)
                .execute(status -> inbound.forgetPayloadsReceivedBefore(NOW, 10));

        assertThat(forgotten).isEqualTo(1);
        assertThat(inbound.findById(expired).orElseThrow().payload()).isNull();
        assertThat(inbound.findById(expired).orElseThrow().outcome().lastError()).isEqualTo("причина");
        assertThat(inbound.findById(failed).orElseThrow().payload()).isNotNull();
        assertThat(inbound.findById(stillWaiting).orElseThrow().payload()).isNotNull();
    }

    @Test
    void foreignReference_isRememberedOnce_andRecognisedByItsKind() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            foreign.remember(PARENT, "CONTRAGENT", NOW);
            foreign.remember(PARENT, "LPU_CONTRACT", NOW.plusSeconds(60));
        });

        assertThat(jdbc.sql("select count(*) from foreign_reference").query(Long.class).single()).isEqualTo(1);
        assertThat(foreign.existsByExternalIdAndKind(PARENT, ParentKind.CONTRAGENT)).isTrue();
        assertThat(foreign.existsByExternalIdAndKind(PARENT, ParentKind.LPU_CONTRACT)).isFalse();
    }

    @Test
    void messageReturnedToParsing_startsWaitingAnew() {
        UUID messageId = row("NEW", OneCStream.LPU_CONTRACTS, NOW.minusSeconds(86_400), WAITING_SINCE);
        InboundMessage message = inbound.findById(messageId).orElseThrow();

        message.markAwaitingParent(new AwaitedParent(ParentKind.CONTRAGENT, PARENT), RECORD, NOW);

        assertThat(message.outcome().parentWait().orElseThrow().since()).isEqualTo(NOW);
        assertThat(message.outcome().status()).isEqualTo(InboundStatus.AWAITING_PARENT);
    }

    @Test
    void fill_remembersInsurersAndSanatoriumsWithoutRvl_only() {
        UUID insurer = UUID.randomUUID();
        insurers.save(Insurer.create(insurer, new Insurer.Details("АО «Страховщик»", null, null, null, false), NOW));
        UUID withoutRvl = UUID.randomUUID();
        skippedContragent(withoutRvl.toString(), "контрагент без вида деятельности РВЛ");
        skippedContragent(withoutRvl.toString(), "контрагент без вида деятельности РВЛ");
        skippedContragent("1-2-3-4-5", "контрагент без вида деятельности РВЛ");
        skippedContragent(UUID.randomUUID().toString(), "не задан projectx.onec.contract.rvl-activity-id");
        UUID becameOurs = UUID.randomUUID();
        skippedContragent(becameOurs.toString(), "контрагент без вида деятельности РВЛ");
        lpus.save(sanatorium(becameOurs));

        fillSql().forEach(testJdbc::update);
        fillSql().forEach(testJdbc::update);

        assertThat(jdbc.sql("select external_id from foreign_reference where kind = 'CONTRAGENT' order by external_id")
                .query(UUID.class).list()).containsExactlyInAnyOrder(insurer, withoutRvl);
    }

    private void skippedContragent(String guid, String reason) {
        testJdbc.update("""
                insert into inbound_message (message_id, stream, source_queue, payload, status, attempt, received_at,
                                             last_error)
                values (?, 'CONTRAGENTS', 'очередь', cast(? as jsonb), 'SKIPPED', 0, ?, ?)
                """, UUID.randomUUID(), "{\"data\":{\"guid\":\"%s\"}}".formatted(guid), Timestamp.from(NOW),
                reason);
    }

    private static List<String> fillSql() {
        return MigrationSql.statementsOf("db/changelog/018-parent-wait.yaml", "018-foreign-reference-fill");
    }

    private void remember(UUID externalId, String kind) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> foreign.remember(externalId, kind, NOW));
    }

    private List<UUID> due() {
        return idsOf(inbound.findDueForImport(NOW, Limit.of(10)));
    }

    private static List<UUID> idsOf(List<InboundMessage> messages) {
        return messages.stream().map(message -> message.receipt().messageId()).toList();
    }

    private UUID waiting(OneCStream stream, ParentKind kind, UUID parent, @Nullable UUID record, Instant receivedAt) {
        UUID messageId = UUID.randomUUID();
        testJdbc.update("""
                insert into inbound_message (message_id, stream, source_queue, payload, status, attempt, received_at,
                                             last_error, parent_wait_since, awaited_parent_kind, awaited_parent_id,
                                             record_external_id)
                values (?, ?, 'очередь', cast('{"data":{}}' as jsonb), 'AWAITING_PARENT', 0, ?, 'ждёт', ?, ?, ?, ?)
                """, messageId, stream.name(), Timestamp.from(receivedAt), Timestamp.from(WAITING_SINCE), kind.name(),
                parent, record);
        return messageId;
    }

    private UUID waitingPart(UUID service, @Nullable Integer batchCount, Instant receivedAt) {
        UUID messageId = waiting(OneCStream.PRICE_LISTS, ParentKind.CONTRAGENT_SERVICE, service, null, receivedAt);
        testJdbc.update("update inbound_message set correlation_id = ?, batch_count = ?, row_count = 1 "
                + "where message_id = ?", CID, batchCount, messageId);
        return messageId;
    }

    private UUID waitingAssociationPart(@Nullable Integer batchCount, Instant receivedAt) {
        UUID messageId = waiting(OneCStream.INSURED_ASSOCIATIONS, ParentKind.POLICY, PARENT, null, receivedAt);
        testJdbc.update("update inbound_message set correlation_id = ?, batch_count = ?, row_count = 1 "
                + "where message_id = ?", CID, batchCount, messageId);
        return messageId;
    }

    private String statusOf(UUID messageId) {
        return jdbc.sql("select status from inbound_message where message_id = :id").param("id", messageId)
                .query(String.class).single();
    }

    private UUID row(String status, OneCStream stream, Instant receivedAt, @Nullable Instant waitingSince) {
        UUID messageId = UUID.randomUUID();
        testJdbc.update("""
                insert into inbound_message (message_id, stream, source_queue, payload, status, attempt, received_at,
                                             last_error, parent_wait_since, awaited_parent_kind, awaited_parent_id)
                values (?, ?, 'очередь', cast('{"data":{}}' as jsonb), ?, 0, ?, 'причина', ?, ?, ?)
                """, messageId, stream.name(), status, Timestamp.from(receivedAt),
                waitingSince == null ? null : Timestamp.from(waitingSince),
                waitingSince == null ? null : ParentKind.CONTRAGENT.name(), waitingSince == null ? null : PARENT);
        return messageId;
    }

    private static Lpu sanatorium(UUID externalId) {
        return Lpu.create(externalId, new Lpu.Details("Санаторий «Бета»", null, null, null, null, null, false), NOW);
    }
}
