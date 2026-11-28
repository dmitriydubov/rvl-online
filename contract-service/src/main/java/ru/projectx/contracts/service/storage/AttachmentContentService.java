package ru.projectx.contracts.service.storage;

import java.io.InputStream;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.projectx.contracts.entity.document.StoredFile;
import ru.projectx.contracts.exception.AttachmentNotFoundException;
import ru.projectx.contracts.exception.CertificateMissingException;
import ru.projectx.contracts.exception.StorageUnavailableException;

@Service
public class AttachmentContentService implements AttachmentContentReads {

    private static final Logger log = LoggerFactory.getLogger(AttachmentContentService.class);

    private final AttachmentRegistry registry;
    private final CertificateStore store;
    private final AttachmentMetrics metrics;

    public AttachmentContentService(AttachmentRegistry registry, CertificateStore store, AttachmentMetrics metrics) {
        this.registry = registry;
        this.store = store;
        this.metrics = metrics;
    }

    @Override
    public AttachmentStream openContent(UUID attachmentId) {
        StoredFile file = registry.activeFile(attachmentId);
        InputStream stream = openStream(attachmentId, file.key());
        return new AttachmentStream(file.contentType(), file.size(), file.hash(), stream);
    }

    private InputStream openStream(UUID attachmentId, String fileKey) {
        try {
            return store.openStream(fileKey);
        } catch (CertificateMissingException missing) {
            log.warn("вложение {}: объекта вложения нет в хранилище", attachmentId);
            throw new AttachmentNotFoundException(attachmentId, missing);
        } catch (StorageUnavailableException unavailable) {
            metrics.countStorageUnavailable();
            throw unavailable;
        }
    }
}
