package ru.projectx.contracts.service.storage;

import java.util.UUID;

public interface AttachmentContentReads {

    AttachmentStream openContent(UUID attachmentId);
}
