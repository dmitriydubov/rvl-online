package ru.projectx.contracts.service.integration.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.projectx.contracts.entity.document.StoredFile;
import ru.projectx.contracts.exception.CertificateMissingException;
import ru.projectx.contracts.exception.StorageUnavailableException;
import ru.projectx.contracts.service.storage.CertificateStore;
import ru.projectx.contracts.service.storage.ContentSource;

public class MinioCertificateStore implements CertificateStore {

    private static final Logger log = LoggerFactory.getLogger(MinioCertificateStore.class);
    private static final long AUTOMATIC_PART_SIZE = -1;
    private static final String NO_SUCH_KEY = "NoSuchKey";

    private final MinioClient client;
    private final String bucket;

    public MinioCertificateStore(MinioClient client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void store(StoredFile file, ContentSource content) {
        try (InputStream stream = opened(content)) {
            put(file, stream);
        } catch (IOException closing) {
            throw new UncheckedIOException("файл справки не закрыт после записи", closing);
        }
    }

    @Override
    public void verifyPresent(String key) {
        StatObjectArgs object = StatObjectArgs.builder().bucket(bucket).object(key).build();
        try {
            client.statObject(object);
        } catch (ErrorResponseException refused) {
            throw refusal(refused, "проверке");
        } catch (MinioException | IOException | GeneralSecurityException | RuntimeException failure) {
            throw unavailable("хранилище справок не ответило о наличии объекта", failure);
        }
    }

    @Override
    public InputStream openStream(String key) {
        GetObjectArgs object = GetObjectArgs.builder().bucket(bucket).object(key).build();
        try {
            return client.getObject(object);
        } catch (ErrorResponseException refused) {
            throw refusal(refused, "чтении");
        } catch (MinioException | IOException | GeneralSecurityException | RuntimeException failure) {
            throw unavailable("хранилище справок не отдало объект", failure);
        }
    }

    @Override
    public void remove(String key) {
        RemoveObjectArgs object = RemoveObjectArgs.builder().bucket(bucket).object(key).build();
        try {
            client.removeObject(object);
        } catch (ErrorResponseException refused) {
            handleRemovalRefusal(refused);
        } catch (MinioException | IOException | GeneralSecurityException | RuntimeException failure) {
            throw unavailable("хранилище справок не удалило объект", failure);
        }
    }

    private RuntimeException refusal(ErrorResponseException refused, String action) {
        String code = refused.errorResponse().code();
        if (!NO_SUCH_KEY.equals(code)) {
            log.warn("хранилище справок отказало в {} объекта: код {}", action, code);
            return new StorageUnavailableException(refused);
        }
        if (bucketPresent()) {
            return new CertificateMissingException(refused);
        }
        log.warn("в хранилище справок нет бакета справок");
        return new StorageUnavailableException(refused);
    }

    private void handleRemovalRefusal(ErrorResponseException refused) {
        RuntimeException translated = refusal(refused, "удалении");
        if (!(translated instanceof CertificateMissingException)) {
            throw translated;
        }
    }

    private boolean bucketPresent() {
        try {
            return client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        } catch (MinioException | IOException | GeneralSecurityException | RuntimeException failure) {
            throw unavailable("хранилище справок не ответило о наличии объекта", failure);
        }
    }

    private static StorageUnavailableException unavailable(String message, Exception failure) {
        log.warn("{}: {}", message, failure.getClass().getName());
        return new StorageUnavailableException(failure);
    }

    private void put(StoredFile file, InputStream stream) {
        PutObjectArgs object = PutObjectArgs.builder().bucket(bucket).object(file.key())
                .stream(stream, file.size(), AUTOMATIC_PART_SIZE).contentType(file.contentType()).build();
        try {
            client.putObject(object);
        } catch (ErrorResponseException refused) {
            log.warn("хранилище справок отказало в записи объекта: код {}", refused.errorResponse().code());
            throw new StorageUnavailableException(refused);
        } catch (MinioException | IOException | GeneralSecurityException | RuntimeException failure) {
            throw unavailable("хранилище справок не записало объект", failure);
        }
    }

    private static InputStream opened(ContentSource content) {
        try {
            return content.open();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("файл справки не прочитан", unreadable);
        }
    }
}
