package ru.projectx.datareference.controller.error;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.projectx.datareference.controller.request.CurrentRequest;
import ru.projectx.datareference.dto.ErrorResponse;
import ru.projectx.datareference.exception.AmbiguousPolicyNumberException;
import ru.projectx.datareference.exception.AmbiguousPriceException;
import ru.projectx.datareference.exception.AmbiguousReferenceException;
import ru.projectx.datareference.exception.IncompleteReferenceException;
import ru.projectx.datareference.exception.PriceNotCoveredException;
import ru.projectx.datareference.exception.ReferenceNotFoundException;

@RestControllerAdvice
@Order(ErrorAdviceOrder.REFERENCE)
public class ReferenceErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(ReferenceErrorAdvice.class);
    private static final String AMBIGUOUS_DATA = "неоднозначные данные 1С";
    private static final String SEVERAL_POLICIES_WITH_NUMBER = "по номеру полиса найдено несколько полисов";

    @ExceptionHandler(ReferenceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse referenceNotFound(ReferenceNotFoundException notFound) {
        return ErrorResponse.of(notFound.code(), notFound.getMessage(), CurrentRequest.id());
    }

    @ExceptionHandler(AmbiguousReferenceException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse ambiguousReference(AmbiguousReferenceException ambiguous) {
        return conflict(AMBIGUOUS_DATA, ambiguous.code(), ambiguous.getMessage(), AmbiguityDetails.of(ambiguous));
    }

    @ExceptionHandler(AmbiguousPolicyNumberException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse ambiguousPolicyNumber(AmbiguousPolicyNumberException ambiguous) {
        return conflict(SEVERAL_POLICIES_WITH_NUMBER, ambiguous.code(), ambiguous.getMessage(),
                AmbiguityDetails.of(ambiguous));
    }

    @ExceptionHandler(IncompleteReferenceException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    public ErrorResponse incompleteReference(IncompleteReferenceException incomplete) {
        log.warn("данных 1С не хватает для ответа: {}, lpu_id={}, request_id={}",
                incomplete.code(), incomplete.lpuId(), CurrentRequest.id());
        return ErrorResponse.of(incomplete.code(), incomplete.getMessage(), CurrentRequest.id());
    }

    @ExceptionHandler(AmbiguousPriceException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse ambiguousPrice(AmbiguousPriceException ambiguous) {
        List<ErrorResponse.PriceRowCandidate> rows = ambiguous.rows().stream()
                .map(row -> new ErrorResponse.PriceRowCandidate(row.validFrom(), row.validTo()))
                .toList();
        log.warn("неоднозначный прайс услуги: {}, service_id={}, {}, request_id={}",
                ambiguous.code(), ambiguous.serviceId(), rows, CurrentRequest.id());
        return ErrorResponse.of(ambiguous.code(), ambiguous.getMessage(), rows, CurrentRequest.id());
    }

    @ExceptionHandler(PriceNotCoveredException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    public ErrorResponse priceNotCovered(PriceNotCoveredException notCovered) {
        List<ErrorResponse.UncoveredInterval> intervals = notCovered.intervals().stream()
                .map(interval -> new ErrorResponse.UncoveredInterval(interval.dateFrom(), interval.dateTo()))
                .toList();
        log.warn("прайс услуги не покрывает период: {}, service_id={}, {}, request_id={}",
                notCovered.code(), notCovered.serviceId(), intervals, CurrentRequest.id());
        return ErrorResponse.of(notCovered.code(), notCovered.getMessage(), intervals, CurrentRequest.id());
    }

    private static ErrorResponse conflict(
            String journalText, String code, String message, List<? extends ErrorResponse.Detail> candidates) {

        log.warn("{}: {} {}, request_id={}", journalText, code, candidates, CurrentRequest.id());
        return ErrorResponse.of(code, message, candidates, CurrentRequest.id());
    }
}
