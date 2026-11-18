package ru.projectx.datareference.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.ru.Тогда;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public class OneCOutcomeSteps {

    private static final JsonMapper JSON = JsonMapper.shared();
    private static final String DATA = "data";

    private static final Map<String, InboundStatus> OUTCOMES = Map.of(
            "применено", InboundStatus.PROCESSED,
            "ждёт родителя", InboundStatus.AWAITING_PARENT,
            "пропущено", InboundStatus.SKIPPED,
            "отложено до повтора", InboundStatus.RETRY,
            "отклонено без повторов", InboundStatus.FAILED,
            "ждёт разбора", InboundStatus.NEW,
            "ждёт сборки передачи", InboundStatus.BUFFERED);

    private final OneCInbox inbox;

    public OneCOutcomeSteps(OneCInbox inbox) {
        this.inbox = inbox;
    }

    @Тогда("последнее сообщение 1С:УСК отклонено без повторов с причиной {string}")
    public void lastMessageRejected(String reason) {
        InboundRow last = inbox.last();

        assertThat(last.status()).as("статус последнего сообщения").isEqualTo(InboundStatus.FAILED.name());
        assertThat(last.lastError()).as("причина отказа последнего сообщения").isEqualTo(reason);
        inbox.settle(inbox.lastMessageId(), InboundStatus.FAILED);
    }

    @Тогда("сообщение 1С:УСК {string} отклонено без повторов с причиной {string}")
    public void messageRejected(String messageId, String reason) {
        InboundRow row = inbox.row(UUID.fromString(messageId));

        assertThat(row.status()).as("статус сообщения %s", messageId).isEqualTo(InboundStatus.FAILED.name());
        assertThat(row.lastError()).as("причина отказа сообщения %s", messageId).isEqualTo(reason);
        inbox.settle(UUID.fromString(messageId), InboundStatus.FAILED);
    }

    @Тогда("последнее сообщение 1С:УСК отложено до повтора после попытки {int} с причиной {string}")
    public void lastMessagePostponed(int attempt, String reason) {
        assertPostponed(inbox.last(), "последнего", attempt, reason);
    }

    @Тогда("первое сообщение 1С:УСК отложено до повтора после попытки {int} с причиной {string}")
    public void firstMessagePostponed(int attempt, String reason) {
        assertPostponed(inbox.first(), "первого", attempt, reason);
    }

    @Тогда("сообщение 1С:УСК {string} отложено до повтора после попытки {int} с причиной {string}")
    public void messagePostponed(String messageId, int attempt, String reason) {
        assertPostponed(inbox.row(UUID.fromString(messageId)), messageId, attempt, reason);
    }

    @Тогда("исходы сообщений 1С:УСК в порядке приёма — {string}")
    public void outcomesInOrder(String outcomes) {
        List<String> expected = Stream.of(outcomes.split(",")).map(String::strip).map(OneCOutcomeSteps::status).toList();

        assertThat(inbox.statuses()).as("статусы сырья в порядке приёма").containsExactlyElementsOf(expected);
    }

    @Тогда("исходы сообщений передачи прайса {string} в порядке приёма — {string}")
    @Тогда("исходы сообщений передачи связей застрахованных {string} в порядке приёма — {string}")
    public void transferOutcomesInOrder(String cid, String outcomes) {
        List<String> expected = Stream.of(outcomes.split(",")).map(String::strip).map(OneCOutcomeSteps::status).toList();

        assertThat(inbox.transferStatuses(UUID.fromString(cid)))
                .as("статусы частей передачи %s в порядке приёма", cid)
                .containsExactlyElementsOf(expected);
    }

    private static String status(String outcome) {
        assertThat(OUTCOMES).as("исходы, которые знает шаг").containsKey(outcome);
        return OUTCOMES.get(outcome).name();
    }

    private static void assertPostponed(InboundRow row, String which, int attempt, String reason) {
        assertThat(row.status()).as("статус %s сообщения", which).isEqualTo(InboundStatus.RETRY.name());
        assertThat(row.attempt()).as("отложенных попыток %s сообщения", which).isEqualTo(attempt);
        assertThat(row.lastError()).as("причина отказа %s сообщения", which).isEqualTo(reason);
    }

    @Тогда("сырьё последнего сообщения сохранено и содержит {string}")
    public void lastMessageRawKept(String value) {
        assertRawKept(inbox.last().payload(), inbox.lastEnvelope(), "последнего сообщения", value);
    }

    @Тогда("сырьё сообщения 1С:УСК {string} сохранено и содержит {string}")
    public void messageRawKept(String messageId, String value) {
        UUID id = UUID.fromString(messageId);
        assertRawKept(inbox.row(id).payload(), inbox.envelopeOf(id), "сообщения " + messageId, value);
    }

    private static void assertRawKept(String payload, String sent, String which, String value) {
        assertThat(payload).as("тело %s", which).isNotNull();
        JsonNode kept = JSON.readTree(payload);
        assertThat(kept).as("тело %s — присланный конверт целиком", which).isEqualTo(JSON.readTree(sent));
        assertThat(stringsOf(kept.path(DATA)))
                .as("строковые значения data %s", which)
                .contains(value);
    }

    private static List<String> stringsOf(JsonNode node) {
        return withNested(node).filter(JsonNode::isString).map(JsonNode::stringValue).toList();
    }

    private static Stream<JsonNode> withNested(JsonNode node) {
        return Stream.concat(Stream.of(node), node.valueStream().flatMap(OneCOutcomeSteps::withNested));
    }
}
