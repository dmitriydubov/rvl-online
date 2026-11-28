package ru.projectx.contracts.entity.document;

import java.util.UUID;

public final class DocumentAttachments {

    private DocumentAttachments() {
    }

    public static DocumentAttachment certificate(UUID individualRequestId, UUID participantId, UUID attachmentId) {
        return new DocumentAttachment(individualRequestId, participantId, null, null, attachmentId, null, null);
    }

    public static DocumentAttachment consent(UUID policyId, UUID familyMemberId, UUID attachmentId) {
        return new DocumentAttachment(null, null, policyId, familyMemberId, attachmentId, null, null);
    }

    public static boolean isActive(DocumentAttachment attachment) {
        return attachment.deletedAt() == null;
    }

    public static boolean hasActiveAttachmentId(DocumentAttachment attachment) {
        return attachment.attachmentId() != null && isActive(attachment);
    }
}
