package ru.projectx.contracts.exception;

import java.util.UUID;

public class AttachmentBusyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AttachmentBusyException(UUID attachmentId, Throwable cause) {
        super("договор КМ по вложению " + attachmentId + " занят другой операцией", cause);
    }
}
