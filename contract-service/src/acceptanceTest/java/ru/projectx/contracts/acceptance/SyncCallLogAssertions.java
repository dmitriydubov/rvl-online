package ru.projectx.contracts.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Duration;
import java.util.ConcurrentModificationException;
import org.awaitility.Awaitility;

final class SyncCallLogAssertions {

    private static final Duration CALL_LOG_PATIENCE = Duration.ofSeconds(2);
    private static final Duration CALL_LOG_POLL = Duration.ofMillis(10);

    private SyncCallLogAssertions() {
    }

    static void assertLogged(ServiceJournal journal, String method, String requestId, int status) {
        Awaitility.await()
                .atMost(CALL_LOG_PATIENCE)
                .pollInterval(CALL_LOG_POLL)
                .ignoreExceptionsInstanceOf(ConcurrentModificationException.class)
                .untilAsserted(() -> assertThat(journal.events())
                        .as("записи журнала о вызове %s с requestId %s", method, requestId)
                        .filteredOn(event -> requestId.equals(event.getMDCPropertyMap().get("requestId")))
                        .extracting(ILoggingEvent::getFormattedMessage)
                        .anyMatch(message -> message.startsWith("синхронный вызов " + method + " ")
                                && message.endsWith(" завершён с кодом " + status)));
    }
}
