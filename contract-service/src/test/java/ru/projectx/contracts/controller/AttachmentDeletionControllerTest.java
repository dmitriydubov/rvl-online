package ru.projectx.contracts.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.projectx.contracts.exception.AttachmentBusyException;
import ru.projectx.contracts.exception.AttachmentLockedException;
import ru.projectx.contracts.exception.StorageUnavailableException;
import ru.projectx.contracts.service.RequestIds;
import ru.projectx.contracts.service.storage.AttachmentDeletions;
import ru.projectx.contracts.service.storage.AttachmentMetrics;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class AttachmentDeletionControllerTest {

    private static final UUID ATTACHMENT = UUID.fromString("3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e21");
    private static final String PATH = "/internal/v1/attachments/{attachment_id}";
    private static final String TRACE = "a41f8c02-7d3b-4e19-9a55-2c7e0b6f1d84";

    private final AttachmentDeletions deletions = mock(AttachmentDeletions.class);
    private final AttachmentMetrics metrics = new AttachmentMetrics(new SimpleMeterRegistry());
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AttachmentDeletionController(deletions))
            .setControllerAdvice(new RequestFormatAdvice(metrics), new DomainErrorAdvice(), new UnexpectedErrorAdvice())
            .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()))
            .addFilters(new RequestIdFilter())
            .build();

    @Test
    void delete_invokesServiceAndReturns204NoContentWithEmptyBodyAndRequestId() throws Exception {
        MvcResult result = mvc.perform(delete(PATH, ATTACHMENT).header(RequestIds.HEADER, TRACE))
                .andExpect(status().isNoContent())
                .andExpect(header().string(RequestIds.HEADER, TRACE))
                .andReturn();

        verify(deletions).delete(ATTACHMENT);
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-guid", "3c2f8e10", "3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e210"})
    void malformedAttachmentId_isValidationErrorNamingAttachmentIdWithoutCallingService(String rawId) throws Exception {
        mvc.perform(delete(PATH, rawId).header(RequestIds.HEADER, TRACE))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(RequestIds.HEADER, TRACE))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details.length()").value(1))
                .andExpect(jsonPath("$.error.details[0].field").value("attachment_id"))
                .andExpect(jsonPath("$.error.details[0].reason").value("неверный формат"))
                .andExpect(jsonPath("$.error.request_id").value(TRACE));

        verifyNoInteractions(deletions);
    }

    @Test
    void lockedBusyAndStorageUnavailable_areMappedToExpectedErrorCodesWithoutDetails() throws Exception {
        doThrow(new AttachmentLockedException(ATTACHMENT))
                .doThrow(new AttachmentBusyException(ATTACHMENT, new CannotAcquireLockException("55P03")))
                .doThrow(new StorageUnavailableException(new IOException("down")))
                .when(deletions).delete(ATTACHMENT);

        mvc.perform(delete(PATH, ATTACHMENT).header(RequestIds.HEADER, TRACE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ATTACHMENT_LOCKED"))
                .andExpect(jsonPath("$.error.message")
                        .value("справка уже передана в 1С:УСК вместе с договором КМ и не может быть удалена"))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andExpect(jsonPath("$.error.request_id").value(TRACE));

        mvc.perform(delete(PATH, ATTACHMENT).header(RequestIds.HEADER, TRACE))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message")
                        .value("договор КМ занят другой операцией, повторите запрос позже"))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andExpect(jsonPath("$.error.request_id").value(TRACE));

        mvc.perform(delete(PATH, ATTACHMENT).header(RequestIds.HEADER, TRACE))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message").value("хранилище файлов недоступно"))
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andExpect(jsonPath("$.error.request_id").value(TRACE));
    }
}
