package ru.projectx.contracts.service.integration.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.ServerException;
import io.minio.messages.ErrorResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import ru.projectx.contracts.exception.CertificateMissingException;
import ru.projectx.contracts.exception.StorageUnavailableException;

class MinioCertificateStoreReadDeleteTest {

    private static final String BUCKET = "rvl-contracts";
    private static final String KEY = "1111111111_222222222/certificates/0b6c/3c2f.jpg";
    private static final byte[] CONTENT = "СТЕНД: вымышленная справка".getBytes(StandardCharsets.UTF_8);

    private final MinioClient client = mock(MinioClient.class);
    private final MinioCertificateStore store = new MinioCertificateStore(client, BUCKET);

    @Test
    void openStream_requestsObjectUnderKeyInBucketWithoutConsumingBytes() throws Exception {
        GetObjectResponse response = new GetObjectResponse(Headers.of(), BUCKET, "us-east-1", KEY,
                new ByteArrayInputStream(CONTENT));
        when(client.getObject(any())).thenReturn(response);

        InputStream opened = store.openStream(KEY);

        assertThat(opened).isSameAs(response);
        assertThat(opened.readAllBytes()).isEqualTo(CONTENT);
        ArgumentCaptor<GetObjectArgs> args = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(client).getObject(args.capture());
        assertThat(args.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(args.getValue().object()).isEqualTo(KEY);
    }

    @Test
    void openStream_ofMissingObjectInExistingBucket_throwsCertificateMissing() throws Exception {
        ErrorResponseException missing = errorResponse("NoSuchKey", "Object does not exist");
        when(client.getObject(any())).thenThrow(missing);
        when(client.bucketExists(any())).thenReturn(true);

        assertThatThrownBy(() -> store.openStream(KEY)).isInstanceOf(CertificateMissingException.class)
                .hasCause(missing);
        ArgumentCaptor<BucketExistsArgs> bucket = ArgumentCaptor.forClass(BucketExistsArgs.class);
        verify(client).bucketExists(bucket.capture());
        assertThat(bucket.getValue().bucket()).isEqualTo(BUCKET);
    }

    @Test
    void openStream_answeredNoSuchKeyInMissingBucket_throwsStorageUnavailable() throws Exception {
        ErrorResponseException missing = errorResponse("NoSuchKey", "Object does not exist");
        when(client.getObject(any())).thenThrow(missing);
        when(client.bucketExists(any())).thenReturn(false);

        assertThatThrownBy(() -> store.openStream(KEY)).isInstanceOf(StorageUnavailableException.class)
                .hasCause(missing);
    }

    @Test
    void openStream_whoseBucketCannotBeChecked_throwsStorageUnavailable() throws Exception {
        ErrorResponseException missing = errorResponse("NoSuchKey", "Object does not exist");
        IOException refused = new IOException("Connection refused");
        when(client.getObject(any())).thenThrow(missing);
        when(client.bucketExists(any())).thenThrow(refused);

        assertThatThrownBy(() -> store.openStream(KEY)).isInstanceOf(StorageUnavailableException.class)
                .hasCause(refused);
    }

    @Test
    void openStream_refusedWithOtherCode_throwsStorageUnavailableWithoutCheckingBucket() throws Exception {
        ErrorResponseException refused = errorResponse("NoSuchBucket", "The specified bucket does not exist");
        when(client.getObject(any())).thenThrow(refused);

        assertThatThrownBy(() -> store.openStream(KEY)).isInstanceOf(StorageUnavailableException.class)
                .hasCause(refused);
        verify(client, never()).bucketExists(any());
    }

    @Test
    void openStream_failingOnNetworkSigningOrClient_throwsStorageUnavailable() throws Exception {
        IOException refused = new IOException("Connection refused");
        when(client.getObject(any())).thenThrow(refused)
                .thenThrow(new ServerException("503", 503, "trace"))
                .thenThrow(new InvalidKeyException("key"))
                .thenThrow(new RuntimeException(new InterruptedException()));

        assertThatThrownBy(() -> store.openStream(KEY)).isInstanceOf(StorageUnavailableException.class)
                .hasCause(refused);
        assertThatThrownBy(() -> store.openStream(KEY)).isInstanceOf(StorageUnavailableException.class)
                .hasCauseInstanceOf(ServerException.class);
        assertThatThrownBy(() -> store.openStream(KEY)).isInstanceOf(StorageUnavailableException.class)
                .hasCauseInstanceOf(InvalidKeyException.class);
        assertThatThrownBy(() -> store.openStream(KEY)).isInstanceOf(StorageUnavailableException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void remove_deletesObjectUnderKeyInBucket() throws Exception {
        store.remove(KEY);

        ArgumentCaptor<RemoveObjectArgs> args = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(client).removeObject(args.capture());
        assertThat(args.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(args.getValue().object()).isEqualTo(KEY);
    }

    @Test
    void remove_answeredNoSuchKeyInExistingBucket_completesWithoutError() throws Exception {
        doThrow(errorResponse("NoSuchKey", "Object does not exist")).when(client).removeObject(any());
        when(client.bucketExists(any())).thenReturn(true);

        assertThatCode(() -> store.remove(KEY)).doesNotThrowAnyException();
    }

    @Test
    void remove_answeredNoSuchKeyInMissingBucket_throwsStorageUnavailable() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(MinioCertificateStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ErrorResponseException missing = errorResponse("NoSuchKey", "Object does not exist");
            doThrow(missing).when(client).removeObject(any());
            when(client.bucketExists(any())).thenReturn(false);

            assertThatThrownBy(() -> store.remove(KEY)).isInstanceOf(StorageUnavailableException.class)
                    .hasCause(missing);
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("в хранилище справок нет бакета справок");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void remove_refusedWithMissingBucketOrAccessDenied_throwsStorageUnavailableWithoutCheckingBucket()
            throws Exception {
        ErrorResponseException noBucket = errorResponse("NoSuchBucket", "The specified bucket does not exist");
        doThrow(noBucket).when(client).removeObject(any());

        assertThatThrownBy(() -> store.remove(KEY)).isInstanceOf(StorageUnavailableException.class)
                .hasCause(noBucket);
        verify(client, never()).bucketExists(any());
    }

    @Test
    void remove_failingOnNetworkSigningOrClient_throwsStorageUnavailable() throws Exception {
        IOException refused = new IOException("Connection refused");
        doThrow(refused)
                .doThrow(new ServerException("503", 503, "trace"))
                .doThrow(new InvalidKeyException("key"))
                .doThrow(new RuntimeException(new InterruptedException()))
                .when(client).removeObject(any());

        assertThatThrownBy(() -> store.remove(KEY)).isInstanceOf(StorageUnavailableException.class)
                .hasCause(refused);
        assertThatThrownBy(() -> store.remove(KEY)).isInstanceOf(StorageUnavailableException.class)
                .hasCauseInstanceOf(ServerException.class);
        assertThatThrownBy(() -> store.remove(KEY)).isInstanceOf(StorageUnavailableException.class)
                .hasCauseInstanceOf(InvalidKeyException.class);
        assertThatThrownBy(() -> store.remove(KEY)).isInstanceOf(StorageUnavailableException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    private static ErrorResponseException errorResponse(String code, String message) {
        return new ErrorResponseException(new ErrorResponse(code, message, BUCKET, KEY, null, null, null), null, null);
    }
}
