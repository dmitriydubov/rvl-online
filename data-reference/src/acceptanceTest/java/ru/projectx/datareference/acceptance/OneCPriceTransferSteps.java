package ru.projectx.datareference.acceptance;

import io.cucumber.java.ru.Дано;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class OneCPriceTransferSteps {

    private final OneCInbox inbox;

    public OneCPriceTransferSteps(OneCInbox inbox) {
        this.inbox = inbox;
    }

    @Дано("1С:УСК прислала финальное сообщение {string} передачи прайса {string} по услуге {string}: пакетов {int}, строк {int}")
    public void finalMessageSent(String messageId, String cid, String service, int batches, int rows) {
        inbox.receive(OneCPriceTransferMessages.finalMessage(
                UUID.fromString(messageId), UUID.fromString(cid), service, batches, rows));
    }

    @Дано("1С:УСК прислала пакет {string} передачи прайса {string} по услуге {string}:")
    public void batchSent(String messageId, String cid, String service, List<Map<String, String>> rows) {
        inbox.receive(OneCPriceTransferMessages.batch(UUID.fromString(messageId), UUID.fromString(cid), service, rows));
    }

    @Дано("1С:УСК прислала пакет {string} передачи прайса {string} по услуге {string} из одной строки, в которой {string}")
    public void defectiveBatchSent(String messageId, String cid, String service, String defect) {
        inbox.receive(OneCPriceTransferMessages.defectiveBatch(
                UUID.fromString(messageId), UUID.fromString(cid), service, defect));
    }

    @Дано("1С:УСК прислала пакет {string} передачи прайса {string} по услуге {string} без массива строк: {string}")
    public void batchWithoutRowsArraySent(String messageId, String cid, String service, String defect) {
        inbox.receive(OneCPriceTransferMessages.batchWithoutRowsArray(
                UUID.fromString(messageId), UUID.fromString(cid), service, defect));
    }

    @Дано("1С:УСК прислала пакет {string} передачи прайса {string} по услуге {string} с пустым массивом строк")
    public void batchWithEmptyRowsArraySent(String messageId, String cid, String service) {
        batchSent(messageId, cid, service, List.of());
    }

    @Дано("1С:УСК прислала пакет {string} прайса по услуге {string} без идентификатора передачи:")
    public void batchWithoutTransferSent(String messageId, String service, List<Map<String, String>> rows) {
        inbox.receive(OneCPriceTransferMessages.batchWithoutTransfer(UUID.fromString(messageId), service, rows));
    }
}
