package ru.projectx.contracts.service.storage;

import java.util.UUID;
import org.springframework.stereotype.Service;
import ru.projectx.contracts.exception.StorageUnavailableException;
import ru.projectx.contracts.service.RequestIds;

@Service
public class AttachmentDeletionService implements AttachmentDeletions {

    private final AttachmentDeletionTransactions transactions;
    private final CertificateStore store;
    private final AttachmentMetrics metrics;

    public AttachmentDeletionService(AttachmentDeletionTransactions transactions, CertificateStore store,
            AttachmentMetrics metrics) {
        this.transactions = transactions;
        this.store = store;
        this.metrics = metrics;
    }

    @Override
    public void delete(UUID attachmentId) {
        String requestId = RequestIds.current();
        transactions.prepare(attachmentId)
                .ifPresent(candidate -> removeAndMark(attachmentId, candidate, requestId));
    }

    private void removeAndMark(UUID attachmentId, DeletionCandidate candidate, String requestId) {
        removeObject(candidate.fileKey());
        transactions.markDeleted(attachmentId, candidate, requestId);
    }

    private void removeObject(String fileKey) {
        try {
            store.remove(fileKey);
        } catch (StorageUnavailableException unavailable) {
            metrics.countStorageUnavailable();
            throw unavailable;
        }
    }
}
