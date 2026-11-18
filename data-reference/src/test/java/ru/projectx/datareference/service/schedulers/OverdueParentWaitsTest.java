package ru.projectx.datareference.service.schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ru.projectx.datareference.entity.inbound.AwaitedParent;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.entity.inbound.ParentKind;
import ru.projectx.datareference.service.metrics.ParentWaitMetrics;
import ru.projectx.datareference.service.onec.ExpiredParentWait;
import ru.projectx.datareference.service.onec.ParentWaitExpiry;
import ru.projectx.datareference.support.ImportSettings;

class OverdueParentWaitsTest {

    private static final int REPORT_LIMIT = 2;
    private static final UUID CID = UUID.fromString("7d3e9a10-4b2c-4f8e-9a61-0c5b8e2f4d17");
    private static final AwaitedParent SERVICE =
            new AwaitedParent(ParentKind.CONTRAGENT_SERVICE, UUID.fromString("4b7d1f9a-3c6e-4d28-9a05-b8e2c1f7d346"));

    private final ParentWaitExpiry expiry = mock(ParentWaitExpiry.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OverdueParentWaits overdue = new OverdueParentWaits(expiry, new ParentWaitMetrics(registry),
            ImportSettings.defaults().parentWaitReportLimit(REPORT_LIMIT).build());
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void captureLog() {
        logged.start();
        log().addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        log().detachAppender(logged);
    }

    @Test
    void expiredMessagesOfAStream_giveOneSummary_withTheFirstParentsUpToTheLimit() {
        List<AwaitedParent> parents = IntStream.range(0, 3)
                .mapToObj(index -> new AwaitedParent(ParentKind.CONTRAGENT, UUID.randomUUID()))
                .toList();
        when(expiry.expireOverdue()).thenReturn(parents.stream()
                .map(parent -> new ExpiredParentWait(OneCStream.LPU_CONTRACTS, parent, null))
                .toList());

        overdue.rejectAndReport();

        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo("сообщения потока LPU_CONTRACTS не дождались родителя "
                    + "за срок ожидания PT24H: отклонено 3, первые родители: [" + parents.get(0).id() + ", "
                    + parents.get(1).id() + "]");
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertThat(expired("LPU_CONTRACTS")).isEqualTo(3);
    }

    @Test
    void parentOfSeveralMessages_isListedOnce() {
        AwaitedParent parent = new AwaitedParent(ParentKind.CONTRAGENT, UUID.randomUUID());
        ExpiredParentWait contacts = new ExpiredParentWait(OneCStream.LPU_CONTACTS, parent, null);
        when(expiry.expireOverdue()).thenReturn(List.of(contacts, contacts));

        overdue.rejectAndReport();

        assertThat(logged.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("сообщения потока LPU_CONTACTS не дождались родителя за срок ожидания PT24H: отклонено 2, "
                        + "первые родители: [" + parent.id() + "]");
    }

    @Test
    void streamsAreReportedSeparately() {
        when(expiry.expireOverdue()).thenReturn(List.of(
                new ExpiredParentWait(OneCStream.LPU_CONTRACTS, new AwaitedParent(ParentKind.CONTRAGENT, UUID.randomUUID()), null),
                new ExpiredParentWait(OneCStream.CONTRAGENT_SERVICES,
                        new AwaitedParent(ParentKind.LPU_CONTRACT, UUID.randomUUID()), null)));

        overdue.rejectAndReport();

        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                .satisfiesExactly(
                        first -> assertThat(first).startsWith("сообщения потока LPU_CONTRACTS ").contains("отклонено 1,"),
                        second -> assertThat(second).startsWith("сообщения потока CONTRAGENT_SERVICES ")
                                .contains("отклонено 1,"));
        assertThat(expired("LPU_CONTRACTS")).isEqualTo(1);
        assertThat(expired("CONTRAGENT_SERVICES")).isEqualTo(1);
    }

    @Test
    void expiredTransfer_givesOneRecordWithItsIdentifier_andCountsEveryPart() {
        ExpiredParentWait part = new ExpiredParentWait(OneCStream.PRICE_LISTS, SERVICE, CID);
        when(expiry.expireOverdue()).thenReturn(List.of(part, part));

        overdue.rejectAndReport();

        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo("передача прайса " + CID + " не дождалась услуги "
                    + SERVICE.id() + " за срок ожидания PT24H: отклонено сообщений 2");
        });
        assertThat(expired("PRICE_LISTS")).isEqualTo(2);
    }

    @Test
    void expiredAssociationTransfer_namesTheAwaitedPolicy() {
        AwaitedParent policy = new AwaitedParent(ParentKind.POLICY, UUID.fromString("11111111-2222-4333-8444-555555555506"));
        ExpiredParentWait part = new ExpiredParentWait(OneCStream.INSURED_ASSOCIATIONS, policy, CID);
        when(expiry.expireOverdue()).thenReturn(List.of(part, part));

        overdue.rejectAndReport();

        assertThat(logged.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("передача связей застрахованных " + CID + " не дождалась полиса " + policy.id()
                        + " за срок ожидания PT24H: отклонено сообщений 2");
        assertThat(expired("INSURED_ASSOCIATIONS")).isEqualTo(2);
    }

    @Test
    void nothingExpired_writesNothing() {
        overdue.rejectAndReport();

        assertThat(logged.list).isEmpty();
        assertThat(registry.find(ParentWaitMetrics.EXPIRED).counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    private double expired(String stream) {
        return registry.get(ParentWaitMetrics.EXPIRED).tag("stream", stream).counter().count();
    }

    private static Logger log() {
        return (Logger) LoggerFactory.getLogger(OverdueParentWaits.class);
    }
}
