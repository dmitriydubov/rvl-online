package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.ru.Дано;
import io.cucumber.java.ru.Когда;
import io.cucumber.java.ru.Тогда;
import java.io.IOException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import ru.projectx.contracts.config.storage.StorageProperties;

public class AttachmentContentSteps {

    private final AttachmentCalls calls;
    private final CertificateRecords records;
    private final StandStorage storage;
    private final StorageProperties settings;
    private final AttachmentTargets targets;

    public AttachmentContentSteps(AttachmentCalls calls, CertificateRecords records, StandStorage storage,
            StorageProperties settings, AttachmentTargets targets) {
        this.calls = calls;
        this.records = records;
        this.storage = storage;
        this.settings = settings;
        this.targets = targets;
    }

    @Дано("объект согласия по вложению {string} удалён из хранилища")
    public void consentObjectRemoved(String attachment) throws Exception {
        storage.removeObject(settings.bucket(), records.byAttachment(attachment).key());
    }

    @Когда("стенд создаёт бакет Договоров и возвращает объект справки по вложению {string}")
    public void bucketCreatedAndCertificateRestored(String attachment) throws Exception {
        storage.recreateBucket(settings.bucket());
        storage.putObject(settings.bucket(), records.byAttachment(attachment).key(), calls.acceptedContent(attachment));
    }

    @Когда("сервис Заявок запрашивает содержимое вложения {string}")
    public void requestsContent(String attachment) throws IOException, InterruptedException {
        calls.exchange("GET", path(attachment), Optional.empty(), Optional.empty());
    }

    @Когда("сервис Заявок запрашивает содержимое вложения {string} с заголовком X-Request-Id {string}")
    public void requestsContentWithRequestId(String attachment, String requestId)
            throws IOException, InterruptedException {
        calls.exchange("GET", path(attachment), Optional.of(requestId), Optional.empty());
    }

    @Когда("сервис Заявок запрашивает содержимое вложения {string} с недопустимым заголовком X-Request-Id {string}")
    public void requestsContentWithInvalidRequestId(String attachment, String kind)
            throws IOException, InterruptedException {
        calls.exchange("GET", path(attachment), Optional.of(AttachmentUploadSteps.invalidRequestId(kind)),
                Optional.empty());
    }

    @Когда("сервис Заявок запрашивает содержимое по цели {string}")
    public void requestsContentByTarget(String target) throws IOException, InterruptedException {
        calls.exchange("GET", path(targets.contentTarget(target)), Optional.empty(), Optional.empty());
    }

    @Когда("сервис Заявок запрашивает содержимое вложения с дефектом пути {string}")
    public void requestsContentWithPathDefect(String defect) throws IOException, InterruptedException {
        calls.exchange("GET", path(targets.defectSegment(defect)), Optional.empty(), Optional.empty());
    }

    @Тогда("Договоры ответили содержимым вложения {int} с заголовками:")
    public void answeredWithContentHeaders(int status, DataTable headers) {
        AttachmentCalls.Answer answer = calls.last();
        assertThat(answer.status()).as("код ответа").isEqualTo(status);
        for (Map.Entry<String, String> entry : headers.asMap().entrySet()) {
            assertThat(answer.headers().firstValue(entry.getKey()))
                    .as("заголовок %s", entry.getKey())
                    .contains(entry.getValue());
        }
    }

    @Тогда("поток байтов ответа побайтово совпадает с отправленным файлом вложения {string}")
    public void responseBytesMatchAcceptedFile(String attachment) {
        assertThat(calls.last().rawBody())
                .as("сырые байты ответа по вложению %s", attachment)
                .isEqualTo(calls.acceptedContent(attachment));
    }

    @Тогда("поток байтов ответа в hex — {string}")
    public void responseBytesInHex(String expectedHex) {
        assertThat(HexFormat.of().formatHex(calls.last().rawBody()))
                .as("поток байтов ответа в hex")
                .isEqualTo(expectedHex);
    }

    private static String path(String attachment) {
        return "/internal/v1/attachments/" + attachment + "/content";
    }
}
