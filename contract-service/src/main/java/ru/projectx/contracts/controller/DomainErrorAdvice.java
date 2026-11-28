package ru.projectx.contracts.controller;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.projectx.contracts.dto.ErrorResponse;
import ru.projectx.contracts.exception.AttachmentBusyException;
import ru.projectx.contracts.exception.AttachmentConflictException;
import ru.projectx.contracts.exception.AttachmentDefect;
import ru.projectx.contracts.exception.AttachmentLockedException;
import ru.projectx.contracts.exception.AttachmentNotFoundException;
import ru.projectx.contracts.exception.AttachmentRejectedException;
import ru.projectx.contracts.exception.RequestCancelledException;
import ru.projectx.contracts.exception.RequestNotFoundException;
import ru.projectx.contracts.exception.StorageUnavailableException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DomainErrorAdvice {

    private static final Map<AttachmentDefect, String> DEFECT_REASONS = new EnumMap<>(Map.of(
            AttachmentDefect.CONTENT_TYPE, "тип файла не допускается",
            AttachmentDefect.SIZE, "размер файла вне допустимого"));

    private static final Map<Class<? extends RuntimeException>, Supplier<ResponseEntity<ErrorResponse>>> ANSWERS = Map.of(
            RequestNotFoundException.class,
            () -> ApiErrors.of(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "задачи на выпуск по заявке нет"),
            AttachmentNotFoundException.class,
            () -> ApiErrors.of(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "вложения с таким идентификатором нет"),
            RequestCancelledException.class,
            () -> ApiErrors.of(HttpStatus.CONFLICT, "INVALID_STATE", "работа по заявке прекращена"),
            AttachmentConflictException.class,
            () -> ApiErrors.of(HttpStatus.CONFLICT, "ATTACHMENT_CONFLICT",
                    "вложение с таким идентификатором уже зарегистрировано с другими реквизитами или удалено"),
            AttachmentLockedException.class,
            () -> ApiErrors.of(HttpStatus.CONFLICT, "ATTACHMENT_LOCKED",
                    "справка уже передана в 1С:УСК вместе с договором КМ и не может быть удалена"),
            AttachmentBusyException.class,
            () -> ApiErrors.of(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "договор КМ занят другой операцией, повторите запрос позже"),
            StorageUnavailableException.class,
            () -> ApiErrors.of(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "хранилище файлов недоступно"));

    @ExceptionHandler({
        RequestNotFoundException.class,
        AttachmentNotFoundException.class,
        RequestCancelledException.class,
        AttachmentConflictException.class,
        AttachmentLockedException.class,
        AttachmentBusyException.class,
        StorageUnavailableException.class
    })
    public ResponseEntity<ErrorResponse> domainError(RuntimeException failure) {
        return ANSWERS.get(failure.getClass()).get();
    }

    @ExceptionHandler
    public ResponseEntity<ErrorResponse> attachmentRejected(AttachmentRejectedException rejected) {
        return ApiErrors.invalidField(ApiErrors.FILE_FIELD, DEFECT_REASONS.get(rejected.defect()));
    }
}
