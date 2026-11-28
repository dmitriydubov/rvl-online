package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.ru.Дано;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class AttachmentLockedCasesSteps {

    private static final String REQUEST = "61a7982c-a0b4-4bbf-9527-60b40b752803";
    private static final String PARTICIPANT = "9275471c-d2fe-484f-97a6-d6d7b1267b5e";
    private static final String ATTACHMENT = "3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e21";
    private static final String SUBMISSIONS_QUEUE = "передачи договоров КМ";

    private static final List<Map<String, String>> PARTICIPANTS = List.of(
            Map.of("участник", "ae8312c3-350b-4d0b-806c-5e2460e74dbc", "родственник", "нет", "доплата", "0.00"),
            Map.of("участник", PARTICIPANT, "родственник", "да", "доплата", "34000.00", "родство", "SPOUSE",
                    "программа", "2f6a0c1e-3d5b-4a7c-9e8f-0b1c2d3e4f5a"));
    private static final Map<String, String> ACCEPTANCE = Map.of(
            "contract_version", "1",
            "accepted_by", "b0a1c7e4-52d9-4f31-8c06-7e3a9b1d2f45",
            "email", "insured@example.test",
            "accepted_at", "2026-02-10T10:04:58+03:00",
            "channel", "WEB");
    private static final Map<String, String> REGISTERED = Map.of(
            "status", "REGISTERED",
            "contract_usk_id", "a71f3c98-4e20-4a55-93b1-5d0c7e2f8814",
            "contract_usk_number", "DKM-000004417",
            "policy.external_id", "9e4d2c1b-7a35-4f68-b2c0-5d1e8f3a6b74",
            "policy.number", "0987654321",
            "policy.date_from", "2026-04-10",
            "policy.date_to", "2026-04-24",
            "documents", "[]");
    private static final Map<String, String> REJECTED = Map.of(
            "status", "ERROR",
            "error_code", "CONTRACT_REJECTED",
            "error_message", "Стенд: регистрация договора невозможна");
    private static final Map<String, String> CANCELLATION = Map.of(
            "причина", "INSURED_REFUSED",
            "комментарий", "Стенд: комментарий",
            "аннулировано", "2026-02-12T14:30:00+03:00");

    private final AttachmentCalls calls;
    private final ReferenceStubSteps references;
    private final TaskPublishingSteps tasks;
    private final InboxSteps inbox;
    private final OutboxSteps outbox;
    private final AcceptanceSteps acceptance;
    private final KmContractPaymentSteps payments;
    private final KmContractSubmissionSteps submissions;
    private final OneCQueueSteps oneCQueues;
    private final OneCPublishingSteps oneC;
    private final KmContractManualReturnSteps manualReturn;

    public AttachmentLockedCasesSteps(AttachmentCalls calls, ReferenceStubSteps references, TaskPublishingSteps tasks,
            InboxSteps inbox, OutboxSteps outbox, AcceptanceSteps acceptance, KmContractPaymentSteps payments,
            KmContractSubmissionSteps submissions, OneCQueueSteps oneCQueues, OneCPublishingSteps oneC,
            KmContractManualReturnSteps manualReturn) {
        this.calls = calls;
        this.references = references;
        this.tasks = tasks;
        this.inbox = inbox;
        this.outbox = outbox;
        this.acceptance = acceptance;
        this.payments = payments;
        this.submissions = submissions;
        this.oneCQueues = oneCQueues;
        this.oneC = oneC;
        this.manualReturn = manualReturn;
    }

    @Дано("стенд подготовил случай блокировки удаления справки: {string}")
    public void prepareLockedCase(String caseName) throws IOException, InterruptedException {
        switch (caseName) {
            case "договор КМ в SUBMITTED" -> submitWithCertificate();
            case "договор КМ в ISSUED" -> submitAndApplyUskResult(REGISTERED);
            case "договор КМ в ERROR после недоставки передачи" -> undeliverSubmission();
            case "договор КМ в ERROR по ответу 1С:УСК" -> submitAndApplyUskResult(REJECTED);
            case "договор КМ возвращён дежурным в PAID после недоставки" -> {
                undeliverSubmission();
                manualReturn.returnedByProcedure(PARTICIPANT, REQUEST);
            }
            case "договор КМ в CANCELLED после прекращения ДГ-02" -> cancelSubmittedContract();
            case "прежний договор КМ прекращён после передачи и открыт новый в DRAFT_READY" -> {
                cancelSubmittedContract();
                tasks.taskPublished("d41a8e20-6f33-4c19-9b52-8e0d1c7a4f66", REQUEST, 3, PARTICIPANTS);
                inbox.everythingProcessed();
            }
            case "новая справка дозагружена через СЭ-04 после передачи договора КМ" -> uploadAfterSubmission();
            default -> throw new AssertionError("случая блокировки «" + caseName + "» нет в перечне шага");
        }
    }

    private void prepareRequestAndTaskV1() {
        references.programDefined(Map.of("id", "2f6a0c1e-3d5b-4a7c-9e8f-0b1c2d3e4f5a",
                "external_id", "6b02f1cc-9d47-4e35-8a20-7f1c3e5b09a2", "в архиве", "false"));
        references.insuranceContractDefined(Map.of("id", "e12aab1a-1582-4b3a-af36-d30e637953f1",
                "external_id", "8c41e2ab-55d0-4a17-9b63-2f0e7c1d4a88", "в архиве", "false"));
        references.policyDefined(Map.of("id", "a23ba061-f742-4b20-8b95-36a0e525c087",
                "external_id", "1d9a6e77-4c02-4b88-a15e-30f7b2c9de41", "номер", "СТЕНД-0001", "в архиве", "false"));
        references.policyDefined(Map.of("id", "2c7e9b40-1f5a-4d83-9e6b-0a4c8d2f7e15",
                "external_id", "9e4d2c1b-7a35-4f68-b2c0-5d1e8f3a6b74", "номер", "0987654321", "в архиве", "false"));
        tasks.requestDefined(Map.of(
                "заявка", REQUEST, "номер", "IZ-2026-004512",
                "массовая заявка", "c9bfd8fc-7ed2-4fcc-a67f-b8afe814b36f",
                "договор страхования", "e12aab1a-1582-4b3a-af36-d30e637953f1",
                "полис страхователя", "a23ba061-f742-4b20-8b95-36a0e525c087",
                "ЛПУ", "1b1b4fdf-bd2a-4055-a127-8c24c544a23f",
                "заезд", "2026-04-10", "выезд", "2026-04-24"));
        tasks.taskPublished("b81f0f3a-1c44-4f2a-9a1e-5a0e2f7c9d11", REQUEST, 1, PARTICIPANTS);
        inbox.everythingProcessed();
        outbox.publicationPass();
    }

    private void uploadCertificate() throws IOException, InterruptedException {
        calls.send(AttachmentUploadSteps.build(
                "/internal/v1/requests/" + REQUEST + "/participants/" + PARTICIPANT + "/attachments",
                Map.of(
                        "вложение", ATTACHMENT,
                        "имя файла", "Тестова_Анна_Ивановна_J45.jpg",
                        "тип", "image/jpeg",
                        "содержимое", "СТЕНД: вымышленная справка 070/у, участник 1"),
                new byte[0]));
        assertThat(calls.last().status()).as("загрузка справки %s", ATTACHMENT).isEqualTo(200);
    }

    private void acceptAndPay() throws IOException, InterruptedException {
        acceptance.acceptsContractOf(PARTICIPANT, REQUEST, ACCEPTANCE);
        payments.contractPaid(PARTICIPANT, REQUEST, "2026-02-10T07:05:00Z");
    }

    private void submitWithCertificate() throws IOException, InterruptedException {
        prepareRequestAndTaskV1();
        uploadCertificate();
        acceptAndPay();
        submissions.preparationPass();
    }

    private void submitAndApplyUskResult(Map<String, String> result) throws IOException, InterruptedException {
        submitWithCertificate();
        outbox.publicationPass();
        oneC.registrationPublished("0c6a9f31-58b2-4e77-91d0-3a7e5b1c22f9", PARTICIPANT, REQUEST, result);
        inbox.everythingProcessed();
    }

    private void undeliverSubmission() throws IOException, InterruptedException {
        prepareRequestAndTaskV1();
        uploadCertificate();
        acceptAndPay();
        oneCQueues.queueDeleted(SUBMISSIONS_QUEUE);
        submissions.preparationPass();
        outbox.publicationPass();
        outbox.publicationPass();
        oneCQueues.queueRedeclared(SUBMISSIONS_QUEUE);
    }

    private void cancelSubmittedContract() throws IOException, InterruptedException {
        submitWithCertificate();
        outbox.publicationPass();
        tasks.cancellationPublished("c3f80a67-9d12-4b58-8e44-0a7b1f25e903", REQUEST, 2, CANCELLATION);
        inbox.everythingProcessed();
    }

    private void uploadAfterSubmission() throws IOException, InterruptedException {
        prepareRequestAndTaskV1();
        acceptAndPay();
        submissions.preparationPass();
        outbox.publicationPass();
        uploadCertificate();
    }
}
