package ru.projectx.contracts.service.storage;

import java.io.InputStream;
import ru.projectx.contracts.entity.document.StoredFile;

public interface CertificateStore {

    void store(StoredFile file, ContentSource content);

    void verifyPresent(String key);

    InputStream openStream(String key);

    void remove(String key);
}
