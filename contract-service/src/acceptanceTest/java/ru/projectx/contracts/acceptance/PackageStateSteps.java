package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.ru.Когда;
import io.cucumber.java.ru.Тогда;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import tools.jackson.databind.JsonNode;

public class PackageStateSteps {

    private static final String PARTICIPANT = "участник";
    private static final String DOCUMENTS = "documents";
    private static final String CONTRACT_ID = "contract_id";
    private static final String PRESENT = "есть";
    private static final String NONE = "нет";
    private static final String NESTED = ".";
    private static final Set<String> AMOUNTS = Set.of("premium_amount", "payment.amount");
    private static final Set<String> MOMENTS = Set.of("acceptance.accepted_at", "payment.expires_at");
    private static final String LM_POLICY_KIND = "LM_POLICY";
    private static final String DOCUMENT_SEPARATOR = "; ";

    private final AttachmentCalls calls;
    private final IssuanceRecords records;
    private final PrintedFormRecords forms;
    private final ServiceJournal journal;
    private final TaskMessages messages;
    private final PublishedMessages published;

    public PackageStateSteps(AttachmentCalls calls, IssuanceRecords records, PrintedFormRecords forms,
            ServiceJournal journal, TaskMessages messages, PublishedMessages published) {
        this.calls = calls;
        this.records = records;
        this.forms = forms;
        this.journal = journal;
        this.messages = messages;
        this.published = published;
    }

    @Когда("сервис Заявок запрашивает состояние пакета по заявке {string}")
    public void requestsPackage(String request) throws IOException, InterruptedException {
        calls.exchange("GET", path(request), Optional.empty(), Optional.empty());
    }

    @Когда("сервис Заявок запрашивает состояние пакета по заявке {string} с заголовком X-Request-Id {string}")
    public void requestsPackageWithRequestId(String request, String requestId) throws IOException, InterruptedException {
        calls.exchange("GET", path(request), Optional.of(requestId), Optional.empty());
    }

    @Когда("сервис Заявок запрашивает состояние пакета по заявке {string} с недопустимым заголовком X-Request-Id {string}")
    public void requestsPackageWithInvalidRequestId(String request, String kind)
            throws IOException, InterruptedException {
        calls.exchange("GET", path(request), Optional.of(AttachmentUploadSteps.invalidRequestId(kind)), Optional.empty());
    }

    @Тогда("Договоры ответили состоянием пакета {int}: заявка {string}, статус {string}, участники:")
    public void answeredPackage(int status, String request, String requestStatus, List<Map<String, String>> rows) {
        AttachmentCalls.Answer answer = calls.last();
        assertThat(answer.status()).as("код ответа, тело %s", answer.body()).isEqualTo(status);
        JsonNode body = calls.body(answer);
        assertThat(body.path("individual_request_id").asString()).isEqualTo(request);
        assertThat(body.path("status").asString()).as("статус заявки").isEqualTo(requestStatus);
        JsonNode items = body.path("items");
        assertThat(items.isArray()).as("items — массив в %s", body).isTrue();
        assertThat(items.size()).as("число участников в %s", items).isEqualTo(rows.size());
        SoftAssertions softly = new SoftAssertions();
        for (int index = 0; index < rows.size(); index++) {
            item(softly, request, rows.get(index), items.path(index));
        }
        softly.assertAll();
    }

    @Тогда("в журнале Договоров есть запись о вызове GET с requestId из ответа и кодом ответа {int}")
    public void getJournaled(int status) {
        String requestId = calls.last().requestId()
                .orElseThrow(() -> new AssertionError("в ответе нет заголовка X-Request-Id"));
        getJournaledWithRequestId(requestId, status);
    }

    @Тогда("в журнале Договоров есть запись о вызове GET с requestId {string} и кодом ответа {int}")
    public void getJournaledWithRequestId(String requestId, int status) {
        SyncCallLogAssertions.assertLogged(journal, "GET", requestId, status);
    }

    @Тогда("значение поля {string} сообщения {string} есть в его теле, но не попало в ответ Договоров о состоянии пакета")
    public void personalValueStaysOutOfThePackage(String field, String messageId) {
        String value = ServiceLogSteps.value(messages.read(published.of(messageId)), field)
                .orElseThrow(() -> new AssertionError("в опубликованной задаче нет поля " + field));
        assertThat(value).as("значение поля %s", field).isNotBlank();
        assertThat(calls.last().body()).as("ответ о состоянии пакета").doesNotContain(value);
    }

    private void item(SoftAssertions softly, String request, Map<String, String> row, JsonNode item) {
        String participant = row.get(PARTICIPANT);
        softly.assertThat(item.path("participant_id").asString()).as("участник").isEqualTo(participant);
        row.forEach((column, expected) -> column(softly, request, participant, item, column,
                Objects.requireNonNullElse(expected, "")));
        nestedObjects(softly, row, item);
    }

    private void column(SoftAssertions softly, String request, String participant, JsonNode item, String column,
            String expected) {
        if (PARTICIPANT.equals(column)) {
            return;
        }
        if (DOCUMENTS.equals(column)) {
            documents(softly, item, new ParticipantRef(request, participant), expected);
            return;
        }
        JsonNode actual = node(item, column);
        String described = participant + " / " + column;
        if (expected.isEmpty()) {
            softly.assertThat(actual.isMissingNode() || actual.isNull()).as("поля %s нет: %s", described, actual).isTrue();
            return;
        }
        softly.assertThat(actual.isMissingNode() || actual.isNull()).as("поле %s есть", described).isFalse();
        value(softly, described, column, actual, expectedValue(request, participant, column, expected));
    }

    private String expectedValue(String request, String participant, String column, String expected) {
        return CONTRACT_ID.equals(column) && PRESENT.equals(expected)
                ? records.latestContractId(participant, request).toString() : expected;
    }

    private static void value(SoftAssertions softly, String described, String column, JsonNode actual, String expected) {
        if (AMOUNTS.contains(column)) {
            softly.assertThat(actual.isNumber()).as("%s — число", described).isTrue();
            softly.assertThat(actual.decimalValue()).as(described).isEqualByComparingTo(new BigDecimal(expected));
            return;
        }
        if (MOMENTS.contains(column)) {
            softly.assertThat(OffsetDateTime.parse(actual.asString()).toInstant()).as(described)
                    .isEqualTo(OffsetDateTime.parse(expected).toInstant());
            return;
        }
        softly.assertThat(actual.asString()).as(described).isEqualTo(expected);
    }

    private record ParticipantRef(String request, String participant) {
    }

    private void documents(SoftAssertions softly, JsonNode item, ParticipantRef ref, String expected) {
        JsonNode actual = item.path(DOCUMENTS);
        softly.assertThat(actual.isArray()).as("documents участника %s — массив", ref.participant()).isTrue();
        if (NONE.equals(expected)) {
            softly.assertThat(actual.size()).as("documents участника %s", ref.participant()).isZero();
            return;
        }
        UUID owner = owner(item, ref.request(), ref.participant());
        String[] expectedDocuments = expected.split(DOCUMENT_SEPARATOR);
        softly.assertThat(actual.size()).as("documents записи %s", owner).isEqualTo(expectedDocuments.length);
        for (int index = 0; index < expectedDocuments.length; index++) {
            String[] parts = expectedDocuments[index].split(" ");
            JsonNode document = actual.path(index);
            softly.assertThat(document.path("doc_type").asString()).isEqualTo(parts[0]);
            softly.assertThat(document.path("kind").asString()).isEqualTo(parts[1]);
            softly.assertThat(document.path("version").asString()).isEqualTo(parts[2]);
            softly.assertThat(document.path("url").asString()).isEqualTo(parts[3]);
            softly.assertThat(document.path("document_id").asString()).as("document_id")
                    .isEqualTo(formId(owner, parts[0], parts[1]));
        }
    }

    private UUID owner(JsonNode item, String request, String participant) {
        return LM_POLICY_KIND.equals(item.path("document_kind").asString())
                ? records.latestLmPolicyId(participant, request)
                : records.latestContractId(participant, request);
    }

    private String formId(UUID owner, String docType, String kind) {
        return forms.of(owner).stream()
                .filter(form -> docType.equals(form.docType()) && kind.equals(form.kind()))
                .map(form -> form.id().toString())
                .findFirst()
                .orElseThrow(() -> new AssertionError("у записи " + owner + " нет формы " + docType + " " + kind));
    }

    private static void nestedObjects(SoftAssertions softly, Map<String, String> row, JsonNode item) {
        row.keySet().stream().filter(column -> column.contains(NESTED))
                .map(column -> column.substring(0, column.indexOf(NESTED)))
                .distinct()
                .filter(object -> row.entrySet().stream()
                        .filter(cell -> cell.getKey().startsWith(object + NESTED))
                        .allMatch(cell -> cell.getValue() == null || cell.getValue().isEmpty()))
                .forEach(object -> softly.assertThat(item.path(object).isMissingNode() || item.path(object).isNull())
                        .as("объекта %s нет у участника %s", object, row.get(PARTICIPANT)).isTrue());
    }

    private static JsonNode node(JsonNode item, String column) {
        JsonNode node = item;
        for (String name : column.split("\\.")) {
            node = node.path(name);
        }
        return node;
    }

    private static String path(String request) {
        return "/internal/v1/requests/" + request + "/package";
    }
}
