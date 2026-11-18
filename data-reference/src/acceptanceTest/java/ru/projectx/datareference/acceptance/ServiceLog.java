package ru.projectx.datareference.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.ParameterType;
import io.cucumber.java.ru.Тогда;

public class ServiceLog {

    private static final String SEARCH_STRING_MARK = "жжж";

    private final ContractsService contracts;
    private final ServiceJournal journal;

    public ServiceLog(ContractsService contracts, ServiceJournal journal) {
        this.contracts = contracts;
        this.journal = journal;
    }

    @Before
    public void startCollecting() {
        journal.start();
    }

    @After
    public void stopCollecting() {
        journal.stop();
    }

    @Тогда("в журнале Дата-референса нет записей уровня ERROR")
    public void noErrors() {
        assertThat(journal.events())
                .as("записи уровня ERROR в журнале")
                .noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    @ParameterType(name = "уровень", value = "ERROR|WARN|INFO|DEBUG|TRACE")
    public Level level(String name) {
        return Level.toLevel(name);
    }

    @Тогда("в журнале Дата-референса есть запись уровня {уровень} с полученным id")
    public void entryWithObtainedId(Level level) {
        entryMentioning(level, contracts.obtainedId());
    }

    @Тогда("в журнале Дата-референса есть запись уровня {уровень} с кодом {string}")
    public void entryWithCode(Level level, String code) {
        entryMentioning(level, code);
    }

    @Тогда("в журнале Дата-референса есть запись уровня {уровень} с X-Request-Id ответа")
    public void entryWithResponseRequestId(Level level) {
        assertThat(contracts.lastResponse().requestIds()).as("заголовок X-Request-Id ответа").hasSize(1);
        entryMentioning(level, contracts.lastResponse().requestIds().getFirst());
    }

    @Тогда("строка query из запроса не попала ни в ответ, ни в журнал Дата-референса")
    public void searchStringNotRepeated() {
        assertThat(contracts.lastResponse().body().toString())
                .as("тело ответа")
                .doesNotContain(SEARCH_STRING_MARK);
        assertThat(journal.mentioning(SEARCH_STRING_MARK))
                .as("записи журнала со строкой поиска")
                .isEmpty();
    }

    @Тогда("номер полиса {string} не попал ни в ответ, ни в журнал Дата-референса")
    public void policyNumberNotInResponseNorJournal(String number) {
        assertThat(contracts.lastResponse().body().toString())
                .as("тело ответа")
                .doesNotContain(number);
        policyNumberNotInJournal(number);
    }

    @Тогда("номер полиса {string} не попал в журнал Дата-референса")
    public void policyNumberNotInJournal(String number) {
        assertThat(journal.mentioning(number))
                .as("записи журнала с номером полиса")
                .isEmpty();
    }

    private void entryMentioning(Level level, String text) {
        assertThat(journal.events())
                .as("записи уровня %s с «%s» в журнале", level, text)
                .anyMatch(event -> event.getLevel() == level && event.getFormattedMessage().contains(text));
    }
}
