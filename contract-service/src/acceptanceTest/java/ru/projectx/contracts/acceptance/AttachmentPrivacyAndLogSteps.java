package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.cucumber.java.ru.Тогда;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.test.web.server.LocalManagementPort;

public class AttachmentPrivacyAndLogSteps {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ServiceJournal journal;
    private final AttachmentCalls calls;
    private final CertificateRecords records;
    private final URI metrics;

    public AttachmentPrivacyAndLogSteps(ServiceJournal journal, AttachmentCalls calls, CertificateRecords records,
            @LocalManagementPort int managementPort) {
        this.journal = journal;
        this.calls = calls;
        this.records = records;
        this.metrics = URI.create("http://localhost:" + managementPort + "/actuator/prometheus");
    }

    @Тогда("в журнале Договоров есть запись уровня WARN по вложению {string} с причиной {string}")
    public void attachmentWarningLogged(String attachment, String reason) {
        String expected = "вложение " + attachment + ": " + reason;
        List<ILoggingEvent> matching = journal.events().stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().equals(expected))
                .toList();
        assertThat(matching).as("записи WARN «%s»", expected).hasSize(1);
        assertThat(matching.get(0).getThrowableProxy()).as("приложенное исключение").isNull();
    }

    @Тогда("в журнале Договоров нет записей уровня WARN")
    public void noWarnings() {
        assertThat(journal.events()).filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage).isEmpty();
    }

    @Тогда("имя файла {string}, содержимое вложения {string} и ключ его объекта не попали ни в журнал Договоров, ни в метрики")
    public void attachmentSecretsStayOutOfLogsAndMetrics(String fileName, String attachment)
            throws IOException, InterruptedException {
        for (String value : sensitiveValues(fileName, attachment)) {
            assertThat(journal.mentioning(value)).extracting(ILoggingEvent::getFormattedMessage)
                    .as("записи журнала с «%s»", value).isEmpty();
        }
        String exposition = exposition();
        for (String value : sensitiveValues(fileName, attachment)) {
            assertThat(exposition).as("выдача метрик").doesNotContain(value);
        }
    }

    @Тогда("имя файла {string}, содержимое вложения {string} и ключ его объекта не попали ни в журнал Договоров, ни в метрики, ни в ответ")
    public void attachmentSecretsStayOutOfLogsMetricsAndResponse(String fileName, String attachment)
            throws IOException, InterruptedException {
        attachmentSecretsStayOutOfLogsAndMetrics(fileName, attachment);
        String body = calls.last().body();
        for (String value : sensitiveValues(fileName, attachment)) {
            assertThat(body).as("тело ответа").doesNotContain(value);
        }
    }

    private List<String> sensitiveValues(String fileName, String attachment) {
        String content = new String(calls.sentContent(attachment), StandardCharsets.UTF_8);
        String objectKey = records.byAttachment(attachment).key();
        assertThat(fileName).isNotBlank();
        assertThat(content).isNotBlank();
        assertThat(objectKey).isNotBlank();
        return List.of(fileName, content, objectKey);
    }

    private String exposition() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(metrics).timeout(TIMEOUT).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("ответ /actuator/prometheus").isEqualTo(200);
        return response.body();
    }
}
