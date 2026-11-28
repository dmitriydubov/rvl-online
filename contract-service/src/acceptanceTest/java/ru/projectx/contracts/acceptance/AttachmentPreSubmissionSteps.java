package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.ru.Дано;
import java.io.IOException;
import java.util.Map;

public class AttachmentPreSubmissionSteps {

    private static final Map<String, String> ACCEPTANCE = Map.of(
            "contract_version", "1",
            "accepted_by", "b0a1c7e4-52d9-4f31-8c06-7e3a9b1d2f45",
            "email", "insured@example.test",
            "accepted_at", "2026-02-10T10:04:58+03:00",
            "channel", "WEB");
    private static final Map<String, String> CANCELLATION = Map.of(
            "причина", "INSURED_REFUSED",
            "комментарий", "Стенд: комментарий",
            "аннулировано", "2026-02-10T10:05:00+03:00");

    private final IssuanceRecords records;
    private final AcceptanceSteps acceptance;
    private final KmContractPaymentSteps payments;
    private final OrderingStubSteps orderingStub;
    private final PaymentOrderSteps paymentOrders;
    private final OutboxSteps outbox;
    private final OrderEventSteps orderEvents;
    private final InvoiceSteps invoices;
    private final TaskPublishingSteps tasks;
    private final InboxSteps inbox;

    public AttachmentPreSubmissionSteps(IssuanceRecords records, AcceptanceSteps acceptance,
            KmContractPaymentSteps payments, OrderingStubSteps orderingStub, PaymentOrderSteps paymentOrders,
            OutboxSteps outbox, OrderEventSteps orderEvents, InvoiceSteps invoices, TaskPublishingSteps tasks,
            InboxSteps inbox) {
        this.records = records;
        this.acceptance = acceptance;
        this.payments = payments;
        this.orderingStub = orderingStub;
        this.paymentOrders = paymentOrders;
        this.outbox = outbox;
        this.orderEvents = orderEvents;
        this.invoices = invoices;
        this.tasks = tasks;
        this.inbox = inbox;
    }

    @Дано("договор КМ участника {string} заявки {string} доведён до этапа до передачи в 1С:УСК: {string}")
    public void advanceContractToStage(String participant, String request, String stage)
            throws IOException, InterruptedException {
        switch (stage) {
            case "DRAFT_READY" -> assertThat(records.latestContractId(participant, request)).isNotNull();
            case "ACCEPTED" -> accept(participant, request);
            case "INVOICE_ISSUED" -> issueInvoice(participant, request);
            case "PAID без подготовки передачи" -> {
                accept(participant, request);
                payments.contractPaid(participant, request, "2026-02-10T07:05:00Z");
            }
            case "CANCELLED по ДГ-02 без передачи" -> {
                tasks.cancellationPublished("c3f80a67-9d12-4b58-8e44-0a7b1f25e903", request, 2, CANCELLATION);
                inbox.everythingProcessed();
                assertThat(records.latestContractId(participant, request)).isNotNull();
            }
            default -> throw new AssertionError("этапа «" + stage + "» нет в перечне шага");
        }
    }

    private void accept(String participant, String request) throws IOException, InterruptedException {
        acceptance.acceptsContractOf(participant, request, ACCEPTANCE);
    }

    private void issueInvoice(String participant, String request) throws IOException, InterruptedException {
        accept(participant, request);
        orderingStub.creationAnswers("создан 0196f0a2-5b1c-7d3e-9f40-1a2b3c4d5e01");
        paymentOrders.creationPass();
        outbox.publicationPass();
        orderEvents.eventPublished("d1a0c001-5e2f-4a3b-8c4d-000000000001", "fixed",
                "0196f0a2-5b1c-7d3e-9f40-1a2b3c4d5e01");
        inbox.everythingProcessed();
        invoices.checkoutAnswers(
                "счёт 7c1e5a90-3b42-4d6f-8a17-9e0f2b6c4d31 со ссылкой https://pay.example.test/pay/7c1e5a90");
        invoices.issuingPass();
    }
}
