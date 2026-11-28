package ru.projectx.contracts.exception;

import java.util.UUID;

public class AttachmentNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AttachmentNotFoundException(UUID attachmentId) {
        super("вложения " + attachmentId + " нет");
    }

    public AttachmentNotFoundException(UUID attachmentId, Throwable cause) {
        super("вложения " + attachmentId + " нет", cause);
    }
}
