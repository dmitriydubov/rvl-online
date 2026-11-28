package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.ru.Дано;
import io.cucumber.java.ru.Когда;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

public class AttachmentRepeatDeletionSteps {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String REQUEST = "61a7982c-a0b4-4bbf-9527-60b40b752803";
    private static final String PARTICIPANT = "9275471c-d2fe-484f-97a6-d6d7b1267b5e";
    private static final String CERTIFICATE = "3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e21";
    private static final String CONSENT = "4d3a9f21-6b8c-4e32-8a7f-2c1b8d5e9f32";
    private static final String AFTER_SUBMISSION = "удалённая справка 070/у после последующей передачи договора КМ";
    private static final Map<String, String> ACCEPTANCE = Map.of(
            "contract_version", "1",
            "accepted_by", "b0a1c7e4-52d9-4f31-8c06-7e3a9b1d2f45",
            "email", "insured@example.test",
            "accepted_at", "2026-02-10T10:04:58+03:00",
            "channel", "WEB");

    private final AttachmentCalls calls;
    private final StandClock clock;
    private final AcceptanceSteps acceptance;
    private final KmContractPaymentSteps payments;
    private final KmContractSubmissionSteps submissions;
    private final OutboxSteps outbox;
    private String rememberedAttachment = "";

    public AttachmentRepeatDeletionSteps(AttachmentCalls calls, StandClock clock, AcceptanceSteps acceptance,
            KmContractPaymentSteps payments, KmContractSubmissionSteps submissions, OutboxSteps outbox) {
        this.calls = calls;
        this.clock = clock;
        this.acceptance = acceptance;
        this.payments = payments;
        this.submissions = submissions;
        this.outbox = outbox;
    }

    @Дано("стенд подготовил случай повторного удаления {string} с первичным requestId {string} в момент {string} по Москве")
    public void prepareRepeatDeletion(String caseName, String firstRequestId, String moscowMoment)
            throws IOException, InterruptedException {
        rememberedAttachment = uploadForCase(caseName);
        advanceTo(moscowMoment);
        calls.exchange("DELETE", "/internal/v1/attachments/" + rememberedAttachment, Optional.of(firstRequestId),
                Optional.empty());
        assertThat(calls.last().status()).as("первичное удаление вложения").isEqualTo(204);
        if (AFTER_SUBMISSION.equals(caseName)) {
            acceptance.acceptsContractOf(PARTICIPANT, REQUEST, ACCEPTANCE);
            payments.contractPaid(PARTICIPANT, REQUEST, "2026-02-10T07:10:00Z");
            submissions.preparationPass();
            outbox.publicationPass();
        }
    }

    @Когда("сервис Заявок повторно удаляет вложение подготовленного случая с заголовком X-Request-Id {string}")
    public void repeatDeletion(String repeatRequestId) throws IOException, InterruptedException {
        assertThat(rememberedAttachment).as("вложение подготовленного случая").isNotBlank();
        calls.exchange("DELETE", "/internal/v1/attachments/" + rememberedAttachment, Optional.of(repeatRequestId),
                Optional.empty());
    }

    private String uploadForCase(String caseName) throws IOException, InterruptedException {
        return switch (caseName) {
            case "удалённая справка 070/у", AFTER_SUBMISSION -> {
                upload("/internal/v1/requests/" + REQUEST + "/participants/" + PARTICIPANT + "/attachments",
                        CERTIFICATE, "Тестова_Анна_070у.jpg", "image/jpeg",
                        "СТЕНД: вымышленная справка 070/у, участник 1");
                yield CERTIFICATE;
            }
            case "удалённое согласие на обработку ПДн" -> {
                upload("/internal/v1/policies/a23ba061-f742-4b20-8b95-36a0e525c087/family-members/"
                                + "c45dc283-1964-4d42-8d17-58c2a747e209/consents",
                        CONSENT, "Тестова_Ольга_согласие.pdf", "application/pdf",
                        "СТЕНД: вымышленное согласие на обработку ПДн, родственник 1");
                yield CONSENT;
            }
            default -> throw new AssertionError("случая повторного удаления «" + caseName + "» нет в перечне шага");
        };
    }

    private void upload(String path, String attachment, String fileName, String type, String text)
            throws IOException, InterruptedException {
        calls.send(AttachmentUploadSteps.build(path, Map.of(
                "вложение", attachment,
                "имя файла", fileName,
                "тип", type,
                "содержимое", text), new byte[0]));
        assertThat(calls.last().status()).as("загрузка вложения %s", attachment).isEqualTo(200);
    }

    private void advanceTo(String moscowMoment) {
        Instant target = LocalDateTime.parse(moscowMoment, MOMENT).atZone(MOSCOW).toInstant();
        Duration delta = Duration.between(clock.instant(), target);
        assertThat(delta).as("сдвиг часов до %s", moscowMoment).isPositive();
        clock.advance(delta);
    }
}
