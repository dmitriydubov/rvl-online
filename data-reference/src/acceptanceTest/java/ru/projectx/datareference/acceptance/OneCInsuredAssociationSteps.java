package ru.projectx.datareference.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.ru.Дано;
import io.cucumber.java.ru.Тогда;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.projectx.datareference.entity.inbound.OneCStream;
import tools.jackson.databind.JsonNode;

public class OneCInsuredAssociationSteps {

    private static final String NONE = "нет";
    private static final String ROWS_QUEUE = "связи застрахованных";
    private static final String FINAL_QUEUE = "связи застрахованных, финальные";
    private static final String ASSOCIATIONS = """
            select cast(related_policy_external_id as text) as related, cast(relation_type_external_id as text) as type,
                   cast(valid_from as text) as valid_from, cast(valid_to as text) as valid_to
              from insured_associations
             where insured_policy_external_id = :insured
            """;
    private static final String TEXT_COLUMNS = """
            select table_name, column_name
              from information_schema.columns
             where table_schema = current_schema()
               and data_type in ('text', 'character varying', 'character', 'json', 'jsonb')
               and not (table_name = 'inbound_message' and column_name = 'payload')
            """;

    private final OneCInbox inbox;
    private final OneCBroker broker;
    private final JdbcClient jdbc;
    private final ServiceJournal journal;
    private final Map<UUID, Integer> rowsSent = new HashMap<>();

    public OneCInsuredAssociationSteps(OneCInbox inbox, OneCBroker broker, JdbcClient jdbc, ServiceJournal journal) {
        this.inbox = inbox;
        this.broker = broker;
        this.jdbc = jdbc;
        this.journal = journal;
    }

    @Дано("1С:УСК прислала строки {string} передачи связей застрахованных {string} с отбором {string}:")
    public void rowsSent(String messageId, String cid, String filter, List<Map<String, String>> rows) {
        inbox.receive(rows(UUID.fromString(messageId), UUID.fromString(cid), filter, rows));
    }

    @Дано("1С:УСК прислала строки {string} передачи связей застрахованных {string} с отбором {string}, где в поле {string} записано {string}:")
    public void rowsWithFieldSent(String messageId, String cid, String filter, String field, String value,
            List<Map<String, String>> rows) {
        List<Map<String, String>> edited = rows.stream()
                .map(row -> OneCInsuredAssociationMessages.withRowField(row, field, value))
                .toList();
        rowsSent(messageId, cid, filter, edited);
    }

    @Дано("1С:УСК прислала сообщение со строками {string} передачи связей застрахованных {string} с отбором {string} без массива строк: {string}")
    public void rowsMessageWithoutRowsArraySent(String messageId, String cid, String filter, String defect) {
        inbox.receive(OneCInsuredAssociationMessages.withoutRowsArray(
                UUID.fromString(messageId), UUID.fromString(cid), filter, defect));
    }

    @Дано("1С:УСК прислала сообщение со строками {string} передачи связей застрахованных {string} с отбором {string} с пустым массивом строк")
    public void rowsMessageWithEmptyRowsArraySent(String messageId, String cid, String filter) {
        rowsSent(messageId, cid, filter, List.of());
    }

    @Дано("1С:УСК прислала финальное сообщение {string} передачи связей застрахованных {string} с отбором {string}: сообщений {int}, строк {int}")
    public void finalSent(String messageId, String cid, String filter, int messages, int rows) {
        inbox.receive(OneCInsuredAssociationMessages.finalMessage(
                UUID.fromString(messageId), UUID.fromString(cid), filter, messages, rows));
    }

    @Дано("1С:УСК прислала полную передачу связей застрахованных с отбором {string}:")
    public void wholeTransferSent(String filter, List<Map<String, String>> rows) {
        UUID cid = UUID.randomUUID();
        inbox.receive(rows(UUID.randomUUID(), cid, filter, rows));
        inbox.receive(OneCInsuredAssociationMessages.finalMessage(UUID.randomUUID(), cid, filter, 1, rows.size()));
    }

    @Дано("1С:УСК публикует в свою очередь строки {string} передачи связей застрахованных {string} с отбором {string}:")
    public void rowsPublished(String messageId, String cid, String filter, List<Map<String, String>> rows) {
        publish(ROWS_QUEUE, rows(UUID.fromString(messageId), UUID.fromString(cid), filter, rows));
    }

    @Дано("1С:УСК публикует в свою очередь финальное сообщение {string} передачи связей застрахованных {string} с отбором {string}: сообщений {int}, строк {int}")
    public void finalPublished(String messageId, String cid, String filter, int messages, int rows) {
        publish(FINAL_QUEUE, OneCInsuredAssociationMessages.finalMessage(
                UUID.fromString(messageId), UUID.fromString(cid), filter, messages, rows));
    }

    @Тогда("сырьё передачи связей застрахованных {string} в порядке приёма — сообщения {string}")
    public void transferRawInOrder(String cid, String messageIds) {
        List<UUID> expected = Stream.of(messageIds.split("\\s*,\\s*")).map(UUID::fromString).toList();

        assertThat(inbox.transferMessageIds(UUID.fromString(cid)))
                .as("message-id сырья передачи %s в порядке приёма", cid)
                .containsExactlyElementsOf(expected);
    }

    @Тогда("связи застрахованного {string} в базе Дата-референса:")
    public void associationsStored(String insured, List<Map<String, String>> expected) {
        List<Map<String, String>> stored = associationsOf(insured).stream()
                .map(OneCInsuredAssociationSteps::asTableRow)
                .toList();

        assertThat(stored).as("связи застрахованного %s", insured).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Тогда("связанные полисы застрахованного {string} в базе Дата-референса — {string}")
    public void relatedPoliciesStored(String insured, String related) {
        List<String> expected = NONE.equals(related) ? List.of() : List.of(related.split("\\s*,\\s*"));

        assertThat(associationsOf(insured)).extracting(association -> association.get("related"))
                .as("связанные полисы застрахованного %s", insured)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Тогда("представления связанных застрахованных из присланных сообщений связей не сохранены в базе Дата-референса вне тел сообщений сырья и не попали в журнал Дата-референса")
    public void representationsKeptOnlyInRaw() {
        List<String> representations = inbox.receivedOf(OneCStream.INSURED_ASSOCIATIONS).stream()
                .flatMap(message -> representationsIn(message.data()))
                .distinct()
                .toList();
        assertThat(representations).as("ФИО связанных застрахованных в присланных сообщениях").isNotEmpty();

        for (String representation : representations) {
            assertThat(columnsMentioning(representation)).as("колонки базы с ФИО связанного застрахованного")
                    .isEmpty();
            assertThat(journal.mentioning(representation)).as("записи журнала с ФИО связанного застрахованного")
                    .isEmpty();
        }
    }

    private OneCMessage rows(UUID messageId, UUID cid, String filter, List<Map<String, String>> rows) {
        int firstRow = rowsSent.getOrDefault(cid, 0) + 1;
        rowsSent.put(cid, firstRow + rows.size() - 1);
        return OneCInsuredAssociationMessages.rows(messageId, cid, filter, rows, firstRow);
    }

    private void publish(String queue, OneCMessage message) {
        inbox.expect(message);
        broker.publish(broker.queueOf(queue), message.messageId(), message.envelope().getBytes(StandardCharsets.UTF_8));
    }

    private List<Map<String, Object>> associationsOf(String insured) {
        return jdbc.sql(ASSOCIATIONS).param("insured", UUID.fromString(insured)).query().listOfRows();
    }

    private static Map<String, String> asTableRow(Map<String, Object> association) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("связанный полис", String.valueOf(association.get("related")));
        row.put("тип связи", String.valueOf(association.get("type")));
        row.put("начало", String.valueOf(association.get("valid_from")));
        row.put("окончание", association.get("valid_to") == null ? NONE : String.valueOf(association.get("valid_to")));
        return row;
    }

    private List<String> columnsMentioning(String value) {
        return jdbc.sql(TEXT_COLUMNS).query((row, number) -> new String[] {row.getString(1), row.getString(2)}).list()
                .stream()
                .filter(column -> mentions(column[0], column[1], value))
                .map(column -> column[0] + "." + column[1])
                .toList();
    }

    private boolean mentions(String table, String column, String value) {
        String sql = "select count(*) from \"%s\" where cast(\"%s\" as text) like :pattern".formatted(table, column);
        return jdbc.sql(sql).param("pattern", "%" + value + "%").query(Long.class).single() > 0;
    }

    private static Stream<String> representationsIn(JsonNode node) {
        Stream<String> own = node.isObject() && node.path("representation").isString()
                ? Stream.of(node.path("representation").asString())
                : Stream.empty();
        return Stream.concat(own, node.valueStream().flatMap(OneCInsuredAssociationSteps::representationsIn));
    }
}
