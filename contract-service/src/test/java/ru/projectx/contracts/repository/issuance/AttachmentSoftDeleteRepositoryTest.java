package ru.projectx.contracts.repository.issuance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import ru.projectx.contracts.entity.document.DocumentAttachment;
import ru.projectx.contracts.entity.document.DocumentAttachments;
import ru.projectx.contracts.entity.document.PrintedForm;
import ru.projectx.contracts.entity.document.PrintedFormType;
import ru.projectx.contracts.entity.document.StoredFile;
import ru.projectx.contracts.entity.document.UskDocument;
import ru.projectx.contracts.entity.party.PersonOwner;
import ru.projectx.contracts.support.IntegrationTest;

@IntegrationTest
class AttachmentSoftDeleteRepositoryTest {

    private static final Instant CREATED = Instant.parse("2026-02-10T07:05:00Z");
    private static final Instant DELETED = Instant.parse("2026-02-10T07:10:00Z");
    private static final Instant LATER = Instant.parse("2026-02-10T07:25:00Z");
    private static final UUID REQUEST = UUID.fromString("61a7982c-a0b4-4bbf-9527-60b40b752803");
    private static final UUID PARTICIPANT = UUID.fromString("6778e8c2-985a-4d54-9bd3-0f6403b104d6");
    private static final UUID POLICY = UUID.fromString("a23ba061-f742-4b20-8b95-36a0e525c087");
    private static final UUID FAMILY_MEMBER = UUID.fromString("c45dc283-1964-4d42-8d17-58c2a747e209");
    private static final UUID CERT_ATTACHMENT = UUID.fromString("3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e21");
    private static final UUID CONSENT_ATTACHMENT = UUID.fromString("4d3a9f21-6b8c-4e32-8a7f-2c1b8d5e9f32");
    private static final StoredFile FILE = new StoredFile("certificates/0b6c1d2e/" + CERT_ATTACHMENT + ".jpg",
            "7851d78af0fb98bbbda8933f49860a009ac7761c21a1136613c8a6704b156251", 76, "image/jpeg");

    @Autowired
    private PrintedFormRepository documents;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void emptyTable() {
        jdbc.sql("truncate table contract_documents cascade").update();
    }

    @Test
    void markDeletedByAttachmentId_updatesActiveCertificateAndConsentAndRefreshesContext() {
        documents.saveAndFlush(PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE,
                DocumentAttachments.certificate(REQUEST, PARTICIPANT, CERT_ATTACHMENT), FILE, CREATED));
        documents.saveAndFlush(PrintedForm.receivedAttachment(PrintedFormType.PD_CONSENT,
                DocumentAttachments.consent(POLICY, FAMILY_MEMBER, CONSENT_ATTACHMENT), FILE, CREATED));

        transaction.executeWithoutResult(status -> {
            PrintedForm preloaded = documents.findByAttachmentAttachmentId(CERT_ATTACHMENT).orElseThrow();
            assertThat(preloaded.attachment()).contains(
                    DocumentAttachments.certificate(REQUEST, PARTICIPANT, CERT_ATTACHMENT));

            int certUpdated = documents.markDeletedByAttachmentId(CERT_ATTACHMENT, DELETED, "req-del-1");
            int consentUpdated = documents.markDeletedByAttachmentId(CONSENT_ATTACHMENT, DELETED, "req-del-2");

            assertThat(List.of(certUpdated, consentUpdated)).containsExactly(1, 1);
            PrintedForm reloaded = documents.findByAttachmentAttachmentId(CERT_ATTACHMENT).orElseThrow();
            assertThat(reloaded.attachment()).contains(new DocumentAttachment(
                    REQUEST, PARTICIPANT, null, null, CERT_ATTACHMENT, DELETED, "req-del-1"));
        });

        PrintedForm consent = documents.findByAttachmentAttachmentId(CONSENT_ATTACHMENT).orElseThrow();
        assertThat(consent.attachment()).contains(new DocumentAttachment(
                null, null, POLICY, FAMILY_MEMBER, CONSENT_ATTACHMENT, DELETED, "req-del-2"));
    }

    @Test
    void markDeletedByAttachmentId_doesNotOverwriteAlreadySoftDeletedAttachment() {
        documents.saveAndFlush(PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE,
                new DocumentAttachment(REQUEST, PARTICIPANT, null, null, CERT_ATTACHMENT, DELETED, "req-first"),
                FILE, CREATED));

        int updated = transaction.execute(status ->
                documents.markDeletedByAttachmentId(CERT_ATTACHMENT, LATER, "req-second"));

        assertThat(updated).isZero();
        PrintedForm preserved = documents.findByAttachmentAttachmentId(CERT_ATTACHMENT).orElseThrow();
        assertThat(preserved.attachment()).contains(new DocumentAttachment(
                REQUEST, PARTICIPANT, null, null, CERT_ATTACHMENT, DELETED, "req-first"));
    }

    @Test
    void markDeletedByAttachmentId_ignoresUnknownAttachmentAndUskPrintedForm() {
        UUID contractId = UUID.randomUUID();
        jdbc.sql("insert into contracts (id, number, individual_request_id, insurance_contract_id, insurer_policy_id, "
                        + "policyholder_participant_id, insured_participant_id, date_from, date_to, premium_amount, "
                        + "status, entity_version, created_at, updated_at) values (:id, 'DKM-2026-000099', :request, "
                        + "gen_random_uuid(), gen_random_uuid(), :participant, :participant, '2026-04-10', '2026-04-24', "
                        + "34000.00, 'ISSUED', 1, :now, :now)")
                .param("id", contractId)
                .param("request", REQUEST)
                .param("participant", PARTICIPANT)
                .param("now", java.time.OffsetDateTime.parse("2026-02-10T07:05:00Z"))
                .update();
        PrintedForm form = documents.saveAndFlush(PrintedForm.issuedFor(PersonOwner.contract(contractId),
                new UskDocument(PrintedFormType.CONTRACT, "https://1c-usk.example/form.pdf", 1024L), CREATED));

        int unknownUpdated = transaction.execute(status ->
                documents.markDeletedByAttachmentId(UUID.randomUUID(), DELETED, "req-1"));
        int formUpdated = transaction.execute(status ->
                documents.markDeletedByAttachmentId(form.id(), DELETED, "req-2"));

        assertThat(List.of(unknownUpdated, formUpdated)).containsExactly(0, 0);
        assertThat(documents.findById(form.id())).get().satisfies(saved -> {
            assertThat(saved.attachment()).isEmpty();
            assertThat(saved.uskFileUrl()).contains("https://1c-usk.example/form.pdf");
        });
        jdbc.sql("truncate table contract_documents, contracts cascade").update();
    }
}
