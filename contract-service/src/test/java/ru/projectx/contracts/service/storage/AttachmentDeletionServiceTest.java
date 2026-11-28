package ru.projectx.contracts.service.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.projectx.contracts.entity.contract.Contract;
import ru.projectx.contracts.entity.document.DocumentAttachment;
import ru.projectx.contracts.entity.document.DocumentAttachments;
import ru.projectx.contracts.entity.document.PrintedForm;
import ru.projectx.contracts.entity.document.PrintedFormType;
import ru.projectx.contracts.entity.document.StoredFile;
import ru.projectx.contracts.entity.document.UskDocument;
import ru.projectx.contracts.entity.issuance.CancelReason;
import ru.projectx.contracts.entity.issuance.Cancellation;
import ru.projectx.contracts.entity.issuance.IssueError;
import ru.projectx.contracts.entity.party.PersonOwner;
import ru.projectx.contracts.exception.AttachmentBusyException;
import ru.projectx.contracts.exception.AttachmentLockedException;
import ru.projectx.contracts.exception.StorageUnavailableException;
import ru.projectx.contracts.message.usk.KmContractEvents;
import ru.projectx.contracts.repository.issuance.ContractRepository;
import ru.projectx.contracts.repository.issuance.PrintedFormRepository;
import ru.projectx.contracts.repository.journal.OutboxMessageRepository;
import ru.projectx.contracts.service.RequestIds;
import ru.projectx.contracts.support.Issuances;

class AttachmentDeletionServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-10T07:10:00Z");
    private static final String TRACE = "req-delete-01";
    private static final String FIRST_CONTRACT = "0f4b8e2a-6c13-4d7e-b9a1-2e5c7d3f8a64";
    private static final String SECOND_CONTRACT = "1f4b8e2a-6c13-4d7e-b9a1-2e5c7d3f8a65";
    private static final String KEY = "1111111111_222222222/certificates/0b6c/" + Uploads.ATTACHMENT + ".jpg";
    private static final StoredFile FILE = new StoredFile(KEY, Uploads.CONTENT_HASH, 76L, "image/jpeg");

    private final PrintedFormRepository documents = mock(PrintedFormRepository.class);
    private final ContractRepository contracts = mock(ContractRepository.class);
    private final OutboxMessageRepository outbox = mock(OutboxMessageRepository.class);
    private final CertificateStore store = mock(CertificateStore.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AttachmentMetrics metrics = new AttachmentMetrics(meters);
    private final AttachmentDeletionTransactions transactions = new AttachmentDeletionTransactions(
            documents, contracts, outbox, Clock.fixed(NOW, ZoneOffset.UTC));
    private final AttachmentDeletionService service = new AttachmentDeletionService(transactions, store, metrics);

    @AfterEach
    void clearMdc() {
        MDC.remove(RequestIds.MDC_KEY);
    }

    @Test
    void activeConsent_isRemovedFromStorageOutsideTransactionAndMarkedDeletedWithoutCheckingContracts() {
        MDC.put(RequestIds.MDC_KEY, TRACE);
        PrintedForm consent = PrintedForm.receivedAttachment(PrintedFormType.PD_CONSENT,
                DocumentAttachments.consent(Uploads.POLICY, Uploads.FAMILY_MEMBER, Uploads.ATTACHMENT), FILE,
                NOW.minusSeconds(300));
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(consent));
        when(documents.existsByAttachmentAttachmentIdAndAttachmentDeletedAtIsNull(Uploads.ATTACHMENT)).thenReturn(true);
        AtomicBoolean txActiveOnRemove = new AtomicBoolean(true);
        doAnswer(call -> {
            txActiveOnRemove.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(store).remove(KEY);

        service.delete(Uploads.ATTACHMENT);

        assertThat(txActiveOnRemove).isFalse();
        InOrder order = inOrder(documents, store);
        order.verify(documents).findByAttachmentAttachmentId(Uploads.ATTACHMENT);
        order.verify(store).remove(KEY);
        order.verify(documents).existsByAttachmentAttachmentIdAndAttachmentDeletedAtIsNull(Uploads.ATTACHMENT);
        order.verify(documents).markDeletedByAttachmentId(Uploads.ATTACHMENT, NOW, TRACE);
        verifyNoInteractions(contracts, outbox);
    }

    @Test
    void activeCertificateBeforeFirstTask_skipsLockingAndDeletesFileAndMarksRecord() {
        MDC.put(RequestIds.MDC_KEY, TRACE);
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(activeCertificate()));
        when(documents.existsByAttachmentAttachmentIdAndAttachmentDeletedAtIsNull(Uploads.ATTACHMENT)).thenReturn(true);
        when(contracts.findByRequestIndividualRequestIdAndInsuredParticipantId(Uploads.REQUEST, Uploads.PARTICIPANT))
                .thenReturn(List.of());

        service.delete(Uploads.ATTACHMENT);

        InOrder order = inOrder(contracts, store, documents);
        order.verify(contracts).findByRequestIndividualRequestIdAndInsuredParticipantId(
                Uploads.REQUEST, Uploads.PARTICIPANT);
        order.verify(store).remove(KEY);
        order.verify(documents).existsByAttachmentAttachmentIdAndAttachmentDeletedAtIsNull(Uploads.ATTACHMENT);
        order.verify(contracts).findByRequestIndividualRequestIdAndInsuredParticipantId(
                Uploads.REQUEST, Uploads.PARTICIPANT);
        order.verify(documents).markDeletedByAttachmentId(Uploads.ATTACHMENT, NOW, TRACE);
        verify(contracts, never()).lockFresh(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void activeCertificateWithPaidUnsubmittedContract_locksInBothPhasesDeletesFileAndMarksRecord() {
        MDC.put(RequestIds.MDC_KEY, TRACE);
        Contract paid = Issuances.paidContract(FIRST_CONTRACT, Uploads.PARTICIPANT, "DKM-2026-000001",
                NOW.minusSeconds(60));
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(activeCertificate()));
        when(documents.existsByAttachmentAttachmentIdAndAttachmentDeletedAtIsNull(Uploads.ATTACHMENT)).thenReturn(true);
        when(contracts.findByRequestIndividualRequestIdAndInsuredParticipantId(Uploads.REQUEST, Uploads.PARTICIPANT))
                .thenReturn(List.of(paid));
        when(outbox.existsByCorrelationIdAndAggregateTypeAndAggregateIdInAndEventType(
                Uploads.REQUEST, KmContractEvents.CONTRACT_AGGREGATE, List.of(paid.id()),
                KmContractEvents.KM_CONTRACT_SUBMITTED)).thenReturn(false);

        service.delete(Uploads.ATTACHMENT);

        InOrder order = inOrder(contracts, outbox, store, documents);
        order.verify(contracts).lockFresh(List.of(paid));
        order.verify(outbox).existsByCorrelationIdAndAggregateTypeAndAggregateIdInAndEventType(
                Uploads.REQUEST, KmContractEvents.CONTRACT_AGGREGATE, List.of(paid.id()),
                KmContractEvents.KM_CONTRACT_SUBMITTED);
        order.verify(store).remove(KEY);
        order.verify(documents).existsByAttachmentAttachmentIdAndAttachmentDeletedAtIsNull(Uploads.ATTACHMENT);
        order.verify(contracts).lockFresh(List.of(paid));
        order.verify(documents).existsByAttachmentAttachmentIdAndAttachmentDeletedAtIsNull(Uploads.ATTACHMENT);
        order.verify(outbox).existsByCorrelationIdAndAggregateTypeAndAggregateIdInAndEventType(
                Uploads.REQUEST, KmContractEvents.CONTRACT_AGGREGATE, List.of(paid.id()),
                KmContractEvents.KM_CONTRACT_SUBMITTED);
        order.verify(documents).markDeletedByAttachmentId(Uploads.ATTACHMENT, NOW, TRACE);
    }

    @Test
    void unknownUskPrintedFormOrAlreadyDeletedAttachment_returnsIdempotentlyWithoutTouchingStorageOrDatabase() {
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.empty());
        service.delete(Uploads.ATTACHMENT);

        PrintedForm uskForm = PrintedForm.issuedFor(PersonOwner.contract(UUID.randomUUID()),
                new UskDocument(PrintedFormType.CONTRACT, "https://1c-usk.example/form.pdf", 100L), NOW);
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(uskForm));
        service.delete(Uploads.ATTACHMENT);

        PrintedForm alreadyDeleted = PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE,
                new DocumentAttachment(Uploads.REQUEST, Uploads.PARTICIPANT, null, null, Uploads.ATTACHMENT,
                        NOW.minusSeconds(60), "req-prior"),
                FILE, NOW.minusSeconds(300));
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(alreadyDeleted));
        service.delete(Uploads.ATTACHMENT);

        verifyNoInteractions(contracts, outbox, store);
        verify(documents, never()).markDeletedByAttachmentId(any(), any(), any());
    }

    @Test
    void submittedOrIssuedContractWithoutOutboxLookup_blocksCertificateDeletionBeforeTouchingStorage() {
        Contract submitted = Issuances.paidContract(FIRST_CONTRACT, Uploads.PARTICIPANT, "DKM-2026-000001",
                NOW.minusSeconds(60));
        submitted.submit(NOW.minusSeconds(30));
        Contract issued = Issuances.issuedContract(SECOND_CONTRACT, Uploads.PARTICIPANT, "DKM-2026-000002");
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(activeCertificate()));
        when(contracts.findByRequestIndividualRequestIdAndInsuredParticipantId(Uploads.REQUEST, Uploads.PARTICIPANT))
                .thenReturn(List.of(submitted))
                .thenReturn(List.of(issued));

        assertThatThrownBy(() -> service.delete(Uploads.ATTACHMENT))
                .isInstanceOf(AttachmentLockedException.class)
                .hasMessageContaining(Uploads.ATTACHMENT.toString());
        assertThatThrownBy(() -> service.delete(Uploads.ATTACHMENT))
                .isInstanceOf(AttachmentLockedException.class);

        verify(contracts).lockFresh(List.of(submitted));
        verify(contracts).lockFresh(List.of(issued));
        verifyNoInteractions(outbox, store);
        verify(documents, never()).markDeletedByAttachmentId(any(), any(), any());
    }

    @Test
    void outboxSubmissionHistoryOnCancelledOrErrorContract_blocksCertificateDeletionBeforeTouchingStorage() {
        Contract cancelled = Issuances.contract(FIRST_CONTRACT, Uploads.PARTICIPANT, "DKM-2026-000001");
        cancelled.cancel(new Cancellation(CancelReason.REQUEST_CANCELLED, null, NOW), NOW);
        Contract reopened = Issuances.contract(SECOND_CONTRACT, Uploads.PARTICIPANT, "DKM-2026-000002");
        reopened.markError(new IssueError("KM_CONTRACT_SUBMISSION_UNDELIVERED", "не доставлен"), NOW);
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(activeCertificate()));
        when(contracts.findByRequestIndividualRequestIdAndInsuredParticipantId(Uploads.REQUEST, Uploads.PARTICIPANT))
                .thenReturn(List.of(cancelled, reopened));
        when(outbox.existsByCorrelationIdAndAggregateTypeAndAggregateIdInAndEventType(
                Uploads.REQUEST, KmContractEvents.CONTRACT_AGGREGATE, List.of(cancelled.id(), reopened.id()),
                KmContractEvents.KM_CONTRACT_SUBMITTED)).thenReturn(true);

        assertThatThrownBy(() -> service.delete(Uploads.ATTACHMENT))
                .isInstanceOf(AttachmentLockedException.class);

        verify(contracts).lockFresh(List.of(cancelled, reopened));
        verifyNoInteractions(store);
        verify(documents, never()).markDeletedByAttachmentId(any(), any(), any());
    }

    @Test
    void contractSubmittedDuringPhaseTwoS3Removal_blocksPhaseThreeBeforeMarkingDatabase() {
        MDC.put(RequestIds.MDC_KEY, TRACE);
        Contract paid = Issuances.paidContract(FIRST_CONTRACT, Uploads.PARTICIPANT, "DKM-2026-000001",
                NOW.minusSeconds(60));
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(activeCertificate()));
        when(documents.existsByAttachmentAttachmentIdAndAttachmentDeletedAtIsNull(Uploads.ATTACHMENT)).thenReturn(true);
        when(contracts.findByRequestIndividualRequestIdAndInsuredParticipantId(Uploads.REQUEST, Uploads.PARTICIPANT))
                .thenReturn(List.of(paid));
        when(outbox.existsByCorrelationIdAndAggregateTypeAndAggregateIdInAndEventType(
                Uploads.REQUEST, KmContractEvents.CONTRACT_AGGREGATE, List.of(paid.id()),
                KmContractEvents.KM_CONTRACT_SUBMITTED)).thenReturn(false);
        doAnswer(call -> {
            paid.submit(NOW);
            return null;
        }).when(store).remove(KEY);

        assertThatThrownBy(() -> service.delete(Uploads.ATTACHMENT))
                .isInstanceOf(AttachmentLockedException.class);

        verify(store).remove(KEY);
        verify(documents, never()).markDeletedByAttachmentId(any(), any(), any());
    }

    @Test
    void concurrentDeletionBeforeOrDuringPhaseThreeLock_completesIdempotentlyEvenAfterContractSubmission() {
        Contract firstPaid = Issuances.paidContract(FIRST_CONTRACT, Uploads.PARTICIPANT, "DKM-2026-000001",
                NOW.minusSeconds(60));
        Contract secondPaid = Issuances.paidContract(SECOND_CONTRACT, Uploads.PARTICIPANT, "DKM-2026-000002",
                NOW.minusSeconds(60));
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(activeCertificate()));
        when(contracts.findByRequestIndividualRequestIdAndInsuredParticipantId(Uploads.REQUEST, Uploads.PARTICIPANT))
                .thenReturn(List.of(firstPaid))
                .thenReturn(List.of(secondPaid));
        when(outbox.existsByCorrelationIdAndAggregateTypeAndAggregateIdInAndEventType(any(), any(), any(), any()))
                .thenReturn(false);
        when(documents.existsByAttachmentAttachmentIdAndAttachmentDeletedAtIsNull(Uploads.ATTACHMENT))
                .thenReturn(false)
                .thenReturn(true, false);
        AtomicInteger secondLocks = new AtomicInteger();
        doAnswer(call -> {
            if (secondLocks.incrementAndGet() == 2) {
                secondPaid.submit(NOW);
            }
            return null;
        }).when(contracts).lockFresh(List.of(secondPaid));

        service.delete(Uploads.ATTACHMENT);
        service.delete(Uploads.ATTACHMENT);

        verify(contracts, times(1)).lockFresh(List.of(firstPaid));
        verify(contracts, times(2)).lockFresh(List.of(secondPaid));
        verify(documents, never()).markDeletedByAttachmentId(any(), any(), any());
    }

    @Test
    void pessimisticLockFailureOnContract_throwsAttachmentBusyWithoutTouchingStorageOrDatabase() {
        Contract draft = Issuances.contract(FIRST_CONTRACT, Uploads.PARTICIPANT, "DKM-2026-000001");
        CannotAcquireLockException lockTimeout = new CannotAcquireLockException("55P03");
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(activeCertificate()));
        when(contracts.findByRequestIndividualRequestIdAndInsuredParticipantId(Uploads.REQUEST, Uploads.PARTICIPANT))
                .thenReturn(List.of(draft));
        doThrow(lockTimeout).when(contracts).lockFresh(List.of(draft));

        assertThatThrownBy(() -> service.delete(Uploads.ATTACHMENT))
                .isInstanceOf(AttachmentBusyException.class)
                .hasCause(lockTimeout)
                .hasMessage("договор КМ по вложению " + Uploads.ATTACHMENT + " занят другой операцией");

        verifyNoInteractions(outbox, store);
        verify(documents, never()).markDeletedByAttachmentId(any(), any(), any());
    }

    @Test
    void storageFailureInPhaseTwo_countsStorageUnavailableAndLeavesDatabaseUnmodified() {
        when(documents.findByAttachmentAttachmentId(Uploads.ATTACHMENT)).thenReturn(Optional.of(activeCertificate()));
        when(contracts.findByRequestIndividualRequestIdAndInsuredParticipantId(Uploads.REQUEST, Uploads.PARTICIPANT))
                .thenReturn(List.of());
        StorageUnavailableException unavailable = new StorageUnavailableException(new IOException("down"));
        doThrow(unavailable).when(store).remove(KEY);

        assertThatThrownBy(() -> service.delete(Uploads.ATTACHMENT)).isSameAs(unavailable);

        assertThat(meters.get("contracts.storage.unavailable").counter().count()).isEqualTo(1);
        verify(documents, never()).markDeletedByAttachmentId(any(), any(), any());
    }

    @Test
    void attachmentRecordWithoutStoredFileInDelete_isIllegalState() {
        PrintedForm broken = new PrintedForm() {
            @Override
            public UUID id() {
                return Uploads.ATTACHMENT;
            }

            @Override
            public PrintedFormType docType() {
                return PrintedFormType.PD_CONSENT;
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

        assertThatThrownBy(() -> service.delete(Uploads.ATTACHMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("у вложения " + Uploads.ATTACHMENT + " нет файла");
        verifyNoInteractions(store);
    }

    private static PrintedForm activeCertificate() {
        return PrintedForm.receivedAttachment(PrintedFormType.MEDICAL_CERTIFICATE,
                DocumentAttachments.certificate(Uploads.REQUEST, Uploads.PARTICIPANT, Uploads.ATTACHMENT),
                FILE, NOW.minusSeconds(300));
    }
}
