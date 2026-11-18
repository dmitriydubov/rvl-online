package ru.projectx.datareference.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.entity.inbound.OneCStream;

@ExtendWith(MockitoExtension.class)
class InboundBacklogMetricsTest {

    private static final Instant STARTED = Instant.parse("2026-10-05T12:00:00Z");
    private static final Instant REFRESHED = STARTED.plusSeconds(30);

    @Mock
    private InboundBacklogReads reads;

    private final MovableClock clock = new MovableClock(STARTED);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private InboundBacklogMetrics metrics;

    @BeforeEach
    void bind() {
        logged.start();
        metricsLog().addAppender(logged);
        metrics = new InboundBacklogMetrics(reads, clock);
        metrics.bindTo(registry);
    }

    @AfterEach
    void releaseLog() {
        metricsLog().detachAppender(logged);
    }

    @Test
    void beforeFirstRefresh_everyStreamAndStatusIsZero() {
        assertThat(registry.find(InboundBacklogMetrics.MESSAGES).gauges())
                .hasSize(OneCStream.values().length * 5)
                .allSatisfy(gauge -> assertThat(gauge.value()).isZero());
        assertThat(registry.find(InboundBacklogMetrics.MESSAGES).gauges())
                .extracting(gauge -> gauge.getId().getTag("status"))
                .containsOnly("NEW", "RETRY", "BUFFERED", "AWAITING_PARENT", "FAILED");
        assertThat(registry.find(InboundBacklogMetrics.OLDEST_PENDING).timeGauges())
                .hasSize(OneCStream.values().length)
                .allSatisfy(gauge -> assertThat(gauge.value(TimeUnit.SECONDS)).isZero());
        assertThat(gauge(InboundBacklogMetrics.STUCK_TRANSFERS).value()).isZero();
    }

    @Test
    void withdraw_removesEverySnapshotSeries() {
        metrics.withdraw();

        assertThat(registry.find(InboundBacklogMetrics.MESSAGES).gauges()).isEmpty();
        assertThat(registry.find(InboundBacklogMetrics.OLDEST_PENDING).timeGauges()).isEmpty();
        assertThat(registry.find(InboundBacklogMetrics.STALENESS).timeGauges()).isEmpty();
        assertThat(registry.find(InboundBacklogMetrics.STUCK_TRANSFERS).gauges()).isEmpty();
        assertThat(registry.find(InboundBacklogMetrics.OVERDUE_PAYLOADS).gauges()).isEmpty();
        assertThat(registry.find(InboundBacklogMetrics.OVERDUE_QUARANTINE_BODIES).gauges()).isEmpty();
        assertThat(registry.find(InboundBacklogMetrics.OVERDUE_POLICIES).gauges()).isEmpty();
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void refreshAfterWithdraw_publishesTheFreshSnapshotAgainOnce() {
        metrics.withdraw();
        when(reads.read()).thenReturn(new InboundBacklog(List.of(
                new InboundBacklog.StatusCount(OneCStream.POLICIES, InboundStatus.FAILED, 3, STARTED)),
                new InboundBacklog.StuckTransfers(0, 0), new InboundBacklog.Overdue(0, 0, 0), REFRESHED));

        metrics.refresh();
        metrics.refresh();

        assertThat(registry.find(InboundBacklogMetrics.MESSAGES).gauges())
                .hasSize(OneCStream.values().length * 5);
        assertThat(registry.get(InboundBacklogMetrics.MESSAGES).tag("stream", "POLICIES").tag("status", "FAILED")
                .gauge().value()).isEqualTo(3.0);
        assertThat(registry.find(InboundBacklogMetrics.STALENESS).timeGauges()).hasSize(1);
    }

    @Test
    void refreshWhileSeriesArePublished_registersNoSeriesAgain() {
        when(reads.read()).thenReturn(InboundBacklog.empty(REFRESHED));
        registry.clear();

        metrics.refresh();

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void withdrawBeforeBinding_changesNothing() {
        InboundBacklogMetrics unbound = new InboundBacklogMetrics(reads, clock);

        unbound.withdraw();

        assertThat(registry.find(InboundBacklogMetrics.STALENESS).timeGauges()).hasSize(1);
    }

    @Test
    void refreshBeforeBinding_keepsTheSnapshotForTheRegistryBoundLater() {
        InboundBacklogMetrics unbound = new InboundBacklogMetrics(reads, clock);
        SimpleMeterRegistry later = new SimpleMeterRegistry();
        when(reads.read()).thenReturn(new InboundBacklog(List.of(
                new InboundBacklog.StatusCount(OneCStream.POLICIES, InboundStatus.FAILED, 7, STARTED)),
                new InboundBacklog.StuckTransfers(0, 0), new InboundBacklog.Overdue(0, 0, 0), REFRESHED));

        unbound.refresh();
        unbound.bindTo(later);

        assertThat(later.get(InboundBacklogMetrics.MESSAGES).tag("stream", "POLICIES").tag("status", "FAILED")
                .gauge().value()).isEqualTo(7.0);
        assertThat(logged.list).isEmpty();
    }

    @Test
    void beforeFirstRefresh_stalenessCountsFromEpoch() {
        assertThat(timeGauge(InboundBacklogMetrics.STALENESS).value(TimeUnit.SECONDS))
                .isEqualTo((double) STARTED.getEpochSecond());
    }

    @Test
    void refresh_publishesCountsOfTheSnapshot() {
        when(reads.read()).thenReturn(new InboundBacklog(List.of(
                new InboundBacklog.StatusCount(OneCStream.POLICIES, InboundStatus.FAILED, 3, STARTED),
                new InboundBacklog.StatusCount(OneCStream.PRICE_LISTS, InboundStatus.BUFFERED, 4, STARTED)),
                new InboundBacklog.StuckTransfers(2, 4), new InboundBacklog.Overdue(11, 5, 6), REFRESHED));

        metrics.refresh();

        assertThat(messages(OneCStream.POLICIES, InboundStatus.FAILED)).isEqualTo(3.0);
        assertThat(messages(OneCStream.PRICE_LISTS, InboundStatus.BUFFERED)).isEqualTo(4.0);
        assertThat(messages(OneCStream.POLICIES, InboundStatus.NEW)).isZero();
        assertThat(gauge(InboundBacklogMetrics.STUCK_TRANSFERS).value()).isEqualTo(2.0);
        assertThat(gauge(InboundBacklogMetrics.STUCK_ASSOCIATION_TRANSFERS).value()).isEqualTo(4.0);
        assertThat(gauge(InboundBacklogMetrics.OVERDUE_PAYLOADS).value()).isEqualTo(11.0);
        assertThat(gauge(InboundBacklogMetrics.OVERDUE_QUARANTINE_BODIES).value()).isEqualTo(5.0);
        assertThat(gauge(InboundBacklogMetrics.OVERDUE_POLICIES).value()).isEqualTo(6.0);
    }

    @Test
    void oldestPendingAge_growsWithTheClock() {
        when(reads.read()).thenReturn(new InboundBacklog(List.of(
                new InboundBacklog.StatusCount(OneCStream.MKB10, InboundStatus.RETRY, 1, STARTED)),
                new InboundBacklog.StuckTransfers(0, 0), new InboundBacklog.Overdue(0, 0, 0), REFRESHED));
        metrics.refresh();

        clock.moveTo(STARTED.plus(Duration.ofMinutes(5)).plusMillis(500));

        assertThat(oldestPending(OneCStream.MKB10).value(TimeUnit.SECONDS)).isEqualTo(300.5);
        assertThat(oldestPending(OneCStream.POLICIES).value(TimeUnit.SECONDS)).isZero();
    }

    @Test
    void staleness_isTheAgeOfTheSnapshot() {
        when(reads.read()).thenReturn(InboundBacklog.empty(REFRESHED));
        metrics.refresh();

        clock.moveTo(REFRESHED.plusSeconds(45));

        assertThat(timeGauge(InboundBacklogMetrics.STALENESS).value(TimeUnit.SECONDS)).isEqualTo(45.0);
    }

    @Test
    void failedRefresh_keepsPreviousSnapshotAndLogsFailureType() {
        when(reads.read()).thenReturn(new InboundBacklog(List.of(
                new InboundBacklog.StatusCount(OneCStream.MKB10, InboundStatus.NEW, 6, STARTED)),
                new InboundBacklog.StuckTransfers(0, 0), new InboundBacklog.Overdue(0, 0, 0), REFRESHED));
        metrics.refresh();
        when(reads.read()).thenThrow(new DataAccessResourceFailureException("полис Иванова И.И."));

        metrics.refresh();
        clock.moveTo(REFRESHED.plusSeconds(90));

        assertThat(messages(OneCStream.MKB10, InboundStatus.NEW)).isEqualTo(6.0);
        assertThat(timeGauge(InboundBacklogMetrics.STALENESS).value(TimeUnit.SECONDS)).isEqualTo(90.0);
        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains(DataAccessResourceFailureException.class.getName())
                    .contains(REFRESHED.toString())
                    .doesNotContain("Иванова");
        });
    }

    @Test
    void everyMeter_isDescribed() {
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getDescription()).isNotBlank());
    }

    private double messages(OneCStream stream, InboundStatus status) {
        return registry.get(InboundBacklogMetrics.MESSAGES)
                .tag("stream", stream.name()).tag("status", status.name()).gauge().value();
    }

    private TimeGauge oldestPending(OneCStream stream) {
        return registry.get(InboundBacklogMetrics.OLDEST_PENDING).tag("stream", stream.name()).timeGauge();
    }

    private Gauge gauge(String name) {
        return registry.get(name).gauge();
    }

    private TimeGauge timeGauge(String name) {
        return registry.get(name).timeGauge();
    }

    private static Logger metricsLog() {
        return (Logger) LoggerFactory.getLogger(InboundBacklogMetrics.class);
    }

    private static final class MovableClock extends Clock {

        private volatile Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void moveTo(Instant moment) {
            now = moment;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
