package ru.projectx.contracts.exception;

import java.util.UUID;

public class AttachmentLockedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AttachmentLockedException(UUID attachmentId) {
        super("вложение " + attachmentId + " закреплено за переданным в 1С:УСК договором КМ");
    }
}
