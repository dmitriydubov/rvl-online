package ru.projectx.contracts.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.context.request.WebRequest;
import ru.projectx.contracts.service.RequestIds;
import ru.projectx.contracts.service.storage.AttachmentContentReads;
import ru.projectx.contracts.service.storage.AttachmentStream;

@InternalApi
public class AttachmentContentController {

    private final AttachmentContentReads contents;

    public AttachmentContentController(AttachmentContentReads contents) {
        this.contents = contents;
    }

    @GetMapping("/attachments/{attachment_id}/content")
    public void content(@PathVariable("attachment_id") UUID attachmentId, WebRequest request,
            HttpServletResponse response) throws IOException {
        AttachmentStream content = contents.openContent(attachmentId);
        try (InputStream stream = content.inputStream()) {
            if (request.checkNotModified(content.fileHash())) {
                return;
            }
            writeStream(content, stream, response);
        }
    }

    private static void writeStream(AttachmentStream content, InputStream stream, HttpServletResponse response)
            throws IOException {
        response.setContentType(content.contentType());
        response.setContentLengthLong(content.fileSize());
        response.setHeader(HttpHeaders.ETAG, "\"" + content.fileHash() + "\"");
        try {
            stream.transferTo(response.getOutputStream());
        } catch (IOException failure) {
            clearUncommittedHeaders(response);
            throw failure;
        }
    }

    private static void clearUncommittedHeaders(HttpServletResponse response) {
        if (!response.isCommitted()) {
            response.reset();
            response.setHeader(RequestIds.HEADER, RequestIds.current());
        }
    }
}
