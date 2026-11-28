package ru.projectx.contracts.controller;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import ru.projectx.contracts.dto.ErrorResponse;
import ru.projectx.contracts.service.RequestIds;

final class ApiErrors {

    static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    static final String FILE_FIELD = "file";

    private ApiErrors() {
    }

    static ResponseEntity<ErrorResponse> of(HttpStatus status, String code, String message) {
        return of(status, code, message, List.of());
    }

    static ResponseEntity<ErrorResponse> invalidField(String field, String reason) {
        return of(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, "запрос не соответствует формату",
                List.of(new ErrorResponse.FieldViolation(field, reason)));
    }

    static ResponseEntity<ErrorResponse> invalidFields(List<ErrorResponse.FieldViolation> violations) {
        return of(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, "запрос не соответствует формату", violations);
    }

    static ResponseEntity<ErrorResponse> conflictOnField(String code, String message, String field, String reason) {
        return of(HttpStatus.CONFLICT, code, message, List.of(new ErrorResponse.FieldViolation(field, reason)));
    }

    private static ResponseEntity<ErrorResponse> of(HttpStatus status, String code, String message,
            List<ErrorResponse.FieldViolation> details) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(code, message, details, RequestIds.current()));
    }
}
