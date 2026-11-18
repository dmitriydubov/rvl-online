package ru.projectx.datareference.service.schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.repository.database.DatabaseClockRepository;
import ru.projectx.datareference.repository.inbound.BufferedTransfer;
import ru.projectx.datareference.service.onec.SupersededTransferClosure;
import ru.projectx.datareference.support.ImportSettings;

@ExtendWith(MockitoExtension.class)
class StuckTransferAlertTest {

    private static final Instant DATABASE_NOW = Instant.parse("2026-10-20T08:30:00.123456Z");
    private static final Duration THRESHOLD = Duration.ofMinutes(90);
    private static final int REPORT_LIMIT = 3;
    private static final UUID FIRST_TRANSFER = UUID.fromString("c1d2e3f4-0000-4000-8000-000000000001");
    private static final UUID SECOND_TRANSFER = UUID.fromString("c1d2e3f4-0000-4000-8000-000000000002");
    private static final UUID THIRD_TRANSFER = UUID.fromString("c1d2e3f4-0000-4000-8000-000000000003");
    private static final UUID FOURTH_TRANSFER = UUID.fromString("c1d2e3f4-0000-4000-8000-000000000004");

    @Mock
    private AgedInboundMessages agedMessages;

    @Mock
    private SupersededTransferClosure closure;

    @Mock
    private DatabaseClockRepository databaseClock;

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void captureLog() {
        logged.start();
        alertLog().addAppender(logged);
    }

    @AfterEach
    void releaseLog() {
        alertLog().detachAppender(logged);
        logged.stop();
    }

    @Test
    void stuckTransfers_areReportedWithAgeAndPartsInOneWarning() {
        when(agedMessages.transfersBufferedLongerThan(OneCStream.PRICE_LISTS, THRESHOLD, REPORT_LIMIT)).thenReturn(List.of(
                transfer(FIRST_TRANSFER, Duration.ofHours(5), 12), transfer(SECOND_TRANSFER, Duration.ofHours(2), 1)));

        alert().reportStuckTransfers();

        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo("передачи прайсов не собрались за PT1H30M: "
                    + FIRST_TRANSFER + " (ждёт PT5H, частей 12), " + SECOND_TRANSFER + " (ждёт PT2H, частей 1). "
                    + "Прайс по их услугам остался прежним; недостающие сообщения может прислать только источник");
        });
    }

    @Test
    void stuckAssociationTransfers_areReportedInTheirOwnWarning() {
        when(agedMessages.transfersBufferedLongerThan(OneCStream.PRICE_LISTS, THRESHOLD, REPORT_LIMIT))
                .thenReturn(List.of());
        when(agedMessages.transfersBufferedLongerThan(OneCStream.INSURED_ASSOCIATIONS, THRESHOLD, REPORT_LIMIT))
                .thenReturn(List.of(transfer(FIRST_TRANSFER, Duration.ofHours(3), 1)));

        alert().reportStuckTransfers();

        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo("передачи связей застрахованных не собрались за PT1H30M: "
                    + FIRST_TRANSFER + " (ждёт PT3H, частей 1). "
                    + "Связи по их отбору остались прежними; недостающие сообщения может прислать только источник");
        });
    }

    @Test
    void reportLimitReached_warningSaysOthersMayExist() {
        when(agedMessages.transfersBufferedLongerThan(OneCStream.PRICE_LISTS, THRESHOLD, REPORT_LIMIT)).thenReturn(List.of(
                transfer(FIRST_TRANSFER, Duration.ofHours(5), 2), transfer(SECOND_TRANSFER, Duration.ofHours(4), 2),
                transfer(THIRD_TRANSFER, Duration.ofHours(3), 2)));

        alert().reportStuckTransfers();

        assertThat(logged.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .endsWith("только источник. Названы 3 самых давних, зависших может быть больше");
    }

    @Test
    void noStuckTransfers_nothingIsLogged() {
        when(agedMessages.transfersBufferedLongerThan(OneCStream.PRICE_LISTS, THRESHOLD, REPORT_LIMIT)).thenReturn(List.of());

        alert().reportStuckTransfers();

        assertThat(logged.list).isEmpty();
    }

    @Test
    void supersededTransfer_isClosed_andOnlyTheRestAreReported() {
        when(agedMessages.allTransfersBufferedLongerThan(OneCStream.PRICE_LISTS, THRESHOLD)).thenReturn(
                List.of(transfer(FIRST_TRANSFER, Duration.ofHours(5), 2), transfer(SECOND_TRANSFER, Duration.ofHours(2), 1)));
        when(agedMessages.transfersBufferedLongerThan(OneCStream.PRICE_LISTS, THRESHOLD, REPORT_LIMIT))
                .thenReturn(List.of(transfer(SECOND_TRANSFER, Duration.ofHours(2), 1)));
        when(closure.closeIfSuperseded(FIRST_TRANSFER)).thenReturn(true);

        alert().reportStuckTransfers();

        verify(closure).closeIfSuperseded(FIRST_TRANSFER);
        verify(closure).closeIfSuperseded(SECOND_TRANSFER);
        assertThat(logged.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .startsWith("передачи прайсов не собрались за PT1H30M: " + SECOND_TRANSFER + " (ждёт PT2H, частей 1).");
    }

    @Test
    void transfersBeyondReportLimit_areClosedToo() {
        List<BufferedTransfer> beyondLimit = List.of(transfer(FIRST_TRANSFER, Duration.ofHours(9), 1),
                transfer(SECOND_TRANSFER, Duration.ofHours(8), 1), transfer(THIRD_TRANSFER, Duration.ofHours(7), 1),
                transfer(FOURTH_TRANSFER, Duration.ofHours(6), 1));
        when(agedMessages.allTransfersBufferedLongerThan(OneCStream.PRICE_LISTS, THRESHOLD)).thenReturn(beyondLimit);
        when(agedMessages.transfersBufferedLongerThan(OneCStream.PRICE_LISTS, THRESHOLD, REPORT_LIMIT)).thenReturn(beyondLimit.subList(0, 3));

        alert().reportStuckTransfers();

        verify(closure).closeIfSuperseded(FOURTH_TRANSFER);
    }

    @Test
    void allStuckTransfersClosed_nothingIsReported() {
        when(agedMessages.allTransfersBufferedLongerThan(OneCStream.PRICE_LISTS, THRESHOLD))
                .thenReturn(List.of(transfer(FIRST_TRANSFER, Duration.ofHours(5), 2)));
        when(agedMessages.transfersBufferedLongerThan(OneCStream.PRICE_LISTS, THRESHOLD, REPORT_LIMIT)).thenReturn(List.of());
        when(closure.closeIfSuperseded(FIRST_TRANSFER)).thenReturn(true);

        alert().reportStuckTransfers();

        assertThat(logged.list).isEmpty();
    }

    @Test
    void databaseFailureWhileClosing_isLogged_andTransferIsStillReported() {
        List<BufferedTransfer> stuck = List.of(
                transfer(FIRST_TRANSFER, Duration.ofHours(5), 2), transfer(SECOND_TRANSFER, Duration.ofHours(2), 1));
        when(agedMessages.allTransfersBufferedLongerThan(OneCStream.PRICE_LISTS, THRESHOLD)).thenReturn(stuck);
        when(agedMessages.transfersBufferedLongerThan(OneCStream.PRICE_LISTS, THRESHOLD, REPORT_LIMIT)).thenReturn(stuck);
        when(closure.closeIfSuperseded(FIRST_TRANSFER)).thenThrow(new CannotAcquireLockException("занято",
                new SQLException("lock timeout", "55P03")));

        alert().reportStuckTransfers();

        verify(closure).closeIfSuperseded(SECOND_TRANSFER);
        assertThat(logged.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.WARN);
        assertThat(logged.list.getFirst().getFormattedMessage()).isEqualTo("передачу прайса " + FIRST_TRANSFER
                + " не удалось проверить на закрытие: CannotAcquireLockException, SQLSTATE 55P03; "
                + "следующая проверка попробует снова");
        assertThat(logged.list.getFirst().getThrowableProxy()).isNull();
        assertThat(logged.list.getLast().getFormattedMessage()).contains(FIRST_TRANSFER.toString());
    }

    private StuckTransferAlert alert() {
        lenient().when(databaseClock.currentMoment()).thenReturn(DATABASE_NOW);
        return new StuckTransferAlert(agedMessages, closure,
                ImportSettings.defaults().stuckTransferThreshold(THRESHOLD).stuckTransferReportLimit(REPORT_LIMIT)
                        .build(),
                databaseClock);
    }

    private static BufferedTransfer transfer(UUID correlationId, Duration waiting, long parts) {
        return new BufferedTransfer() {

            @Override
            public UUID getCorrelationId() {
                return correlationId;
            }

            @Override
            public Instant getFirstPartReceivedAt() {
                return DATABASE_NOW.minus(waiting);
            }

            @Override
            public long getParts() {
                return parts;
            }
        };
    }

    private static Logger alertLog() {
        return (Logger) LoggerFactory.getLogger(StuckTransferAlert.class);
    }
}
