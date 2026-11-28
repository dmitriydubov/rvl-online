package ru.projectx.contracts.acceptance;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.ru.Когда;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

public class AttachmentUploadSteps {

    static final Map<String, String> INVALID_REQUEST_IDS = Map.of(
            "длиной 65 символов", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0",
            "с символом /", "req/01",
            "пустой", "");

    private static final String ATTACHMENT = "3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e21";
    private static final String FILE_NAME = "Тестова_Анна_070у.jpg";
    private static final String CONTENT = "СТЕНД: вымышленная справка 070/у, участник 1";
    private static final String JPEG = "image/jpeg";
    private static final String NOT_A_GUID = "not-a-guid";
    private static final byte[] FILLER = "070u".getBytes(StandardCharsets.US_ASCII);
    private static final int OVER_LIMIT = 5_242_881;

    private static final Map<String, BiFunction<String, String, AttachmentRequest>> DEFECTS = Map.ofEntries(
            Map.entry("тип файла image/tiff", (r, p) -> withFile(standard(r, p), FILE_NAME, "image/tiff", content(CONTENT))),
            Map.entry("тип файла image/gif", (r, p) -> withFile(standard(r, p), FILE_NAME, "image/gif", content(CONTENT))),
            Map.entry("тип файла не указан", (r, p) -> withFile(standard(r, p), FILE_NAME, null, content(CONTENT))),
            Map.entry("пустой файл", (r, p) -> withFile(standard(r, p), FILE_NAME, JPEG, new byte[0])),
            Map.entry("файл на байт больше предела", (r, p) -> withFile(standard(r, p), FILE_NAME, JPEG, filler(OVER_LIMIT))),
            Map.entry("идентификатор заявки в пути — не GUID", (r, p) -> standard(NOT_A_GUID, p)),
            Map.entry("идентификатор участника в пути — не GUID", (r, p) -> standard(r, NOT_A_GUID)),
            Map.entry("нет части attachment_id", (r, p) -> standard(r, p).withAttachment(Optional.empty())),
            Map.entry("attachment_id — не GUID",
                    (r, p) -> standard(r, p).withAttachment(Optional.of(new AttachmentRequest.AttachmentPart(NOT_A_GUID, null)))),
            Map.entry("часть attachment_id передана с непустым filename",
                    (r, p) -> standard(r, p).withAttachment(Optional.of(new AttachmentRequest.AttachmentPart(ATTACHMENT, "id.txt")))),
            Map.entry("часть attachment_id передана с пустым filename",
                    (r, p) -> standard(r, p).withAttachment(Optional.of(new AttachmentRequest.AttachmentPart(ATTACHMENT, "")))),
            Map.entry("нет части file", (r, p) -> standard(r, p).withFile(Optional.empty())),
            Map.entry("часть file без filename", (r, p) -> withFile(standard(r, p), null, JPEG, content(CONTENT))),
            Map.entry("часть file с пустым filename", (r, p) -> withFile(standard(r, p), "", JPEG, content(CONTENT))),
            Map.entry("часть file с пробельным filename", (r, p) -> withFile(standard(r, p), "   ", JPEG, content(CONTENT))),
            Map.entry("тело не multipart", (r, p) -> standard(r, p).asJson()));

    private final AttachmentCalls calls;

    public AttachmentUploadSteps(AttachmentCalls calls) {
        this.calls = calls;
    }

    @Когда("сервис Заявок загружает справку участника {string} заявки {string}:")
    public void upload(String participant, String request, DataTable table) throws IOException, InterruptedException {
        calls.send(build(path(request, participant), table.asMap(), FILLER));
    }

    @Когда("сервис Заявок загружает справку участника {string} заявки {string} с дефектом {string}")
    public void uploadWithDefect(String participant, String request, String defect)
            throws IOException, InterruptedException {
        BiFunction<String, String, AttachmentRequest> spoil = Optional.ofNullable(DEFECTS.get(defect))
                .orElseThrow(() -> new AssertionError("дефекта «" + defect + "» нет в перечне шага"));
        calls.send(spoil.apply(request, participant));
    }

    @Когда("сервис Заявок загружает справку участника {string} заявки {string} с заголовком X-Request-Id {string}:")
    public void uploadWithRequestId(String participant, String request, String requestId, DataTable table)
            throws IOException, InterruptedException {
        calls.send(build(path(request, participant), table.asMap(), FILLER).withRequestId(requestId));
    }

    @Когда("сервис Заявок загружает справку участника {string} заявки {string} с недопустимым заголовком X-Request-Id {string}:")
    public void uploadWithInvalidRequestId(String participant, String request, String kind, DataTable table)
            throws IOException, InterruptedException {
        calls.send(build(path(request, participant), table.asMap(), FILLER).withRequestId(invalidRequestId(kind)));
    }

    static String invalidRequestId(String kind) {
        return Optional.ofNullable(INVALID_REQUEST_IDS.get(kind))
                .orElseThrow(() -> new AssertionError("недопустимого заголовка «" + kind + "» нет в перечне шага"));
    }

    static AttachmentRequest build(String path, Map<String, String> fields, byte[] filler) {
        byte[] content = fields.containsKey("байты в hex")
                ? HexFormat.of().parseHex(required(fields, "байты в hex"))
                : Optional.ofNullable(fields.get("содержимое")).map(AttachmentUploadSteps::content)
                        .orElseGet(() -> repeat(filler, Integer.parseInt(required(fields, "размер"))));
        return new AttachmentRequest(path,
                Optional.of(new AttachmentRequest.AttachmentPart(required(fields, "вложение"), null)),
                Optional.of(new AttachmentRequest.FilePart(required(fields, "имя файла"), required(fields, "тип"),
                        content)),
                Optional.empty(), true);
    }

    static AttachmentRequest withFile(AttachmentRequest request, @Nullable String fileName,
            @Nullable String contentType, byte[] content) {
        return request.withFile(Optional.of(new AttachmentRequest.FilePart(fileName, contentType, content)));
    }

    static byte[] content(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] repeat(byte[] filler, int size) {
        byte[] content = new byte[size];
        for (int index = 0; index < size; index++) {
            content[index] = filler[index % filler.length];
        }
        return content;
    }

    private static AttachmentRequest standard(String request, String participant) {
        return new AttachmentRequest(path(request, participant),
                Optional.of(new AttachmentRequest.AttachmentPart(ATTACHMENT, null)),
                Optional.of(new AttachmentRequest.FilePart(FILE_NAME, JPEG, content(CONTENT))),
                Optional.empty(), true);
    }

    private static String path(String request, String participant) {
        return "/internal/v1/requests/" + request + "/participants/" + participant + "/attachments";
    }

    private static String required(Map<String, String> fields, String name) {
        return Optional.ofNullable(fields.get(name))
                .orElseThrow(() -> new AssertionError("в таблице загрузки нет строки «" + name + "»"));
    }

    private static byte[] filler(int size) {
        return repeat(FILLER, size);
    }
}
