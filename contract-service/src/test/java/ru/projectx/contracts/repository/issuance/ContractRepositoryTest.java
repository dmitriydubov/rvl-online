package ru.projectx.contracts.repository.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import ru.projectx.contracts.entity.issuance.CancelReason;
import ru.projectx.contracts.entity.issuance.Cancellation;
import ru.projectx.contracts.entity.contract.Contract;
import ru.projectx.contracts.entity.contract.ContractDraft;
import ru.projectx.contracts.entity.party.ContractPerson;
import ru.projectx.contracts.entity.contract.ContractStatus;
import ru.projectx.contracts.entity.contract.ContractStep;
import ru.projectx.contracts.entity.party.Gender;
import ru.projectx.contracts.entity.party.IdentityDocument;
import ru.projectx.contracts.entity.party.IdentityDocumentType;
import ru.projectx.contracts.entity.issuance.IssuanceTerms;
import ru.projectx.contracts.entity.issuance.IssueError;
import ru.projectx.contracts.entity.policy.LmPolicy;
import ru.projectx.contracts.entity.policy.LmPolicyDraft;
import ru.projectx.contracts.entity.policy.LmPolicyStatus;
import ru.projectx.contracts.entity.party.PersonOwner;
import ru.projectx.contracts.entity.party.PersonRole;
import ru.projectx.contracts.entity.party.PersonSnapshot;
import ru.projectx.contracts.entity.issuance.RequestReference;
import ru.projectx.contracts.entity.issuance.TripPeriod;
import ru.projectx.contracts.support.IntegrationTest;

@IntegrationTest
class ContractRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-02-10T07:05:00Z");
    private static final UUID REQUEST = UUID.fromString("61a7982c-a0b4-4bbf-9527-60b40b752803");
    private static final UUID INSURED = UUID.fromString("ae8312c3-350b-4d0b-806c-5e2460e74dbc");
    private static final UUID SPOUSE = UUID.fromString("9275471c-d2fe-484f-97a6-d6d7b1267b5e");
    private static final UUID CHILD = UUID.fromString("6778e8c2-985a-4d54-9bd3-0f6403b104d6");
    private static final RequestReference REFERENCE = new RequestReference(REQUEST,
            UUID.fromString("e12aab1a-1582-4b3a-af36-d30e637953f1"), UUID.fromString("a23ba061-f742-4b20-8b95-36a0e525c087"));
    private static final TripPeriod PERIOD = new TripPeriod(LocalDate.parse("2026-04-10"), LocalDate.parse("2026-04-24"));
    private static final IssuanceTerms TERMS = new IssuanceTerms(REFERENCE, INSURED,
            UUID.fromString("2f6a0c1e-3d5b-4a7c-9e8f-0b1c2d3e4f5a"), PERIOD);

    @Autowired
    private ContractRepository contracts;

    @Autowired
    private LmPolicyRepository policies;

    @Autowired
    private ContractPersonRepository persons;

    @Autowired
    private ContractNumberRepository numbers;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void emptyTables() {
        persons.deleteAll();
        contracts.deleteAll();
        policies.deleteAll();
        jdbc.sql("delete from contract_number_counters").update();
    }

    @Test
    void contract_roundTripsNativeEnumsEmbeddedValuesAndAmounts() {
        Contract contract = Contract.open(new ContractDraft("DKM-2026-000001", TERMS, SPOUSE,
                new BigDecimal("34000.00")), NOW);
        contract.cancel(new Cancellation(CancelReason.REQUEST_CANCELLED, "Стенд: комментарий", NOW), NOW);
        contract.markError(new IssueError("CODE", "текст"), NOW);
        transaction.executeWithoutResult(status -> contracts.save(contract));

        Contract found = contracts.findById(contract.id()).orElseThrow();
        assertThat(found.status()).isEqualTo(ContractStatus.ERROR);
        assertThat(found.state().cancellation()).contains(new Cancellation(CancelReason.REQUEST_CANCELLED, "Стенд: комментарий", NOW));
        assertThat(found.state().error()).contains(new IssueError("CODE", "текст"));
        assertThat(found.premiumAmount()).isEqualByComparingTo("34000.00");
        assertThat(found.state().period()).isEqualTo(PERIOD);
        assertThat(found.state().request()).isEqualTo(REFERENCE);
        assertThat(found.state().entityVersion()).isEqualTo(3);
        assertThat(jdbc.sql("select status::text || '/' || cancel_reason::text from contracts").query(String.class).single())
                .isEqualTo("ERROR/REQUEST_CANCELLED");
    }

    @Test
    void activeContracts_areThoseOfTheRequestOutsideFinishedStatuses() {
        Contract active = contract(SPOUSE, "DKM-2026-000001");
        Contract cancelled = contract(CHILD, "DKM-2026-000002");
        cancelled.cancel(new Cancellation(CancelReason.OTHER, null, NOW), NOW);
        transaction.executeWithoutResult(status -> contracts.saveAll(java.util.List.of(active, cancelled)));

        assertThat(contracts.findByRequestIndividualRequestIdAndStatusNotIn(REQUEST,
                Set.of(ContractStatus.ISSUED, ContractStatus.ERROR, ContractStatus.CANCELLED)))
                .extracting(Contract::number).containsExactly("DKM-2026-000001");
    }

    @Test
    void submittedContracts_areCountedAndOrderedByTheMomentOfSubmissionAndListedByStatusInCreationOrder() {
        Contract later = paid(SPOUSE, "DKM-2026-000001", NOW.plus(Duration.ofMinutes(1)));
        Contract earlier = paid(CHILD, "DKM-2026-000002", NOW);
        Contract submitted = paid(UUID.fromString("2b9d4e6f-8a1c-4d3e-9f50-61728394a5b6"), "DKM-2026-000003", NOW);
        submitted.submit(NOW.plus(Duration.ofMinutes(5)));
        transaction.executeWithoutResult(status -> contracts.saveAll(java.util.List.of(later, earlier, submitted)));

        assertThat(contracts.findByStatusOrderByCreatedAtAscIdAsc(ContractStatus.PAID, Limit.of(10)))
                .extracting(Contract::number).containsExactly("DKM-2026-000002", "DKM-2026-000001");
        assertThat(contracts.findFirstByStatusOrderByUpdatedAtAscIdAsc(ContractStatus.SUBMITTED))
                .map(Contract::number).contains("DKM-2026-000003");
        assertThat(contracts.countByStatusAndUpdatedAtBefore(ContractStatus.SUBMITTED, NOW.plus(Duration.ofMinutes(5))))
                .isZero();
        assertThat(contracts.countByStatusAndUpdatedAtBefore(ContractStatus.SUBMITTED, NOW.plus(Duration.ofMinutes(6))))
                .isEqualTo(1);
        Optional<String> locked = transaction.execute(status -> contracts.findLockedById(submitted.id())
                .map(Contract::number));
        assertThat(locked).contains("DKM-2026-000003");
    }

    @Test
    void runningOrFailedAfterPayment_areRunningAndPaidFailedContractsOfTheRequest() {
        Contract running = contract(SPOUSE, "DKM-2026-000001");
        Contract paidFailed = contract(CHILD, "DKM-2026-000002");
        paidFailed.offerDraft();
        paidFailed.advance(ContractStep.OFFER_ACCEPTED);
        paidFailed.markPaid(NOW, NOW);
        paidFailed.markError(new IssueError("KM_CONTRACT_SUBMISSION_UNDELIVERED", "договор КМ не передан в 1С:УСК"), NOW);
        Contract unpaidFailed = contract(SPOUSE, "DKM-2026-000003");
        unpaidFailed.markError(new IssueError("RELATION_TYPE_REQUIRED", "поле обязательно"), NOW);
        Contract cancelled = contract(CHILD, "DKM-2026-000004");
        cancelled.cancel(new Cancellation(CancelReason.OTHER, null, NOW), NOW);
        transaction.executeWithoutResult(status ->
                contracts.saveAll(java.util.List.of(running, paidFailed, unpaidFailed, cancelled)));

        assertThat(contracts.findRunningOrFailedAfterPayment(REQUEST, ContractStatus.ERROR, ContractStatus.CANCELLED))
                .extracting(Contract::number).containsExactlyInAnyOrder("DKM-2026-000001", "DKM-2026-000002");
    }

    @Test
    void secondUnfinishedContractOfTheSameParticipant_isRejectedByThePartialUniqueIndex() {
        transaction.executeWithoutResult(status -> contracts.save(contract(SPOUSE, "DKM-2026-000001")));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> contracts.save(contract(SPOUSE, "DKM-2026-000002"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cancelledContract_leavesRoomForANewOneOfTheSameParticipant() {
        Contract cancelled = contract(SPOUSE, "DKM-2026-000001");
        cancelled.cancel(new Cancellation(CancelReason.OTHER, null, NOW), NOW);
        transaction.executeWithoutResult(status -> contracts.save(cancelled));

        Contract reopened = contract(SPOUSE, "DKM-2026-000002");
        Contract otherParticipant = contract(CHILD, "DKM-2026-000003");
        IssuanceTerms otherRequestTerms = new IssuanceTerms(new RequestReference(UUID.randomUUID(),
                REFERENCE.insuranceContractId(), REFERENCE.insurerPolicyId()), INSURED, TERMS.programId(), PERIOD);
        Contract otherRequest = Contract.open(new ContractDraft("DKM-2026-000004", otherRequestTerms, SPOUSE,
                new BigDecimal("34000.00")), NOW);
        otherRequest.cancel(new Cancellation(CancelReason.OTHER, null, NOW), NOW);
        transaction.executeWithoutResult(status ->
                contracts.saveAll(java.util.List.of(reopened, otherParticipant, otherRequest)));

        assertThat(contracts.count()).isEqualTo(4);
        assertThat(contracts.findByRequestIndividualRequestIdAndInsuredParticipantId(REQUEST, SPOUSE))
                .extracting(Contract::number)
                .containsExactlyInAnyOrder("DKM-2026-000001", "DKM-2026-000002");
    }

    @Test
    void policy_roundTripsAndKeepsUniqueUnfinishedParticipant() {
        LmPolicy policy = LmPolicy.open(new LmPolicyDraft(TERMS, CHILD), NOW);
        transaction.executeWithoutResult(status -> policies.save(policy));

        assertThat(policies.findByRequestIndividualRequestIdAndStatusNotIn(REQUEST, Set.of(LmPolicyStatus.CANCELLED)))
                .extracting(LmPolicy::participantId).containsExactly(CHILD);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                policies.save(LmPolicy.open(new LmPolicyDraft(TERMS, CHILD), NOW))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persons_roundTripNativeEnumsAndAreFoundByOwnerInRoleOrder() {
        Contract contract = contract(SPOUSE, "DKM-2026-000001");
        PersonSnapshot insured = new PersonSnapshot(SPOUSE, "Тестова", "Теста", null, LocalDate.parse("1988-07-02"),
                Gender.FEMALE, "SPOUSE", new IdentityDocument(IdentityDocumentType.FOREIGN_PASSPORT, null, "654321",
                        null, null, null), null);
        PersonSnapshot policyholder = new PersonSnapshot(INSURED, "Тестов", "Тест", "Тестович",
                LocalDate.parse("1984-03-11"), Gender.MALE, null, new IdentityDocument(IdentityDocumentType.RF_PASSPORT,
                        "4510", "123456", "Тестовый орган", LocalDate.parse("2010-04-20"), "770-001"), "Тестовый адрес");
        transaction.executeWithoutResult(status -> {
            contracts.save(contract);
            persons.save(ContractPerson.snapshot(PersonOwner.contract(contract.id()), PersonRole.INSURED, insured, NOW));
            persons.save(ContractPerson.snapshot(PersonOwner.contract(contract.id()), PersonRole.POLICYHOLDER, policyholder, NOW));
        });

        assertThat(persons.findByContractIdOrderByRole(contract.id())).extracting(ContractPerson::person)
                .containsExactly(policyholder, insured);
        assertThat(persons.findByLmPolicyIdOrderByRole(contract.id())).isEmpty();
        transaction.executeWithoutResult(status -> persons.deleteByContractId(contract.id()));
        assertThat(persons.count()).isZero();
    }

    @Test
    void personWithoutOwnerOrWithBothOwners_isRejected() {
        PersonSnapshot person = new PersonSnapshot(SPOUSE, "Тестова", "Теста", null, LocalDate.parse("1988-07-02"),
                Gender.FEMALE, "SPOUSE", new IdentityDocument(IdentityDocumentType.OTHER, null, "1", null, null, null), null);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> persons.save(
                ContractPerson.snapshot(new PersonOwner(null, null), PersonRole.INSURED, person, NOW))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void numbers_countWithinAYearAndStartAgainWithAnotherYear() {
        int first = transaction.execute(status -> numbers.nextNumber(2026));
        int second = transaction.execute(status -> numbers.nextNumber(2026));
        int otherYear = transaction.execute(status -> numbers.nextNumber(2027));

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
        assertThat(otherYear).isEqualTo(1);
    }

    @Test
    void number_takenInARolledBackTransaction_isNotSpent() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            numbers.nextNumber(2026);
            throw new IllegalStateException("откат");
        })).isInstanceOf(IllegalStateException.class);

        Integer next = transaction.execute(status -> numbers.nextNumber(2026));
        assertThat(next).isEqualTo(1);
    }

    private static Contract contract(UUID participant, String number) {
        return Contract.open(new ContractDraft(number, TERMS, participant, new BigDecimal("34000.00")), NOW);
    }

    private static Contract paid(UUID participant, String number, Instant opened) {
        Contract contract = Contract.open(new ContractDraft(number, TERMS, participant, new BigDecimal("34000.00")),
                opened);
        contract.offerDraft();
        contract.advance(ContractStep.OFFER_ACCEPTED);
        contract.markPaid(opened, opened);
        return contract;
    }
}
