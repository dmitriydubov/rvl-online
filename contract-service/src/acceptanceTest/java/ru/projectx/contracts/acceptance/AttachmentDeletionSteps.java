package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.ru.Когда;
import io.cucumber.java.ru.Тогда;
import java.io.IOException;
import java.util.Optional;
import ru.projectx.contracts.config.storage.StorageProperties;

public class AttachmentDeletionSteps {

    private static final String WITHOUT_HEADER = "без заголовка";

    private final AttachmentCalls calls;
    private final CertificateRecords records;
    private final StandStorage storage;
    private final StorageProperties settings;
    private final ServiceJournal journal;
    private final AttachmentTargets targets;

    public AttachmentDeletionSteps(AttachmentCalls calls, CertificateRecords records, StandStorage storage,
            StorageProperties settings, ServiceJournal journal, AttachmentTargets targets) {
        this.calls = calls;
        this.records = records;
        this.storage = storage;
        this.settings = settings;
        this.journal = journal;
        this.targets = targets;
    }

    @Когда("сервис Заявок удаляет вложение {string}")
    public void deletesAttachment(String attachment) throws IOException, InterruptedException {
        calls.exchange("DELETE", path(attachment), Optional.empty(), Optional.empty());
    }

    @Когда("сервис Заявок удаляет вложение {string} с заголовком X-Request-Id {string}")
    public void deletesAttachmentWithRequestId(String attachment, String requestId)
            throws IOException, InterruptedException {
        calls.exchange("DELETE", path(attachment), Optional.of(requestId), Optional.empty());
    }

    @Когда("сервис Заявок удаляет вложение {string} с выбором заголовка X-Request-Id {string}")
    public void deletesAttachmentWithHeaderChoice(String attachment, String choice)
            throws IOException, InterruptedException {
        Optional<String> requestId = WITHOUT_HEADER.equals(choice)
                ? Optional.empty()
                : Optional.of(AttachmentUploadSteps.invalidRequestId(choice));
        calls.exchange("DELETE", path(attachment), requestId, Optional.empty());
    }

    @Когда("сервис Заявок удаляет вложение по цели {string}")
    public void deletesAttachmentByTarget(String target) throws IOException, InterruptedException {
        calls.exchange("DELETE", path(targets.deletionTarget(target)), Optional.empty(), Optional.empty());
    }

    @Когда("сервис Заявок удаляет вложение с дефектом пути {string}")
    public void deletesAttachmentWithPathDefect(String defect) throws IOException, InterruptedException {
        calls.exchange("DELETE", path(targets.defectSegment(defect)), Optional.empty(), Optional.empty());
    }

    @Тогда("Договоры ответили пустым ответом {int}")
    public void answeredWithEmptyResponse(int status) {
        AttachmentCalls.Answer answer = calls.last();
        assertThat(answer.status()).as("код ответа, тело %s", answer.body()).isEqualTo(status);
        assertThat(answer.rawBody()).as("сырые байты тела ответа").isEmpty();
        String requestId = answer.requestId()
                .orElseThrow(() -> new AssertionError("в ответе нет заголовка X-Request-Id"));
        assertThat(requestId).as("заголовок X-Request-Id").isNotBlank();
    }

    @Тогда("объекта вложения {string} нет в хранилище")
    public void objectAbsentFromStorage(String attachment) throws Exception {
        String key = records.byAttachment(attachment).key();
        assertThat(storage.keys(settings.bucket(), ""))
                .as("ключи объектов в бакете %s", settings.bucket())
                .doesNotContain(key);
    }

    @Когда("стенд создаёт пустой бакет Договоров в хранилище справок")
    public void createsEmptyBucket() throws Exception {
        storage.recreateBucket(settings.bucket());
    }

    @Тогда("в журнале Договоров есть запись о вызове DELETE с requestId из ответа и кодом ответа {int}")
    public void deleteJournaled(int status) {
        String requestId = calls.last().requestId()
                .orElseThrow(() -> new AssertionError("в ответе нет заголовка X-Request-Id"));
        deleteJournaledWithRequestId(requestId, status);
    }

    @Тогда("в журнале Договоров есть запись о вызове DELETE с requestId {string} и кодом ответа {int}")
    public void deleteJournaledWithRequestId(String requestId, int status) {
        SyncCallLogAssertions.assertLogged(journal, "DELETE", requestId, status);
    }

    private static String path(String attachment) {
        return "/internal/v1/attachments/" + attachment;
    }
}
