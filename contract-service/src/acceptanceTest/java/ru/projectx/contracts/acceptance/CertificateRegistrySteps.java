package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.ru.Дано;
import io.cucumber.java.ru.Тогда;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

public class CertificateRegistrySteps {

    private static final String ANY_UUID = "{uuid}";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String KEY_COLUMN = "ключ объекта";
    private static final String CREATED_COLUMN = "создана";
    private static final String DELETED_COLUMN = "удалена";
    private static final String DELETED_REQUEST_COLUMN = "запрос удаления";
    private static final String NONE = "нет";
    private static final String FROM_ANSWER = "из ответа";
    private static final Map<String, Function<CertificateRecords.Certificate, String>> COLUMNS = Map.of(
            "вложение", certificate -> certificate.attachment().toString(),
            "doc_type", CertificateRecords.Certificate::docType,
            "content_type", CertificateRecords.Certificate::contentType,
            "размер", certificate -> Long.toString(certificate.size()),
            "file_hash", CertificateRecords.Certificate::hash);

    private final CertificateRecords records;
    private final StandClock clock;
    private final AttachmentCalls calls;

    public CertificateRegistrySteps(CertificateRecords records, StandClock clock, AttachmentCalls calls) {
        this.records = records;
        this.clock = clock;
        this.calls = calls;
    }

    @Дано("вложение {string} в реестре документов помечено удалённым")
    public void markDeleted(String attachment) {
        records.markDeleted(attachment, clock.instant(), "stand-delete-request");
    }

    @Тогда("справки участника {string} заявки {string} в реестре документов:")
    public void certificates(String participant, String request, DataTable expected) {
        assertRecords("справки участника " + participant,
                records.of(UUID.fromString(request), UUID.fromString(participant)), expected);
    }

    @Тогда("в реестре документов нет справок участника {string} заявки {string}")
    public void noCertificates(String participant, String request) {
        assertThat(records.of(UUID.fromString(request), UUID.fromString(participant)))
                .as("справки участника %s", participant).isEmpty();
    }

    @Тогда("согласия родственника {string} по полису {string} в реестре документов:")
    public void consents(String familyMember, String policy, DataTable expected) {
        assertRecords("согласия родственника " + familyMember,
                records.ofConsent(UUID.fromString(policy), UUID.fromString(familyMember)), expected);
    }

    @Тогда("в реестре документов нет согласий родственника {string} по полису {string}")
    public void noConsents(String familyMember, String policy) {
        assertThat(records.ofConsent(UUID.fromString(policy), UUID.fromString(familyMember)))
                .as("согласия родственника %s", familyMember).isEmpty();
    }

    @Тогда("в реестре документов запись по вложению {string} сохранила удалена {string} и запрос удаления {string}")
    public void deletionRequisitesKept(String attachment, String deletedAt, String deletedRequestId) {
        CertificateRecords.Certificate stored = records.byAttachment(attachment);
        assertDeletedAt(stored, deletedAt);
        assertDeletedRequest(stored, deletedRequestId);
    }

    private void assertRecords(String description, List<CertificateRecords.Certificate> stored, DataTable expected) {
        List<Map<String, String>> rows = expected.asMaps();
        assertThat(stored).as(description).hasSize(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            matches(stored.get(index), rows.get(index));
        }
    }

    static String keyPattern(String expected) {
        return Pattern.quote(expected).replace(ANY_UUID, "\\E" + UUID_PATTERN + "\\Q");
    }

    private void matches(CertificateRecords.Certificate certificate, Map<String, String> row) {
        for (Map.Entry<String, String> cell : row.entrySet()) {
            matchesCell(certificate, cell.getKey(), cell.getValue());
        }
    }

    private void matchesCell(CertificateRecords.Certificate certificate, String column, String expected) {
        switch (column) {
            case KEY_COLUMN -> assertThat(certificate.key()).as("ключ объекта").matches(keyPattern(expected));
            case CREATED_COLUMN -> assertThat(certificate.createdAt()).as("момент записи")
                    .isEqualTo(OffsetDateTime.parse(expected).toInstant());
            case DELETED_COLUMN -> assertDeletedAt(certificate, expected);
            case DELETED_REQUEST_COLUMN -> assertDeletedRequest(certificate, expected);
            default -> {
                Function<CertificateRecords.Certificate, String> value = Optional.ofNullable(COLUMNS.get(column))
                        .orElseThrow(() -> new AssertionError("столбца «" + column + "» нет в словаре шага"));
                assertThat(value.apply(certificate)).as("столбец %s", column).isEqualTo(expected);
            }
        }
    }

    private static void assertDeletedAt(CertificateRecords.Certificate certificate, String expected) {
        if (NONE.equals(expected)) {
            assertThat(certificate.deletedAt()).as("удалена").isNull();
            return;
        }
        assertThat(certificate.deletedAt()).as("удалена").isEqualTo(OffsetDateTime.parse(expected).toInstant());
    }

    private void assertDeletedRequest(CertificateRecords.Certificate certificate, String expected) {
        if (NONE.equals(expected)) {
            assertThat(certificate.deletedRequestId()).as("запрос удаления").isNull();
            return;
        }
        String resolved = FROM_ANSWER.equals(expected)
                ? calls.last().requestId().orElseThrow(() -> new AssertionError("в ответе нет заголовка X-Request-Id"))
                : expected;
        assertThat(certificate.deletedRequestId()).as("запрос удаления").isEqualTo(resolved);
    }
}
