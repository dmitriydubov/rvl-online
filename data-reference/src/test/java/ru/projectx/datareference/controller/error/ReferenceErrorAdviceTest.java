package ru.projectx.datareference.controller.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import ru.projectx.datareference.controller.FailingController;
import ru.projectx.datareference.controller.StandaloneMvc;
import ru.projectx.datareference.controller.request.RequestIdFilter;
import ru.projectx.datareference.exception.AmbiguousPolicyNumberException;
import ru.projectx.datareference.exception.AmbiguousPriceException;
import ru.projectx.datareference.exception.PolicyNumberCandidate;
import ru.projectx.datareference.exception.AmbiguousReferenceException;
import ru.projectx.datareference.exception.IncompleteReferenceException;
import ru.projectx.datareference.exception.PriceNotCoveredException;
import ru.projectx.datareference.exception.MissingReference;

@ExtendWith(OutputCaptureExtension.class)
class ReferenceErrorAdviceTest {

    private static final String REQUEST_ID = "handler-test-1";
    private static final UUID FIRST = UUID.fromString("0a9e0000-0000-4000-8000-000000000001");
    private static final UUID SECOND = UUID.fromString("0a9e0000-0000-4000-8000-000000000002");

    private final FailingController controller = new FailingController();
    private MockMvcTester mvc;

    @BeforeEach
    void setUp() {
        mvc = StandaloneMvc.withRequestId(controller, new ReferenceErrorAdvice());
    }

    @Test
    void referenceNotFound_isNotFound_withSameBodyAsBefore() throws UnsupportedEncodingException {
        controller.failWith(MissingReference.PROGRAM.notFound(FIRST));

        MvcTestResult result = request();

        assertThat(result).hasStatus(404);
        assertThat(body(result)).isEqualTo("{\"error\":{\"code\":\"PROGRAM_NOT_FOUND\","
                + "\"message\":\"программа страхования " + FIRST + " не найдена\","
                + "\"details\":[],\"requestId\":\"" + REQUEST_ID + "\"}}");
    }

    @Test
    void ambiguousPolicy_isConflict_withSameBodyAsBefore() throws UnsupportedEncodingException {
        controller.failWith(AmbiguousReferenceException.policy(List.of(FIRST, SECOND)));

        MvcTestResult result = request();

        assertThat(result).hasStatus(409);
        assertThat(body(result)).isEqualTo("{\"error\":{\"code\":\"AMBIGUOUS_POLICY\","
                + "\"message\":\"по номеру полиса в договоре найдено больше одной записи\","
                + "\"details\":[{\"policyId\":\"" + FIRST + "\"},{\"policyId\":\"" + SECOND + "\"}],"
                + "\"requestId\":\"" + REQUEST_ID + "\"}}");
    }

    @Test
    void ambiguousPriceContract_isConflict_withSameBodyAsBefore() throws UnsupportedEncodingException {
        controller.failWith(AmbiguousReferenceException.priceContract(FIRST, List.of(SECOND)));

        MvcTestResult result = request();

        assertThat(result).hasStatus(409);
        assertThat(body(result)).isEqualTo("{\"error\":{\"code\":\"AMBIGUOUS_PRICE_CONTRACT\","
                + "\"message\":\"у ЛПУ " + FIRST + " больше одного действующего кандидата на прайсовый договор\","
                + "\"details\":[{\"lpuContractId\":\"" + SECOND + "\"}],"
                + "\"requestId\":\"" + REQUEST_ID + "\"}}");
    }

    @Test
    void ambiguousInsuranceContract_isConflict_listingContractCandidates() throws UnsupportedEncodingException {
        controller.failWith(AmbiguousReferenceException.insuranceContract(FIRST, List.of(SECOND, FIRST)));

        MvcTestResult result = request();

        assertThat(result).hasStatus(409);
        assertThat(body(result)).isEqualTo("{\"error\":{\"code\":\"AMBIGUOUS_INSURANCE_CONTRACT\","
                + "\"message\":\"у страхователя " + FIRST + " больше одного действующего договора страхования\","
                + "\"details\":[{\"insuranceContractId\":\"" + SECOND + "\"},"
                + "{\"insuranceContractId\":\"" + FIRST + "\"}],"
                + "\"requestId\":\"" + REQUEST_ID + "\"}}");
    }

    @Test
    void ambiguousPolicyNumber_isConflict_listingPoliciesWithTheirContractGuids_andLoggedAsWarning(
            CapturedOutput output) throws UnsupportedEncodingException {

        controller.failWith(AmbiguousPolicyNumberException.of(List.of(
                new PolicyNumberCandidate(FIRST, SECOND), new PolicyNumberCandidate(SECOND, FIRST))));

        MvcTestResult result = request();

        assertThat(result).hasStatus(409);
        assertThat(body(result)).isEqualTo("{\"error\":{\"code\":\"AMBIGUOUS_POLICY\","
                + "\"message\":\"по номеру полиса найдено больше одной записи\","
                + "\"details\":[{\"policyId\":\"" + FIRST + "\",\"contractExternalId\":\"" + SECOND + "\"},"
                + "{\"policyId\":\"" + SECOND + "\",\"contractExternalId\":\"" + FIRST + "\"}],"
                + "\"requestId\":\"" + REQUEST_ID + "\"}}");
        assertThat(output).contains("WARN", "по номеру полиса найдено несколько полисов: AMBIGUOUS_POLICY",
                "PolicyCandidate[policyId=" + FIRST + ", contractExternalId=" + SECOND + "]",
                "request_id=" + REQUEST_ID).doesNotContain("ERROR");
    }

    @Test
    void incompleteReference_isUnprocessable_withSameBodyAsBefore() throws UnsupportedEncodingException {
        controller.failWith(IncompleteReferenceException.noActivePriceContract(FIRST));

        MvcTestResult result = request();

        assertThat(result).hasStatus(422);
        assertThat(body(result)).isEqualTo("{\"error\":{\"code\":\"NO_ACTIVE_PRICE_CONTRACT\","
                + "\"message\":\"у ЛПУ " + FIRST + " нет действующего договора, по которому брать цены\","
                + "\"details\":[],\"requestId\":\"" + REQUEST_ID + "\"}}");
    }

    @Test
    void ambiguousReference_isLoggedAsWarning_withCandidatesAndRequestId(CapturedOutput output) {
        controller.failWith(AmbiguousReferenceException.policy(List.of(FIRST)));

        request();

        assertThat(output).contains("WARN", "неоднозначные данные 1С: AMBIGUOUS_POLICY",
                "PolicyCandidate[policyId=" + FIRST + ", contractExternalId=null]", "request_id=" + REQUEST_ID)
                .doesNotContain("ERROR");
    }

    @Test
    void incompleteReference_isLoggedAsWarning_withCodeLpuAndRequestId(CapturedOutput output) {
        controller.failWith(IncompleteReferenceException.noActivePriceContract(FIRST));

        request();

        assertThat(output).contains("WARN", "данных 1С не хватает для ответа: NO_ACTIVE_PRICE_CONTRACT",
                "lpu_id=" + FIRST, "request_id=" + REQUEST_ID).doesNotContain("ERROR");
    }

    @Test
    void serviceNotFound_isNotFound_namingServiceReference() {
        controller.failWith(MissingReference.SERVICE.notFound(FIRST));

        MvcTestResult result = request();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo("SERVICE_NOT_FOUND");
        assertThat(result).bodyJson().extractingPath("$.error.message").isEqualTo("услуга " + FIRST + " не найдена");
    }

    @Test
    void serviceOutsidePriceContract_isUnprocessable_asNoActivePriceContract(CapturedOutput output) {
        controller.failWith(IncompleteReferenceException.serviceOutsidePriceContract(FIRST));

        MvcTestResult result = request();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo("NO_ACTIVE_PRICE_CONTRACT");
        assertThat(result).bodyJson().extractingPath("$.error.message")
                .isEqualTo("услуга не относится к прайсовому договору ЛПУ " + FIRST);
        assertThat(output).contains("WARN", "lpu_id=" + FIRST);
    }

    @Test
    void ambiguousPrice_isConflict_listingPriceRowsByValidity(CapturedOutput output) {
        controller.failWith(new AmbiguousPriceException(FIRST, List.of(
                new AmbiguousPriceException.PriceRow(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 20)),
                new AmbiguousPriceException.PriceRow(LocalDate.of(2026, 4, 15), null))));

        MvcTestResult result = request();

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo("AMBIGUOUS_PRICE");
        assertThat(result).bodyJson().extractingPath("$.error.details[0].validFrom").isEqualTo("2026-04-01");
        assertThat(result).bodyJson().extractingPath("$.error.details[0].validTo").isEqualTo("2026-04-20");
        assertThat(result).bodyJson().extractingPath("$.error.details[1].validFrom").isEqualTo("2026-04-15");
        assertThat(result).bodyJson().extractingPath("$.error.details[1].validTo").isNull();
        assertThat(result).bodyJson().extractingPath("$.error.requestId").isEqualTo(REQUEST_ID);
        assertThat(output).contains("WARN", "AMBIGUOUS_PRICE", "service_id=" + FIRST, "request_id=" + REQUEST_ID)
                .doesNotContain("ERROR");
    }

    @Test
    void priceNotCovered_isUnprocessable_listingUncoveredIntervals(CapturedOutput output) {
        controller.failWith(new PriceNotCoveredException(FIRST, List.of(
                new PriceNotCoveredException.Interval(LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 12)))));

        MvcTestResult result = request();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error.code").isEqualTo("PRICE_NOT_COVERED");
        assertThat(result).bodyJson().extractingPath("$.error.details[0].dateFrom").isEqualTo("2026-04-10");
        assertThat(result).bodyJson().extractingPath("$.error.details[0].dateTo").isEqualTo("2026-04-12");
        assertThat(result).bodyJson().extractingPath("$.error.requestId").isEqualTo(REQUEST_ID);
        assertThat(output).contains("WARN", "PRICE_NOT_COVERED", "service_id=" + FIRST, "request_id=" + REQUEST_ID)
                .doesNotContain("ERROR");
    }

    private MvcTestResult request() {
        return mvc.get().uri(FailingController.FAILING).header(RequestIdFilter.HEADER, REQUEST_ID).exchange();
    }

    private static String body(MvcTestResult result) throws UnsupportedEncodingException {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
