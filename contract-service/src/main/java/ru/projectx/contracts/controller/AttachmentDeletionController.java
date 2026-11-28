package ru.projectx.contracts.controller;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import ru.projectx.contracts.service.storage.AttachmentDeletions;

@InternalApi
public class AttachmentDeletionController {

    private final AttachmentDeletions deletions;

    public AttachmentDeletionController(AttachmentDeletions deletions) {
        this.deletions = deletions;
    }

    @DeleteMapping("/attachments/{attachment_id}")
    public ResponseEntity<Void> delete(@PathVariable("attachment_id") UUID attachmentId) {
        deletions.delete(attachmentId);
        return ResponseEntity.noContent().build();
    }
}
