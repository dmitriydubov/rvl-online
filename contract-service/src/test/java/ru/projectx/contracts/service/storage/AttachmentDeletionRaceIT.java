package ru.projectx.contracts.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.projectx.contracts.entity.contract.AcceptanceChannel;
import ru.projectx.contracts.entity.contract.Contract;
import ru.projectx.contracts.entity.contract.ContractAcceptance;
import ru.projectx.contracts.entity.contract.ContractDraft;
import ru.projectx.contracts.entity.contract.ContractStep;
import ru.projectx.contracts.entity.contract.OfferAcceptance;
import ru.projectx.contracts.entity.document.DocumentAttachments;
import ru.projectx.contracts.entity.document.PrintedForm;
import ru.projectx.contracts.entity.document.PrintedFormType;
import ru.projectx.contracts.entity.document.StoredFile;
import ru.projectx.contracts.entity.issuance.IssuanceTerms;
import ru.projectx.contracts.entity.issuance.RequestReference;
import ru.projectx.contracts.entity.party.ContractPerson;
import ru.projectx.contracts.entity.party.Gender;
import ru.projectx.contracts.entity.party.IdentityDocument;
import ru.projectx.contracts.entity.party.IdentityDocumentType;
import ru.projectx.contracts.entity.party.PersonOwner;
import ru.projectx.contracts.entity.party.PersonRole;
import ru.projectx.contracts.entity.party.PersonSnapshot;
import ru.projectx.contracts.exception.AttachmentBusyException;
import ru.projectx.contracts.exception.AttachmentLockedException;
import ru.projectx.contracts.repository.issuance.ContractAcceptanceRepository;
import ru.projectx.contracts.repository.issuance.ContractPersonRepository;
import ru.projectx.contracts.repository.issuance.ContractRepository;
import ru.projectx.contracts.repository.issuance.PrintedFormRepository;
import ru.projectx.contracts.service.RequestIds;
import ru.projectx.contracts.service.km.submission.KmContractSubmissionRecording;
import ru.projectx.contracts.service.reference.ExternalIdentifiers;
import ru.projectx.contracts.support.FixedClockConfig;
import ru.projectx.contracts.support.IntegrationTest;
import ru.projectx.contracts.support.Issuances;
import ru.projectx.contracts.support.RowRaces;
import ru.projectx.contracts.support.Tasks;

@IntegrationTest
@Import(AttachmentDeletionRaceIT.ScriptedStoreConfig.class)
class AttachmentDeletionRaceIT {

    private static final String HOLD_CONTRACT = "select id from contracts where id = ? for update";
    private static final String SUBMIT_CONTRACT = "update contracts set status = 'SUBMITTED'::contract_status,"
            + " entity_version = entity_version + 1 where id = ?";
    private static final ExternalIdentifiers IDENTIFIERS = new ExternalIdentifiers(
            UUID.fromString("8c41e2ab-55d0-4a17-9b63-2f0e7c1d4a88"),
            UUID.fromString("1d9a6e77-4c02-4b88-a15e-30f7b2c9de41"),
            UUID.fromString("6b02f1cc-9d47-4e35-8a20-7f1c3e5b09a2"));

    @Autowired private AttachmentDeletions deletions;
    @Autowired private KmContractSubmissionRecording submissionRecording;
    @Autowired private ContractRepository contracts;
    @Autowired private ContractAcceptanceRepository acceptances;
    @Autowired private ContractPersonRepository persons;
    @Autowired private PrintedFormRepository documents;
    @Autowired private ScriptedCertificateStore store;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcClient jdbc;

    private final UUID request = UUID.randomUUID();
    private final UUID spouse = UUID.randomUUID();
    private final UUID attachmentId = UUID.randomUUID();
    private RowRaces races;
    private UUID contractId;

    @BeforeEach
    void setUpContractAndCertificate() {
        races = new RowRaces(dataSource, jdbc);
        store.reset();
        IssuanceTerms terms = new IssuanceTerms(
                new RequestReference(request, Tasks.INSURANCE_CONTRACT, Tasks.INSURER_POLICY),
                Tasks.INSURED, Tasks.PROGRAM, Issuances.PERIOD);
        Contract contract = Contract.open(new ContractDraft("DKM-RACE-" + request.toString().substring(0, 8),
                terms, spouse, new BigDecimal("34000.00")), Issuances.OPENED);
        contract.offerDraft();
        contract.advance(ContractStep.OFFER_ACCEPTED);
        contract.markPaid(Issuances.OPENED, Issuances.OPENED);
        contractId = contracts.saveAndFlush(contract).id();
        acceptances.saveAndFlush(ContractAcceptance.record(contractId,
                new OfferAcceptance(1, Tasks.INSURED, Issuances.OPENED, AcceptanceChannel.WEB, null, null),
                Issuances.OPENED));
        savePersons(contractId);
        StoredFile file = new StoredFile("1111111111_222222222/certificates/f/" + attachmentId + ".jpg",
                "7851d78af0fb98bbbda8933f49860a009ac7761c21a1136613c8a6704b156251", 76L, "image/jpeg");
        documents.saveAndFlush(PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE,
                DocumentAttachments.certificate(request, spouse, attachmentId), file, Issuances.OPENED));
    }

    @AfterEach
    void cleanTables() {
        MDC.remove(RequestIds.MDC_KEY);
        store.reset();
        jdbc.sql("truncate table outbox_messages, contract_documents, contract_acceptances, contract_persons, contracts cascade")
                .update();
    }

    @Test
    void contractRowHeldLongerThanLockTimeout_throwsAttachmentBusyWithoutDeletingObjectOrMarkingRegistry()
            throws SQLException {
        races.whileTheRowIsHeld(HOLD_CONTRACT, contractId, () ->
                assertThatThrownBy(() -> deletions.delete(attachmentId))
                        .isInstanceOf(AttachmentBusyException.class));

        assertThat(store.removedKeys()).isEmpty();
        assertThat(deletionRow().get("deleted_at")).isNull();
    }

    @Test
    void submissionCommittedWhileDeleteWaitsForContractRow_throwsAttachmentLockedWithoutDeletingObject()
            throws SQLException {
        AtomicReference<Throwable> caught = new AtomicReference<>();
        races.commitOnceTheOtherPathWaitsForTheRow(SUBMIT_CONTRACT, contractId, () -> {
            try {
                deletions.delete(attachmentId);
            } catch (Throwable failure) {
                caught.set(failure);
            }
        });

        assertThat(caught.get()).isInstanceOf(AttachmentLockedException.class);
        assertThat(store.removedKeys()).isEmpty();
        assertThat(deletionRow().get("deleted_at")).isNull();
    }

    @Test
    void uskSubmissionRecordingCommittedDuringPhaseTwoS3Removal_blocksPhaseThreeWithoutDeadlock() {
        store.onRemove(() -> submissionRecording.submit(contractId, IDENTIFIERS));

        assertThatThrownBy(() -> deletions.delete(attachmentId))
                .isInstanceOf(AttachmentLockedException.class);

        assertThat(store.removedKeys()).hasSize(1);
        assertThat(store.sawActiveTransaction()).isFalse();
        assertThat(deletionRow().get("deleted_at")).isNull();
    }

    @Test
    void twoConcurrentDeletesWithSubmissionAfterFirstWinner_completeIdempotentlyKeepingWinnersAuditAndEmptyUskDocs()
            throws Exception {
        CountDownLatch bothInPhaseTwo = new CountDownLatch(2);
        CountDownLatch winnerRecorded = new CountDownLatch(1);
        store.onRemove(() -> coordinatePhaseTwoAndSubmit(bothInPhaseTwo, winnerRecorded));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> deleteWithRequestId("req-winner", winnerRecorded));
            Future<?> second = pool.submit(() -> deleteWithRequestId("req-second", null));
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        Map<String, Object> row = deletionRow();
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("deleted_request_id")).isEqualTo("req-winner");
        String payload = jdbc.sql("select payload from outbox_messages where event_type = 'KmContractSubmitted'")
                .query(String.class).single();
        assertThat(payload).contains("\"documents\": []");
        assertThat(store.sawActiveTransaction()).isFalse();
    }

    private void deleteWithRequestId(String requestId, CountDownLatch afterFinish) {
        MDC.put(RequestIds.MDC_KEY, requestId);
        try {
            deletions.delete(attachmentId);
        } finally {
            MDC.remove(RequestIds.MDC_KEY);
            if (afterFinish != null) {
                afterFinish.countDown();
            }
        }
    }

    private void coordinatePhaseTwoAndSubmit(CountDownLatch bothInPhaseTwo, CountDownLatch winnerRecorded) {
        bothInPhaseTwo.countDown();
        awaitLatch(bothInPhaseTwo);
        if ("req-second".equals(MDC.get(RequestIds.MDC_KEY))) {
            awaitLatch(winnerRecorded);
            submissionRecording.submit(contractId, IDENTIFIERS);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ожидание гонки прервано", interrupted);
        }
    }

    private Map<String, Object> deletionRow() {
        return jdbc.sql("select deleted_at, deleted_request_id from contract_documents where attachment_id = :id")
                .param("id", attachmentId)
                .query()
                .singleRow();
    }

    private void savePersons(UUID ownerContractId) {
        IdentityDocument passport = new IdentityDocument(IdentityDocumentType.RF_PASSPORT, "4510", "123456",
                "Тестовый орган", LocalDate.parse("2010-04-20"), "770-001");
        persons.saveAll(List.of(
                ContractPerson.snapshot(PersonOwner.contract(ownerContractId), PersonRole.POLICYHOLDER,
                        new PersonSnapshot(Tasks.INSURED, "Тестов", "Тест", null, LocalDate.parse("1984-03-11"),
                                Gender.MALE, null, passport, "Адрес"), FixedClockConfig.NOW),
                ContractPerson.snapshot(PersonOwner.contract(ownerContractId), PersonRole.INSURED,
                        new PersonSnapshot(spouse, "Тестова", "Анна", null, LocalDate.parse("1988-07-02"),
                                Gender.FEMALE, "SPOUSE", passport, "Адрес"), FixedClockConfig.NOW)));
        persons.flush();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedStoreConfig {

        @Bean
        @Primary
        ScriptedCertificateStore scriptedCertificateStore() {
            return new ScriptedCertificateStore();
        }
    }

    static final class ScriptedCertificateStore implements CertificateStore {

        private final List<String> removed = new CopyOnWriteArrayList<>();
        private volatile boolean activeTxObserved;
        private volatile Runnable hook = () -> { };

        void reset() {
            removed.clear();
            activeTxObserved = false;
            hook = () -> { };
        }

        void onRemove(Runnable action) {
            this.hook = action;
        }

        List<String> removedKeys() {
            return List.copyOf(removed);
        }

        boolean sawActiveTransaction() {
            return activeTxObserved;
        }

        @Override
        public void store(StoredFile file, ContentSource content) {
        }

        @Override
        public void verifyPresent(String key) {
        }

        @Override
        public InputStream openStream(String key) {
            return new ByteArrayInputStream(new byte[] {1});
        }

        @Override
        public void remove(String key) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                activeTxObserved = true;
            }
            removed.add(key);
            hook.run();
        }
    }
}
