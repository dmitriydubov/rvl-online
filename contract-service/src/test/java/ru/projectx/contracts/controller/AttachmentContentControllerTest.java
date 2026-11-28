package ru.projectx.contracts.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.WebRequest;
import ru.projectx.contracts.exception.AttachmentNotFoundException;
import ru.projectx.contracts.exception.StorageUnavailableException;
import ru.projectx.contracts.service.RequestIds;
import ru.projectx.contracts.service.storage.AttachmentContentReads;
import ru.projectx.contracts.service.storage.AttachmentMetrics;
import ru.projectx.contracts.service.storage.AttachmentStream;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class AttachmentContentControllerTest {

    private static final UUID ATTACHMENT = UUID.fromString("3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e21");
    private static final String PATH = "/internal/v1/attachments/{attachment_id}/content";
    private static final String TRACE = "a41f8c02-7d3b-4e19-9a55-2c7e0b6f1d84";
    private static final String HASH = "26282de112fa052bddc7385c8d9a8af182a7ce3fa6ed6f39b45eb2a8c42b7c82";
    private static final String QUOTED_ETAG = "\"" + HASH + "\"";
    private static final byte[] BINARY = new byte[] {
        (byte) 0xff, (byte) 0xd8, (byte) 0x80, (byte) 0xfe, (byte) 0xff, 0x00, (byte) 0xd9
    };

    private final AttachmentContentReads contents = mock(AttachmentContentReads.class);
    private final AttachmentMetrics metrics = new AttachmentMetrics(new SimpleMeterRegistry());
    private final AttachmentContentController controller = new AttachmentContentController(contents);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new RequestFormatAdvice(metrics), new DomainErrorAdvice(), new UnexpectedErrorAdvice())
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()))
            .addFilters(new RequestIdFilter())
            .build();

    @Test
    void activeAttachment_streamsRawBytesWithHeadersIgnoresRangeClosesStreamAndLogsCompletionAfterStreaming()
            throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        AtomicInteger closedStep = new AtomicInteger();
        AtomicInteger loggedStep = new AtomicInteger();
        when(contents.openContent(ATTACHMENT))
                .thenReturn(new AttachmentStream("image/jpeg", BINARY.length, HASH, stepRecordingStream(sequence, closedStep)))
                .thenReturn(new AttachmentStream("image/jpeg", BINARY.length, HASH, new ByteArrayInputStream(BINARY)));

        MvcResult result = performRecordingFilterLog(sequence, loggedStep);
        MvcResult ranged = mvc.perform(get(PATH, ATTACHMENT)
                        .header(RequestIds.HEADER, TRACE)
                        .header(HttpHeaders.RANGE, "bytes=0-2"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCEPT_RANGES))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(BINARY);
        assertThat(ranged.getResponse().getContentAsByteArray()).isEqualTo(BINARY);
        assertThat(closedStep.get()).isPositive();
        assertThat(loggedStep.get()).isGreaterThan(closedStep.get());
    }

    @Test
    void matchingIfNoneMatch_returns304NotModifiedWithQuotedEtagEmptyBodyAndClosesOpenedStream() throws Exception {
        AtomicBoolean read = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        InputStream stream = trackingStream(read, closed);
        when(contents.openContent(ATTACHMENT)).thenReturn(new AttachmentStream("application/pdf", BINARY.length, HASH, stream));

        MvcResult result = mvc.perform(get(PATH, ATTACHMENT)
                        .header(RequestIds.HEADER, TRACE)
                        .header(HttpHeaders.IF_NONE_MATCH, QUOTED_ETAG))
                .andExpect(status().isNotModified())
                .andExpect(header().string(RequestIds.HEADER, TRACE))
                .andExpect(header().string(HttpHeaders.ETAG, QUOTED_ETAG))
                .andExpect(header().doesNotExist(HttpHeaders.ACCEPT_RANGES))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(read).isFalse();
        assertThat(closed).isTrue();
    }

    @Test
    void uncommittedStreamFailure_resetsContentHeadersClosesStreamAndReturnsJsonError() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream failing = failingReadStream(closed);
        when(contents.openContent(ATTACHMENT)).thenReturn(new AttachmentStream("image/jpeg", 1024L, HASH, failing));

        mvc.perform(get(PATH, ATTACHMENT).header(RequestIds.HEADER, TRACE))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(RequestIds.HEADER, TRACE))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.request_id").value(TRACE));

        assertThat(closed).isTrue();
    }

    @Test
    void committedStreamFailure_closesStreamWithoutResettingCommittedResponse() {
        AtomicBoolean closed = new AtomicBoolean();
        when(contents.openContent(ATTACHMENT))
                .thenReturn(new AttachmentStream("image/jpeg", 1024L, HASH, failingReadStream(closed)));
        WebRequest webRequest = mock(WebRequest.class);
        when(webRequest.checkNotModified(HASH)).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);

        assertThatThrownBy(() -> controller.content(ATTACHMENT, webRequest, response))
                .isInstanceOf(IOException.class)
                .hasMessage("mid-stream disconnect");
        assertThat(closed).isTrue();
        assertThat(response.getHeader(HttpHeaders.ETAG)).isEqualTo(QUOTED_ETAG);
    }

    @Test
    void streamFailingToCloseOnNotModified_propagatesCloseIoException() {
        InputStream broken = new FilterInputStream(new ByteArrayInputStream(BINARY)) {
            @Override
            public void close() throws IOException {
                throw new IOException("broken close");
            }
        };
        when(contents.openContent(ATTACHMENT)).thenReturn(new AttachmentStream("image/png", BINARY.length, HASH, broken));
        WebRequest webRequest = mock(WebRequest.class);
        when(webRequest.checkNotModified(HASH)).thenReturn(true);

        assertThatThrownBy(() -> controller.content(ATTACHMENT, webRequest, new MockHttpServletResponse()))
                .isInstanceOf(IOException.class)
                .hasMessage("broken close");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-guid", "3c2f8e10", "3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e210"})
    void malformedAttachmentId_isValidationErrorNamingAttachmentIdWithoutCallingService(String rawId) throws Exception {
        mvc.perform(get(PATH, rawId).header(RequestIds.HEADER, TRACE))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(RequestIds.HEADER, TRACE))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json"))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.length()").value(1))
                .andExpect(jsonPath("$.error.details[0].field").value("attachment_id"))
                .andExpect(jsonPath("$.error.details[0].reason").value("неверный формат"))
                .andExpect(jsonPath("$.error.request_id").value(TRACE));

        verifyNoInteractions(contents);
    }

    @Test
    void notFoundAndStorageUnavailable_areMappedToNeutralErrorsWithoutDetails() throws Exception {
        when(contents.openContent(ATTACHMENT))
                .thenThrow(new AttachmentNotFoundException(ATTACHMENT))
                .thenThrow(new StorageUnavailableException(new IOException("down")));

        mvc.perform(get(PATH, ATTACHMENT).header(RequestIds.HEADER, TRACE))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json"))
                .andExpect(jsonPath("$.error.code").value("ATTACHMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("вложения с таким идентификатором нет"))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andExpect(jsonPath("$.error.request_id").value(TRACE));

        mvc.perform(get(PATH, ATTACHMENT).header(RequestIds.HEADER, TRACE))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json"))
                .andExpect(jsonPath("$.error.code").value("STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message").value("хранилище файлов недоступно"))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andExpect(jsonPath("$.error.request_id").value(TRACE));
    }

    private MvcResult performRecordingFilterLog(AtomicInteger sequence, AtomicInteger loggedStep) throws Exception {
        Logger filterLogger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent eventObject) {
                loggedStep.set(sequence.incrementAndGet());
                super.append(eventObject);
            }
        };
        appender.start();
        filterLogger.addAppender(appender);
        try {
            return mvc.perform(get(PATH, ATTACHMENT).header(RequestIds.HEADER, TRACE))
                    .andExpect(status().isOk())
                    .andExpect(header().string(RequestIds.HEADER, TRACE))
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                    .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, BINARY.length))
                    .andExpect(header().string(HttpHeaders.ETAG, QUOTED_ETAG))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCEPT_RANGES))
                    .andReturn();
        } finally {
            filterLogger.detachAppender(appender);
            appender.stop();
        }
    }

    private static InputStream stepRecordingStream(AtomicInteger sequence, AtomicInteger closedStep) {
        return new FilterInputStream(new ByteArrayInputStream(BINARY)) {
            @Override
            public void close() {
                closedStep.set(sequence.incrementAndGet());
            }
        };
    }

    private static InputStream trackingStream(AtomicBoolean read, AtomicBoolean closed) {
        return new FilterInputStream(new ByteArrayInputStream(BINARY)) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                read.set(true);
                return super.read(b, off, len);
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }

    private static InputStream failingReadStream(AtomicBoolean closed) {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("mid-stream disconnect");
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }
}
