package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.ru.Тогда;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

public class InternalApiSteps {

    private static final String PRESENT = "есть";
    private static final Pattern SAME_AS = Pattern.compile("как в ответе №(\\d+)");
    private static final String CREATED_AT = "created_at";

    private final AttachmentCalls calls;
    private final ServiceJournal journal;

    public InternalApiSteps(AttachmentCalls calls, ServiceJournal journal) {
        this.calls = calls;
        this.journal = journal;
    }

    @Тогда("Договоры ответили на загрузку справки {int}:")
    public void answeredUpload(int status, DataTable fields) {
        AttachmentCalls.Answer answer = calls.last();
        assertThat(answer.status()).as("код ответа, тело %s", answer.body()).isEqualTo(status);
        JsonNode body = calls.body(answer);
        for (Map.Entry<String, String> field : fields.asMap().entrySet()) {
            assertField(body, field.getKey(), field.getValue());
        }
    }

    @Тогда("Договоры ответили на загрузку согласия {int}:")
    public void answeredConsentUpload(int status, DataTable fields) {
        answeredUpload(status, fields);
    }

    @Тогда("Договоры ответили ошибкой {int} {string}")
    public void answeredError(int status, String code) {
        AttachmentCalls.Answer answer = calls.last();
        assertThat(answer.status()).as("код ответа, тело %s", answer.body()).isEqualTo(status);
        JsonNode error = calls.body(answer).path("error");
        assertThat(error.path("code").asString()).as("error.code").isEqualTo(code);
        assertThat(error.path("message").asString()).as("error.message").isNotBlank();
        assertThat(answer.requestId()).as("заголовок X-Request-Id ответа").isPresent();
        assertThat(error.path("request_id").asString()).as("error.request_id").isEqualTo(answer.requestId().get());
    }

    @Тогда("в ошибке одно нарушение — по полю {string}")
    public void oneViolation(String field) {
        JsonNode details = calls.body(calls.last()).path("error").path("details");
        assertThat(details.size()).as("число нарушений в %s", details).isEqualTo(1);
        assertThat(details.path(0).path("field").asString()).isEqualTo(field);
        assertThat(details.path(0).path("reason").asString()).as("причина нарушения").isNotBlank();
    }

    @Тогда("в ошибке нет нарушений по полям")
    public void noViolations() {
        JsonNode details = calls.body(calls.last()).path("error").path("details");
        assertThat(details.isMissingNode() || details.isEmpty()).as("нарушения %s", details).isTrue();
    }

    @Тогда("в ответе Договоров заголовок X-Request-Id — {string}")
    public void requestIdEchoed(String expected) {
        assertThat(calls.last().requestId()).contains(expected);
    }

    @Тогда("в ответе Договоров заголовок X-Request-Id — новый UUID")
    public void requestIdIssued() {
        AttachmentCalls.Answer answer = calls.last();
        String issued = answer.requestId().orElseThrow(() -> new AssertionError("в ответе нет заголовка X-Request-Id"));
        assertThat(UUID.fromString(issued)).hasToString(issued);
        assertThat(answer.sentRequestId()).as("присланный заголовок").isNotEqualTo(answer.requestId());
    }

    @Тогда("в журнале Договоров есть запись о вызове с requestId из ответа и кодом ответа {int}")
    public void callJournaledWithAnsweredRequestId(int status) {
        callJournaled(calls.last().requestId()
                .orElseThrow(() -> new AssertionError("в ответе нет заголовка X-Request-Id")), status);
    }

    @Тогда("в журнале Договоров есть запись о вызове с requestId {string} и кодом ответа {int}")
    public void callJournaled(String requestId, int status) {
        SyncCallLogAssertions.assertLogged(journal, "POST", requestId, status);
    }

    private void assertField(JsonNode body, String name, String expected) {
        JsonNode actual = body.path(name);
        assertThat(actual.isMissingNode() || actual.isNull()).as("поле %s в %s", name, body).isFalse();
        Matcher same = SAME_AS.matcher(expected);
        if (same.matches()) {
            assertThat(actual).as("поле %s", name)
                    .isEqualTo(calls.body(calls.number(Integer.parseInt(same.group(1)))).path(name));
            return;
        }
        if (!PRESENT.equals(expected)) {
            assertValue(name, actual.asString(), expected);
        }
        assertThat(actual.asString()).as("поле %s", name).isNotBlank();
    }

    private static void assertValue(String name, String actual, String expected) {
        if (CREATED_AT.equals(name)) {
            assertThat(OffsetDateTime.parse(actual).toInstant()).as("поле %s", name)
                    .isEqualTo(OffsetDateTime.parse(expected).toInstant());
            return;
        }
        assertThat(actual).as("поле %s", name).isEqualTo(expected);
    }
}
