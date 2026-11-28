package ru.projectx.contracts.service.storage;

import java.io.InputStream;

public record AttachmentStream(String contentType, long fileSize, String fileHash, InputStream inputStream) {
}
