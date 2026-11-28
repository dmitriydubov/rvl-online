package ru.projectx.contracts.service.integration.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.testcontainers.containers.MinIOContainer;
import ru.projectx.contracts.config.storage.StorageProperties;
import ru.projectx.contracts.entity.document.StoredFile;
import ru.projectx.contracts.exception.CertificateMissingException;
import ru.projectx.contracts.exception.StorageUnavailableException;
import ru.projectx.contracts.support.TestStorage;

@Tag("no-mutate")
class MinioCertificateStoreIT {

    private static final String BUCKET = "contracts-it";
    private static final byte[] CONTENT = "СТЕНД: вымышленная справка 070/у".getBytes(StandardCharsets.UTF_8);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final MinIOContainer STORAGE = TestStorage.container();
    private static MinioClient client;

    @BeforeAll
    static void startStorage() throws Exception {
        STORAGE.start();
        client = new StorageClientConfig().storageClient(settings(BUCKET), new DefaultSslBundleRegistry());
        client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void stopStorage() throws Exception {
        client.close();
        STORAGE.stop();
    }

    @Test
    void storedFile_isAnObjectWithTheSameBytesAndContentType() throws Exception {
        StoredFile file = new StoredFile("certificates/0b6c1d2e/3c2f8e10.jpg", "0".repeat(64), CONTENT.length,
                "image/jpeg");

        new MinioCertificateStore(client, BUCKET).store(file, () -> new ByteArrayInputStream(CONTENT));

        try (GetObjectResponse object = client.getObject(GetObjectArgs.builder().bucket(BUCKET).object(file.key())
                .build())) {
            assertThat(object.readAllBytes()).isEqualTo(CONTENT);
        }
        assertThat(client.statObject(StatObjectArgs.builder().bucket(BUCKET).object(file.key()).build())
                .contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void storedObject_isPresentAndStreamedAndThenRemovedIdempotently() throws Exception {
        StoredFile file = new StoredFile("certificates/7a1d/0c6a9f31.png", "0".repeat(64), CONTENT.length,
                "image/png");
        MinioCertificateStore store = new MinioCertificateStore(client, BUCKET);
        store.store(file, () -> new ByteArrayInputStream(CONTENT));

        assertThatCode(() -> store.verifyPresent(file.key())).doesNotThrowAnyException();
        try (InputStream stream = store.openStream(file.key())) {
            assertThat(stream.readAllBytes()).isEqualTo(CONTENT);
        }

        store.remove(file.key());
        assertThatCode(() -> store.remove(file.key())).doesNotThrowAnyException();
        assertThatThrownBy(() -> store.openStream(file.key())).isInstanceOf(CertificateMissingException.class);
    }

    @Test
    void objectThatWasNeverStored_isACertificateMissingForVerifyAndOpenStream() {
        MinioCertificateStore store = new MinioCertificateStore(client, BUCKET);

        assertThatThrownBy(() -> store.verifyPresent("certificates/none/1.jpg"))
                .isInstanceOf(CertificateMissingException.class);
        assertThatThrownBy(() -> store.openStream("certificates/none/1.jpg"))
                .isInstanceOf(CertificateMissingException.class);
    }

    @Test
    void objectInAMissingBucket_isStorageUnavailableForVerifyOpenAndRemove() throws Exception {
        MinioCertificateStore missingBucket = new MinioCertificateStore(client, "contracts-missing");

        assertThatThrownBy(() -> missingBucket.verifyPresent("certificates/none/1.jpg"))
                .isInstanceOf(StorageUnavailableException.class);
        assertThatThrownBy(() -> missingBucket.openStream("certificates/none/1.jpg"))
                .isInstanceOf(StorageUnavailableException.class);
        assertThatThrownBy(() -> missingBucket.remove("certificates/none/1.jpg"))
                .isInstanceOf(StorageUnavailableException.class);
        assertThat(client.bucketExists(BucketExistsArgs.builder().bucket("contracts-missing").build())).isFalse();
    }

    @Test
    void missingBucket_isStorageUnavailableAndTheBucketIsNotCreated() throws Exception {
        StoredFile file = new StoredFile("certificates/1/2.png", "0".repeat(64), CONTENT.length, "image/png");

        assertThatThrownBy(() -> new MinioCertificateStore(client, "contracts-missing")
                .store(file, () -> new ByteArrayInputStream(CONTENT)))
                .isInstanceOf(StorageUnavailableException.class);

        assertThat(client.bucketExists(BucketExistsArgs.builder().bucket("contracts-missing").build())).isFalse();
    }

    @Test
    void refusedConnection_isStorageUnavailableForStoreOpenAndRemove() {
        StorageProperties nowhere = new StorageProperties("http://127.0.0.1:1", "us-east-1", BUCKET, "certificates",
                "1111111111", "222222222", STORAGE.getUserName(), STORAGE.getPassword(), null,
                new StorageProperties.Timeouts(TIMEOUT, TIMEOUT, TIMEOUT, TIMEOUT));
        MinioClient refused = new StorageClientConfig().storageClient(nowhere, new DefaultSslBundleRegistry());
        MinioCertificateStore store = new MinioCertificateStore(refused, BUCKET);
        StoredFile file = new StoredFile("certificates/1/3.png", "0".repeat(64), CONTENT.length, "image/png");

        assertThatThrownBy(() -> store.store(file, () -> new ByteArrayInputStream(CONTENT)))
                .isInstanceOf(StorageUnavailableException.class);
        assertThatThrownBy(() -> store.openStream(file.key()))
                .isInstanceOf(StorageUnavailableException.class);
        assertThatThrownBy(() -> store.remove(file.key()))
                .isInstanceOf(StorageUnavailableException.class);
    }

    private static StorageProperties settings(String bucket) {
        return new StorageProperties(STORAGE.getS3URL(), "us-east-1", bucket, "certificates", "1111111111", "222222222",
                STORAGE.getUserName(), STORAGE.getPassword(), null,
                new StorageProperties.Timeouts(TIMEOUT, TIMEOUT, TIMEOUT, TIMEOUT));
    }
}
