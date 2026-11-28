package ru.projectx.contracts.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.projectx.contracts.entity.document.DocumentAttachment;
import ru.projectx.contracts.entity.document.DocumentAttachments;
import ru.projectx.contracts.entity.document.PrintedForm;
import ru.projectx.contracts.entity.document.PrintedFormType;
import ru.projectx.contracts.entity.document.StoredFile;
import ru.projectx.contracts.entity.document.UskDocument;
import ru.projectx.contracts.entity.party.PersonOwner;
import ru.projectx.contracts.exception.AttachmentNotFoundException;
import ru.projectx.contracts.exception.CertificateMissingException;
import ru.projectx.contracts.exception.StorageUnavailableException;
import ru.projectx.contracts.repository.issuance.IssuanceRequestRepository;
import ru.projectx.contracts.repository.issuance.PrintedFormRepository;

class AttachmentContentServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-10T07:05:00Z");
    private static final byte[] BYTES = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0x80, (byte) 0xfe, 0x00};
    private static final String SECRET_KEY = "1111111111_222222222/certificates/secret-folder/" + Uploads.ATTACHMENT + ".jpg";
    private static final StoredFile FILE = new StoredFile(SECRET_KEY, Uploads.CONTENT_HASH, BYTES.length, "image/jpeg");

    private final IssuanceRequestRepository requests = mock(IssuanceRequestRepository.class);
    private final PrintedFormRepository documents = mock(PrintedFormRepository.class);
    private final CertificateStore store = mock(CertificateStore.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AttachmentMetrics metrics = new AttachmentMetrics(meters);
    private final AttachmentRegistry registry = new AttachmentRegistry(requests, documents,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final AttachmentContentService service = new AttachmentContentService(registry, store, metrics);

    @Test
    void activeCertificateAndConsent_openStreamOutsideTransactionWithoutBufferingIntoByteArray() throws Exception {
        PrintedForm activeCert = PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE,
                DocumentAttachments.certificate(Uploads.REQUEST, Uploads.PARTICIPANT, Uploads.ATTACHMENT), FILE, NOW);
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(activeCert));
        AtomicBoolean readBeforeController = new AtomicBoolean();
        AtomicBoolean txActiveOnS3 = new AtomicBoolean(true);
        InputStream rawStream = new ByteArrayInputStream(BYTES) {
            @Override
            public int read() {
                readBeforeController.set(true);
                return super.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                readBeforeController.set(true);
                return super.read(b, off, len);
            }
        };
        when(store.openStream(SECRET_KEY)).thenAnswer(call -> {
            txActiveOnS3.set(TransactionSynchronizationManager.isActualTransactionActive());
            return rawStream;
        });

        AttachmentStream opened = service.openContent(Uploads.ATTACHMENT);

        assertThat(txActiveOnS3).isFalse();
        assertThat(readBeforeController).isFalse();
        assertThat(opened.contentType()).isEqualTo("image/jpeg");
        assertThat(opened.fileSize()).isEqualTo(BYTES.length);
        assertThat(opened.fileHash()).isEqualTo(Uploads.CONTENT_HASH);
        assertThat(opened.inputStream()).isSameAs(rawStream);
        assertThat(opened.inputStream().readAllBytes()).isEqualTo(BYTES);
        verifyNoInteractions(requests);
        assertThat(meters.find("contracts.storage.unavailable").counter()).isNull();
    }

    @Test
    void unknownPrintedFormOrSoftDeletedAttachment_throwsNotFoundWithoutCallingStorage() {
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.openContent(Uploads.ATTACHMENT))
                .isInstanceOf(AttachmentNotFoundException.class)
                .hasMessage("вложения " + Uploads.ATTACHMENT + " нет");

        PrintedForm uskForm = PrintedForm.issuedFor(PersonOwner.contract(UUID.randomUUID()),
                new UskDocument(PrintedFormType.CONTRACT, "https://1c-usk.example/form.pdf", 100L), NOW);
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(uskForm));

        assertThatThrownBy(() -> service.openContent(Uploads.ATTACHMENT))
                .isInstanceOf(AttachmentNotFoundException.class);

        PrintedForm deleted = PrintedForm.receivedAttachment(PrintedFormType.PD_CONSENT,
                new DocumentAttachment(null, null, Uploads.POLICY, Uploads.FAMILY_MEMBER, Uploads.ATTACHMENT, NOW, "r1"),
                FILE, NOW);
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.openContent(Uploads.ATTACHMENT))
                .isInstanceOf(AttachmentNotFoundException.class);

        verifyNoInteractions(store);
        assertThat(meters.find("contracts.storage.unavailable").counter()).isNull();
    }

    @Test
    void missingObjectInLiveBucket_logsWarnWithoutFileKeyOrThrowableAndThrowsNotFoundWithoutCountingUnavailable() {
        PrintedForm active = PrintedForm.receivedAttachment(PrintedFormType.PD_CONSENT,
                DocumentAttachments.consent(Uploads.POLICY, Uploads.FAMILY_MEMBER, Uploads.ATTACHMENT), FILE, NOW);
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(active));
        CertificateMissingException missing = new CertificateMissingException(new IOException(SECRET_KEY));
        when(store.openStream(SECRET_KEY)).thenThrow(missing);

        Logger logger = (Logger) LoggerFactory.getLogger(AttachmentContentService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> service.openContent(Uploads.ATTACHMENT))
                    .isInstanceOf(AttachmentNotFoundException.class)
                    .hasCause(missing)
                    .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(SECRET_KEY));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("вложение " + Uploads.ATTACHMENT + ": объекта вложения нет в хранилище")
                    .doesNotContain(SECRET_KEY);
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertThat(meters.find("contracts.storage.unavailable").counter()).isNull();
    }

    @Test
    void storageUnavailable_incrementsUnavailableCounterAndRethrows() {
        PrintedForm active = PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE,
                DocumentAttachments.certificate(Uploads.REQUEST, Uploads.PARTICIPANT, Uploads.ATTACHMENT), FILE, NOW);
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(active));
        StorageUnavailableException unavailable = new StorageUnavailableException(new IOException("down"));
        when(store.openStream(SECRET_KEY)).thenThrow(unavailable);

        assertThatThrownBy(() -> service.openContent(Uploads.ATTACHMENT)).isSameAs(unavailable);

        assertThat(meters.get("contracts.storage.unavailable").counter().count()).isEqualTo(1);
    }

    @Test
    void attachmentRecordWithoutStoredFile_isIllegalState() {
        PrintedForm broken = new PrintedForm() {
            @Override
            public UUID id() {
                return Uploads.ATTACHMENT;
            }

            @Override
            public Optional<DocumentAttachment> attachment() {
                return Optional.of(DocumentAttachments.consent(Uploads.POLICY, Uploads.FAMILY_MEMBER, Uploads.ATTACHMENT));
            }

            @Override
            public Optional<StoredFile> storedFile() {
                return Optional.empty();
            }
        };
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(broken));

        assertThatThrownBy(() -> service.openContent(Uploads.ATTACHMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("у вложения " + Uploads.ATTACHMENT + " нет файла");
        verifyNoInteractions(store);
    }
}
