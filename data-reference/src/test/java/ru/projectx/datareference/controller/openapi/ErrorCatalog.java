package ru.projectx.datareference.controller.openapi;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.UnsatisfiedServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import ru.projectx.datareference.exception.AmbiguousPriceException;
import ru.projectx.datareference.exception.AmbiguousPolicyNumberException;
import ru.projectx.datareference.exception.AmbiguousReferenceException;
import ru.projectx.datareference.exception.PolicyNumberCandidate;
import ru.projectx.datareference.exception.IncompleteReferenceException;
import ru.projectx.datareference.exception.InvalidRequestException;
import ru.projectx.datareference.exception.MissingReference;
import ru.projectx.datareference.exception.PriceNotCoveredException;
import ru.projectx.datareference.exception.RestOverloadException;

final class ErrorCatalog {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final LocalDate DAY = LocalDate.of(2026, 1, 1);
    private static final String FIELD = "id";
    private static final String TEXT = "сбой";

    private ErrorCatalog() {
    }

    static List<Exception> failures() {
        return Stream.of(referenceFailures(), requestFailures(), serverFailures()).flatMap(Stream::of).toList();
    }

    private static Exception[] referenceFailures() {
        return Stream.concat(Arrays.stream(MissingReference.values()).map(missing -> missing.notFound(ID)), Stream.of(
                        AmbiguousReferenceException.policy(List.of(ID)),
                        AmbiguousPolicyNumberException.of(List.of(new PolicyNumberCandidate(ID, ID))),
                        AmbiguousReferenceException.priceContract(ID, List.of(ID)),
                        AmbiguousReferenceException.insuranceContract(ID, List.of(ID)),
                        IncompleteReferenceException.noActivePriceContract(ID),
                        new AmbiguousPriceException(ID, List.of(new AmbiguousPriceException.PriceRow(DAY, null))),
                        new PriceNotCoveredException(ID, List.of(new PriceNotCoveredException.Interval(DAY, DAY)))))
                .toArray(Exception[]::new);
    }

    private static Exception[] requestFailures() {
        return new Exception[] {
            new InvalidRequestException(FIELD, TEXT),
            new MissingServletRequestParameterException(FIELD, UUID.class.getSimpleName()),
            new MissingPathVariableException(FIELD, failingParameter(), true),
            new MethodArgumentTypeMismatchException(TEXT, UUID.class, FIELD, failingParameter(), null),
            new UnsatisfiedServletRequestParameterException(new String[] {FIELD}, Map.of()),
            new NoResourceFoundException(HttpMethod.GET, "/unknown", "unknown"),
            new HttpRequestMethodNotSupportedException(HttpMethod.POST.name(), List.of(HttpMethod.GET.name())),
            new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON))
        };
    }

    private static Exception[] serverFailures() {
        return new Exception[] {
            new IllegalStateException(TEXT),
            new CannotGetJdbcConnectionException(TEXT, new SQLException(TEXT)),
            new RestOverloadException(1)
        };
    }

    private static MethodParameter failingParameter() {
        Method fail = ReflectionUtils.findMethod(FailureController.class, "fail", int.class);
        return new MethodParameter(fail, 0);
    }
}
