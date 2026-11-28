package ru.projectx.contracts.entity.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.projectx.contracts.entity.party.PersonOwner;

class PrintedFormTest {

    private static final Instant NOW = Instant.parse("2026-02-10T07:05:00Z");
    private static final DocumentAttachment ATTACHMENT = DocumentAttachments.certificate(
            UUID.fromString("61a7982c-a0b4-4bbf-9527-60b40b752803"),
            UUID.fromString("6778e8c2-985a-4d54-9bd3-0f6403b104d6"),
            UUID.fromString("3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e21"));
    private static final DocumentAttachment CONSENT = DocumentAttachments.consent(
            UUID.fromString("a23ba061-f742-4b20-8b95-36a0e525c087"),
            UUID.fromString("c45dc283-1964-4d42-8d17-58c2a747e209"),
            UUID.fromString("4d3a9f21-6b8c-4e32-8a7f-2c1b8d5e9f32"));
    private static final StoredFile FILE = new StoredFile(
            "certificates/0b6c1d2e-7f3a-4b5c-9d8e-1f2a3b4c5d6e/3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e21.jpg",
            "7851d78af0fb98bbbda8933f49860a009ac7761c21a1136613c8a6704b156251", 76L, "image/jpeg");

    @Test
    void receivedCertificate_belongsToTheParticipantAttachmentAndNotToAPolicy() {
        PrintedForm certificate = PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE, ATTACHMENT, FILE,
                NOW);

        assertThat(certificate.attachment()).contains(ATTACHMENT);
        assertThat(certificate.lmPolicyId()).isEmpty();
        assertThat(certificate).hasFieldOrPropertyWithValue("contractId", null);
        assertThat(certificate.uskFileUrl()).isEmpty();
    }

    @Test
    void receivedCertificate_isAFinalMedicalCertificateOfFirstVersionWithItsStoredFile() {
        PrintedForm certificate = PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE, ATTACHMENT, FILE,
                NOW);

        assertThat(certificate.docType()).isEqualTo(PrintedFormType.MEDICAL_CERTIFICATE);
        assertThat(certificate.kind()).isEqualTo(PrintedFormKind.FINAL);
        assertThat(certificate.storedFile()).contains(FILE);
        assertThat(certificate.createdAt()).isEqualTo(NOW);
        assertThat(certificate).hasFieldOrPropertyWithValue("version", 1);
    }

    @Test
    void receivedConsent_isAFinalPdConsentWithPolicyAndFamilyMember() {
        PrintedForm consent = PrintedForm.receivedAttachment(PrintedFormType.PD_CONSENT, CONSENT, FILE, NOW);

        assertThat(consent.docType()).isEqualTo(PrintedFormType.PD_CONSENT);
        assertThat(consent.attachment()).contains(CONSENT);
        assertThat(DocumentAttachments.isActive(CONSENT)).isTrue();
        assertThat(DocumentAttachments.hasActiveAttachmentId(CONSENT)).isTrue();
        assertThat(DocumentAttachments.hasActiveAttachmentId(
                new DocumentAttachment(null, null, CONSENT.policyId(), CONSENT.familyMemberId(), null, null, null)))
                .isFalse();
        DocumentAttachment deleted = new DocumentAttachment(null, null, CONSENT.policyId(),
                CONSENT.familyMemberId(), CONSENT.attachmentId(), NOW, "req-1");
        assertThat(DocumentAttachments.isActive(deleted)).isFalse();
        assertThat(DocumentAttachments.hasActiveAttachmentId(deleted)).isFalse();
    }

    @Test
    void lmPolicyForm_hasNoAttachmentAndNoStoredFile() {
        PrintedForm form = PrintedForm.issuedFor(PersonOwner.lmPolicy(ATTACHMENT.participantId()),
                new UskDocument(PrintedFormType.POLICY, "https://s3.example.test/lm/0555123456.pdf", 96256L), NOW);

        assertThat(form.attachment()).isEmpty();
        assertThat(form.storedFile()).isEmpty();
        assertThat(form.lmPolicyId()).contains(ATTACHMENT.participantId());
    }

    @Test
    void lmPolicyForm_isTheFirstVersion() {
        PrintedForm form = PrintedForm.issuedFor(PersonOwner.lmPolicy(ATTACHMENT.participantId()),
                new UskDocument(PrintedFormType.POLICY, "https://s3.example.test/lm/0555123456.pdf", 96256L), NOW);

        assertThat(form.version()).isEqualTo(1);
    }

    @Test
    void contractForm_belongsToTheContractOnlyAndCarriesTheUskLinkAndSize() {
        PrintedForm form = PrintedForm.issuedFor(PersonOwner.contract(ATTACHMENT.individualRequestId()),
                new UskDocument(PrintedFormType.CONTRACT, "https://s3.example.test/km/DKM-2026-000001.pdf", 205312L), NOW);

        assertThat(form.contractId()).contains(ATTACHMENT.individualRequestId());
        assertThat(form.lmPolicyId()).isEmpty();
        assertThat(form.attachment()).isEmpty();
        assertThat(form.docType()).isEqualTo(PrintedFormType.CONTRACT);
        assertThat(form.kind()).isEqualTo(PrintedFormKind.FINAL);
        assertThat(form.uskFileUrl()).contains("https://s3.example.test/km/DKM-2026-000001.pdf");
        assertThat(form).hasFieldOrPropertyWithValue("fileSize", 205312L);
    }

    @Test
    void lmPolicyForm_belongsToNoContract() {
        PrintedForm form = PrintedForm.issuedFor(PersonOwner.lmPolicy(ATTACHMENT.participantId()),
                new UskDocument(PrintedFormType.POLICY, "https://s3.example.test/lm/0555123456.pdf", 96256L), NOW);

        assertThat(form.contractId()).isEmpty();
    }

    @Test
    void onlyContractPolicyAndAppendix_areIssuedByTheUsk() {
        assertThat(PrintedFormType.values()).filteredOn(PrintedFormType::issuedByUsk)
                .containsExactly(PrintedFormType.CONTRACT, PrintedFormType.POLICY, PrintedFormType.APPENDIX);
    }
}
