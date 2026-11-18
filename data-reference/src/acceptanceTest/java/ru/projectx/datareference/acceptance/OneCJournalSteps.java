package ru.projectx.datareference.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.cucumber.java.ru.Тогда;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.projectx.datareference.entity.inbound.OneCStream;
import tools.jackson.databind.JsonNode;

public class OneCJournalSteps {

    private static final Map<String, OneCStream> STREAMS = Map.ofEntries(
            Map.entry("контрагенты", OneCStream.CONTRAGENTS),
            Map.entry("договоры ЛПУ", OneCStream.LPU_CONTRACTS),
            Map.entry("услуги контрагентов", OneCStream.CONTRAGENT_SERVICES),
            Map.entry("программы", OneCStream.PROGRAMS),
            Map.entry("контактная информация", OneCStream.LPU_CONTACTS),
            Map.entry("договоры страхования", OneCStream.INSURANCE_CONTRACTS),
            Map.entry("полисы", OneCStream.POLICIES),
            Map.entry("прейскуранты", OneCStream.PRICE_LISTS),
            Map.entry("МКБ-10", OneCStream.MKB10),
            Map.entry("виды обращений", OneCStream.APPEAL_TYPES),
            Map.entry("связи застрахованных", OneCStream.INSURED_ASSOCIATIONS));

    private static final Pattern EXPIRED_WAITS = Pattern.compile(
            "сообщения потока (\\S+) не дождались родителя за срок ожидания \\S+: отклонено (\\d+), "
                    + "первые родители: \\[(.*)]");
    private static final Pattern GUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final OneCInbox inbox;
    private final ServiceJournal journal;

    public OneCJournalSteps(OneCInbox inbox, ServiceJournal journal) {
        this.inbox = inbox;
        this.journal = journal;
    }

    @Тогда("значение поля {string} последнего сообщения есть в его сырье, но не попало ни в причину отказа, ни в журнал Дата-референса")
    public void fieldValueKeptOnlyInRaw(String field) {
        JsonNode sent = OneCFailureMessages.nodeOf(inbox.lastMessage().data(), field);
        InboundRow last = inbox.last();
        JsonNode stored = OneCFailureMessages.storedNodeOf(last.payload(), field);

        assertThat(OneCFailureMessages.sameValue(sent, stored))
                .as("в теле последнего сообщения поле %s со значением как присланное", field).isTrue();
        for (String value : List.of(sent.asString(), stored.asString())) {
            assertThat(last.lastError()).as("причина отказа последнего сообщения").isNotNull().doesNotContain(value);
            assertThat(journal.mentioning(value)).as("записи журнала со значением поля %s", field).isEmpty();
        }
    }

    @Тогда("в журнале Дата-референса есть запись уровня ERROR об окончательном отказе последнего сообщения: поток {string}, попытка {int}, тип {string}, SQLSTATE {string}")
    public void finalRejectionLogged(String stream, int attempt, String type, String sqlState) {
        finalRejectionLogged(inbox.lastMessage().messageId().toString(), stream, attempt, type, sqlState);
    }

    @Тогда("в журнале Дата-референса есть запись уровня ERROR об окончательном отказе сообщения {string}: поток {string}, попытка {int}, тип {string}, SQLSTATE {string}")
    public void finalRejectionLogged(String messageId, String stream, int attempt, String type, String sqlState) {
        assertThat(STREAMS).as("потоки, которые знает шаг").containsKey(stream);
        String expected = "сообщение %s потока %s отклонено окончательно на попытке %d: %s, SQLSTATE %s".formatted(
                messageId, STREAMS.get(stream), attempt, type, sqlState);

        assertThat(journal.events())
                .as("запись ERROR об окончательном отказе: %s", expected)
                .anyMatch(event -> event.getLevel() == Level.ERROR
                        && expected.equals(event.getFormattedMessage())
                        && event.getThrowableProxy() == null);
    }

    @Тогда("в журнале Дата-референса есть ровно одна запись уровня ERROR об истёкших ожиданиях потока {string}: истекло {int}, в перечне {int} GUID родителей")
    public void expiredWaitsSummaryLogged(String stream, int expired, int listed) {
        assertThat(STREAMS).as("потоки, которые знает шаг").containsKey(stream);
        ILoggingEvent summary = onlyError();
        Matcher matcher = EXPIRED_WAITS.matcher(summary.getFormattedMessage());

        assertThat(matcher.matches()).as("сводная запись об истёкших ожиданиях: %s", summary.getFormattedMessage())
                .isTrue();
        assertThat(matcher.group(1)).as("поток в сводной записи").isEqualTo(STREAMS.get(stream).name());
        assertThat(Integer.parseInt(matcher.group(2))).as("число истёкших в сводной записи").isEqualTo(expired);
        List<String> expectedParents = inbox.expiredParentsInOrder(STREAMS.get(stream)).stream()
                .map(UUID::toString)
                .toList();
        assertThat(GUID.matcher(matcher.group(3)).results().map(MatchResult::group).toList())
                .as("GUID родителей в сводной записи: первые по порядку приёма, остальных нет")
                .hasSize(listed)
                .containsExactlyElementsOf(expectedParents.subList(0, Math.min(listed, expectedParents.size())));
    }

    @Тогда("в журнале Дата-референса есть ровно одна запись уровня ERROR об истёкшем ожидании услуги передачи прайса {string}")
    public void expiredTransferLogged(String cid) {
        assertThat(onlyError().getFormattedMessage())
                .as("запись ERROR об истёкшем ожидании услуги передачей")
                .startsWith("передача прайса " + cid + " не дождалась услуги ");
    }

    @Тогда("в журнале Дата-референса есть ровно одна запись уровня ERROR об истёкшем ожидании полиса передачи связей застрахованных {string}")
    public void expiredAssociationTransferLogged(String cid) {
        assertThat(onlyError().getFormattedMessage())
                .as("запись ERROR об истёкшем ожидании полиса передачей связей")
                .startsWith("передача связей застрахованных " + cid + " не дождалась полиса ");
    }

    private ILoggingEvent onlyError() {
        List<ILoggingEvent> errors = journal.events().stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
        assertThat(errors).as("записи уровня ERROR в журнале").hasSize(1);
        assertThat(errors.getFirst().getThrowableProxy()).as("исключение, приложенное к записи ERROR").isNull();
        return errors.getFirst();
    }
}
