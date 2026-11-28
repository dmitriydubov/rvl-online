package ru.projectx.contracts.repository.issuance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import ru.projectx.contracts.entity.document.DocumentAttachment;
import ru.projectx.contracts.entity.document.DocumentAttachments;
import ru.projectx.contracts.entity.document.PrintedForm;
import ru.projectx.contracts.entity.document.PrintedFormType;
import ru.projectx.contracts.entity.document.StoredFile;
import ru.projectx.contracts.entity.issuance.DocumentKind;
import ru.projectx.contracts.entity.issuance.IssuanceRequest;
import ru.projectx.contracts.entity.issuance.RequestParticipant;
import ru.projectx.contracts.support.IntegrationTest;

@IntegrationTest
class PrintedFormRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-02-10T07:05:00Z");
    private static final UUID REQUEST = UUID.fromString("61a7982c-a0b4-4bbf-9527-60b40b752803");
    private static final UUID EARLY_REQUEST = UUID.fromString("7f2c9e4a-1b3d-4e5f-8a6b-9c0d1e2f3a4b");
    private static final UUID PARTICIPANT = UUID.fromString("6778e8c2-985a-4d54-9bd3-0f6403b104d6");
    private static final UUID POLICY = UUID.fromString("a23ba061-f742-4b20-8b95-36a0e525c087");
    private static final UUID FAMILY_MEMBER = UUID.fromString("c45dc283-1964-4d42-8d17-58c2a747e209");
    private static final UUID ATTACHMENT = UUID.fromString("3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e21");
    private static final UUID CONSENT_ATTACHMENT = UUID.fromString("4d3a9f21-6b8c-4e32-8a7f-2c1b8d5e9f32");
    private static final DocumentAttachment CERT_OWNER = DocumentAttachments.certificate(REQUEST, PARTICIPANT, ATTACHMENT);
    private static final DocumentAttachment CONSENT_OWNER = DocumentAttachments.consent(POLICY, FAMILY_MEMBER,
            CONSENT_ATTACHMENT);
    private static final StoredFile FILE = new StoredFile("certificates/0b6c1d2e/" + ATTACHMENT + ".jpg",
            "7851d78af0fb98bbbda8933f49860a009ac7761c21a1136613c8a6704b156251", 76, "image/jpeg");

    @Autowired
    private PrintedFormRepository documents;

    @Autowired
    private IssuanceRequestRepository requests;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void knownRequest() {
        jdbc.sql("truncate table contract_documents, request_participants, issuance_requests cascade").update();
        transaction.executeWithoutResult(status -> {
            IssuanceRequest request = IssuanceRequest.start(REQUEST, NOW);
            request.takeComposition(List.of(new RequestParticipant(PARTICIPANT, DocumentKind.LM_POLICY)), NOW);
            requests.save(request);
        });
    }

    @Test
    void earlyCertificateWithoutIssuanceRequest_isSavedAndFound() {
        DocumentAttachment early = DocumentAttachments.certificate(EARLY_REQUEST, PARTICIPANT, ATTACHMENT);
        documents.saveAndFlush(PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE, early, FILE, NOW));

        assertThat(documents.findByAttachmentAttachmentId(ATTACHMENT)).get().satisfies(found -> {
            assertThat(found.attachment()).contains(early);
            assertThat(found.storedFile()).contains(FILE);
            assertThat(found.docType()).isEqualTo(PrintedFormType.MEDICAL_CERTIFICATE);
            assertThat(found.lmPolicyId()).isEmpty();
            assertThat(found.createdAt()).isEqualTo(NOW);
        });
        assertThat(jdbc.sql("select doc_type::text from contract_documents").query(String.class).single())
                .isEqualTo("MEDICAL_CERTIFICATE");
    }

    @Test
    void consent_isSavedAndFoundWithPolicyAndFamilyMember() {
        documents.saveAndFlush(PrintedForm.receivedAttachment(PrintedFormType.PD_CONSENT, CONSENT_OWNER, FILE, NOW));

        assertThat(documents.findByAttachmentAttachmentId(CONSENT_ATTACHMENT)).get().satisfies(found -> {
            assertThat(found.attachment()).contains(CONSENT_OWNER);
            assertThat(found.storedFile()).contains(FILE);
            assertThat(found.docType()).isEqualTo(PrintedFormType.PD_CONSENT);
            assertThat(found.contractId()).isEmpty();
            assertThat(found.lmPolicyId()).isEmpty();
        });
        assertThat(jdbc.sql("select doc_type::text from contract_documents").query(String.class).single())
                .isEqualTo("PD_CONSENT");
    }

    @Test
    void activeCertificateQuery_excludesDeletedCertificatesAndConsents() {
        UUID deletedAttachment = UUID.randomUUID();
        documents.saveAndFlush(PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE, CERT_OWNER, FILE, NOW));
        documents.saveAndFlush(PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE,
                new DocumentAttachment(REQUEST, PARTICIPANT, null, null, deletedAttachment, NOW, "req-del"), FILE, NOW));
        documents.saveAndFlush(PrintedForm.receivedAttachment(PrintedFormType.PD_CONSENT, CONSENT_OWNER, FILE, NOW));

        List<PrintedForm> active = documents
                .findByAttachmentIndividualRequestIdAndAttachmentParticipantIdAndDocTypeAndAttachmentDeletedAtIsNullOrderByCreatedAtAscIdAsc(
                        REQUEST, PARTICIPANT, PrintedFormType.MEDICAL_CERTIFICATE);

        assertThat(active).singleElement().satisfies(found -> {
            assertThat(found.attachment()).contains(CERT_OWNER);
            assertThat(found.docType()).isEqualTo(PrintedFormType.MEDICAL_CERTIFICATE);
        });
    }

    @Test
    void unknownAttachment_isNotFound() {
        assertThat(documents.findByAttachmentAttachmentId(ATTACHMENT)).isEmpty();
    }

    @Test
    void markDeletedByAttachmentId_marksActiveRowOnceClearsPersistenceContextAndIgnoresRepeatOrUnknown() {
        Instant deletedAt = NOW.plusSeconds(10);
        documents.saveAndFlush(PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE, CERT_OWNER, FILE, NOW));

        transaction.executeWithoutResult(status -> {
            assertThat(documents.findByAttachmentAttachmentId(ATTACHMENT)).isPresent();
            assertThat(documents.existsByAttachmentAttachmentIdAndAttachmentDeletedAtIsNull(ATTACHMENT)).isTrue();
            assertThat(documents.markDeletedByAttachmentId(ATTACHMENT, deletedAt, "req-first")).isEqualTo(1);
            assertThat(documents.existsByAttachmentAttachmentIdAndAttachmentDeletedAtIsNull(ATTACHMENT)).isFalse();
            DocumentAttachment cleared = documents.findByAttachmentAttachmentId(ATTACHMENT).orElseThrow()
                    .attachment().orElseThrow();
            assertThat(cleared.deletedAt()).isEqualTo(deletedAt);
            assertThat(cleared.deletedRequestId()).isEqualTo("req-first");
            assertThat(documents.markDeletedByAttachmentId(ATTACHMENT, NOW.plusSeconds(99), "req-second")).isZero();
            assertThat(documents.markDeletedByAttachmentId(UUID.randomUUID(), deletedAt, "req-missing")).isZero();
            assertThat(documents.existsByAttachmentAttachmentIdAndAttachmentDeletedAtIsNull(UUID.randomUUID()))
                    .isFalse();
        });

        DocumentAttachment afterRepeat = documents.findByAttachmentAttachmentId(ATTACHMENT).orElseThrow()
                .attachment().orElseThrow();
        assertThat(afterRepeat.deletedAt()).isEqualTo(deletedAt);
        assertThat(afterRepeat.deletedRequestId()).isEqualTo("req-first");
    }

    @Test
    void secondRecordOfTheSameAttachmentEvenAcrossKinds_isRefusedByTheDatabase() {
        documents.saveAndFlush(PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE, CERT_OWNER, FILE, NOW));
        DocumentAttachment sameIdConsent = DocumentAttachments.consent(POLICY, FAMILY_MEMBER, ATTACHMENT);

        assertThatThrownBy(() -> documents.saveAndFlush(
                PrintedForm.receivedAttachment(PrintedFormType.PD_CONSENT, sameIdConsent, FILE, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void singleOwnerConstraint_rejectsZeroAndTwoOwners() {
        assertThatThrownBy(() -> jdbc.sql("insert into contract_documents (id, kind, doc_type, version, created_at, file_key) "
                        + "values (gen_random_uuid(), 'FINAL', 'POLICY', 1, now(), 'k')").update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_contract_documents_single_owner");

        assertThatThrownBy(() -> jdbc.sql("insert into contract_documents (id, kind, doc_type, version, created_at, "
                        + "contract_id, lm_policy_id, usk_file_url) "
                        + "values (gen_random_uuid(), 'FINAL', 'POLICY', 1, now(), gen_random_uuid(), gen_random_uuid(), "
                        + "'https://s3/x.pdf')")
                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_contract_documents_single_owner");
    }

    @Test
    void attachmentOwnerAndFileConstraints_rejectMixedOrIncompleteColumns() {
        assertThatThrownBy(() -> insert("MEDICAL_CERTIFICATE", "individual_request_id, participant_id, attachment_id",
                ":request, :participant, :attachment"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_contract_documents_attachment_file");

        assertThatThrownBy(() -> insert("MEDICAL_CERTIFICATE",
                "individual_request_id, attachment_id, file_key, file_hash, file_size, content_type",
                ":request, :attachment, 'k', 'h', 1, 'image/png'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_contract_documents_attachment_owner");

        assertThatThrownBy(() -> insert("PD_CONSENT",
                "policy_id, attachment_id, file_key, file_hash, file_size, content_type",
                ":policy, :attachment, 'k', 'h', 1, 'image/png'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_contract_documents_attachment_owner");

        assertThatThrownBy(() -> insert("MEDICAL_CERTIFICATE",
                "individual_request_id, participant_id, family_member_id, attachment_id, file_key, file_hash, file_size, content_type",
                ":request, :participant, :familyMember, :attachment, 'k', 'h', 1, 'image/png'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_contract_documents_attachment_owner");

        assertThatThrownBy(() -> insert("PD_CONSENT",
                "policy_id, family_member_id, participant_id, attachment_id, file_key, file_hash, file_size, content_type",
                ":policy, :familyMember, :participant, :attachment, 'k', 'h', 1, 'image/png'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_contract_documents_attachment_owner");

        assertThatThrownBy(() -> insert("POLICY",
                "individual_request_id, participant_id, attachment_id, file_key, file_hash, file_size, content_type",
                ":request, :participant, :attachment, 'k', 'h', 1, 'image/png'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_contract_documents_attachment_owner");
    }

    @Test
    void deletionConstraint_rejectsMismatchedOrBlankDeletionMetadataAndDeletedPrintedForm() {
        assertThatThrownBy(() -> insert("MEDICAL_CERTIFICATE",
                "individual_request_id, participant_id, attachment_id, file_key, file_hash, file_size, content_type, deleted_at",
                ":request, :participant, :attachment, 'k', 'h', 1, 'image/png', now()"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_contract_documents_deletion");

        assertThatThrownBy(() -> insert("MEDICAL_CERTIFICATE",
                "individual_request_id, participant_id, attachment_id, file_key, file_hash, file_size, content_type, deleted_request_id",
                ":request, :participant, :attachment, 'k', 'h', 1, 'image/png', 'req-1'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_contract_documents_deletion");

        assertThatThrownBy(() -> insert("PD_CONSENT",
                "policy_id, family_member_id, attachment_id, file_key, file_hash, file_size, content_type, deleted_at, deleted_request_id",
                ":policy, :familyMember, :attachment, 'k', 'h', 1, 'image/png', now(), '   '"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_contract_documents_deletion");

        assertThatThrownBy(() -> jdbc.sql("insert into contract_documents (id, kind, doc_type, version, created_at, "
                        + "contract_id, usk_file_url, deleted_at, deleted_request_id) "
                        + "values (gen_random_uuid(), 'FINAL', 'CONTRACT', 1, now(), gen_random_uuid(), 'https://s3/x.pdf', now(), 'req-1')")
                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_contract_documents_attachment_owner");
    }

    @Test
    void rollbackOfMigration015AndReapply_cleansConsentsAndEarlyCertificatesWhileKeepingLinkedCertificate()
            throws Exception {
        UUID earlyAttachment = UUID.randomUUID();
        documents.saveAndFlush(PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE, CERT_OWNER, FILE, NOW));
        documents.saveAndFlush(PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE,
                new DocumentAttachment(REQUEST, PARTICIPANT, null, null, UUID.randomUUID(), NOW, "req-del"), FILE, NOW));
        documents.saveAndFlush(PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE,
                DocumentAttachments.certificate(EARLY_REQUEST, PARTICIPANT, earlyAttachment), FILE, NOW));
        documents.saveAndFlush(PrintedForm.receivedAttachment(PrintedFormType.PD_CONSENT, CONSENT_OWNER, FILE, NOW));

        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(), database);
            try {
                liquibase.rollback(1, new Contexts(), new LabelExpression());

                List<UUID> remaining = jdbc.sql("select attachment_id from contract_documents")
                        .query(UUID.class).list();
                assertThat(remaining).containsExactly(ATTACHMENT);
                assertThatThrownBy(() -> jdbc.sql("insert into contract_documents (id, kind, doc_type, version, created_at, "
                                + "individual_request_id, participant_id, attachment_id, file_key, file_hash, file_size, content_type) "
                                + "values (gen_random_uuid(), 'FINAL', 'MEDICAL_CERTIFICATE', 1, now(), :early, :participant, "
                                + ":att, 'k', 'h', 1, 'image/jpeg')")
                        .param("early", EARLY_REQUEST).param("participant", PARTICIPANT)
                        .param("att", earlyAttachment).update())
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("fk_contract_documents_request");
            } finally {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }

        documents.saveAndFlush(PrintedForm.receivedAttachment(PrintedFormType.PD_CONSENT, CONSENT_OWNER, FILE, NOW));
        assertThat(documents.findByAttachmentAttachmentId(CONSENT_ATTACHMENT)).isPresent();
    }

    private void insert(String docType, String columns, String values) {
        jdbc.sql("insert into contract_documents (id, kind, doc_type, version, created_at, " + columns + ") values "
                        + "(gen_random_uuid(), 'FINAL', '" + docType + "', 1, now(), " + values + ")")
                .param("request", REQUEST)
                .param("participant", PARTICIPANT)
                .param("policy", POLICY)
                .param("familyMember", FAMILY_MEMBER)
                .param("attachment", ATTACHMENT)
                .update();
    }
}
