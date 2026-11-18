package ru.projectx.datareference.service.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.entity.inbound.OneCStream;

@Component
public class InboundBacklogMetrics implements MeterBinder {

    static final String MESSAGES = "onec.inbound.messages";
    static final String OLDEST_PENDING = "onec.inbound.pending.oldest.age";
    static final String STUCK_TRANSFERS = "onec.price.transfers.stuck";
    static final String STUCK_ASSOCIATION_TRANSFERS = "onec.insured.association.transfers.stuck";
    static final String OVERDUE_PAYLOADS = "onec.retention.overdue.payloads";
    static final String OVERDUE_QUARANTINE_BODIES = "onec.retention.overdue.quarantine.bodies";
    static final String OVERDUE_POLICIES = "policies.retention.overdue.personal.data";
    static final String STALENESS = "onec.inbound.backlog.staleness";

    private static final List<InboundStatus> GAUGED_STATUSES =
            List.of(InboundStatus.NEW, InboundStatus.RETRY, InboundStatus.BUFFERED, InboundStatus.AWAITING_PARENT,
                    InboundStatus.FAILED);

    private static final Logger log = LoggerFactory.getLogger(InboundBacklogMetrics.class);

    private final InboundBacklogReads reads;
    private final Clock clock;
    private final List<Meter> published = new ArrayList<>();
    private final Object publication = new Object();
    private volatile InboundBacklog backlog;
    private @Nullable MeterRegistry registry;

    public InboundBacklogMetrics(InboundBacklogReads reads, Clock clock) {
        this.reads = reads;
        this.clock = clock;
        this.backlog = InboundBacklog.empty(Instant.EPOCH);
    }

    public void refresh() {
        try {
            backlog = reads.read();
        }
        catch (RuntimeException unavailable) {
            log.warn("метрики очереди разбора 1С не обновлены, остаётся снимок от {}: {}",
                    backlog.takenAt(), unavailable.getClass().getName());
        }
        publish();
    }

    public void withdraw() {
        synchronized (publication) {
            for (Meter meter : published) {
                Objects.requireNonNull(registry).remove(meter);
            }
            published.clear();
        }
    }

    @Override
    public void bindTo(MeterRegistry meters) {
        synchronized (publication) {
            registry = meters;
            publish();
        }
    }

    private void publish() {
        synchronized (publication) {
            MeterRegistry target = registry;
            if (target == null || !published.isEmpty()) {
                return;
            }
            register(target);
        }
    }

    private void register(MeterRegistry target) {
        for (OneCStream stream : OneCStream.values()) {
            bindStream(target, stream);
        }
        gauge(target, STUCK_TRANSFERS, "Передачи прайсов, которые ждут своих частей дольше порога тревоги",
                current -> current.stuckTransfers().priceLists());
        gauge(target, STUCK_ASSOCIATION_TRANSFERS,
                "Передачи связей застрахованных, которые ждут своих частей дольше порога тревоги",
                current -> current.stuckTransfers().insuredAssociations());
        gauge(target, OVERDUE_PAYLOADS, "Тела сообщений с ПДн старше срока хранения, ещё не стёртые",
                current -> current.overdue().payloads());
        gauge(target, OVERDUE_QUARANTINE_BODIES, "Тела карантинных сообщений старше срока хранения, ещё не стёртые",
                current -> current.overdue().quarantineBodies());
        gauge(target, OVERDUE_POLICIES,
                "Полисы, у которых срок хранения ФИО, даты рождения и почты истёк, а они ещё не стёрты",
                current -> current.overdue().policies());
        published.add(TimeGauge.builder(STALENESS, this, TimeUnit.SECONDS,
                        metrics -> metrics.secondsSince(metrics.backlog.takenAt()))
                .description("Сколько секунд назад обновлены метрики очереди разбора 1С")
                .register(target));
    }

    private void bindStream(MeterRegistry target, OneCStream stream) {
        for (InboundStatus status : GAUGED_STATUSES) {
            published.add(Gauge.builder(MESSAGES, this, metrics -> metrics.backlog.messages(stream, status))
                    .description("Сырые сообщения 1С по потоку и статусу: ждут разбора (NEW, RETRY), ждут частей "
                            + "передачи (BUFFERED), ждут родителя (AWAITING_PARENT), отказ (FAILED)")
                    .tag("stream", stream.name())
                    .tag("status", status.name())
                    .register(target));
        }
        published.add(TimeGauge.builder(OLDEST_PENDING, this, TimeUnit.SECONDS,
                        metrics -> metrics.oldestPendingAge(stream))
                .description("Сколько секунд ждёт разбора самое давнее сообщение потока (NEW или RETRY); 0 — не ждёт ни одно")
                .tag("stream", stream.name())
                .register(target));
    }

    private void gauge(MeterRegistry target, String name, String description, ToDoubleFunction<InboundBacklog> value) {
        published.add(Gauge.builder(name, this, metrics -> value.applyAsDouble(metrics.backlog))
                .description(description)
                .register(target));
    }

    private double oldestPendingAge(OneCStream stream) {
        return backlog.oldestPending(stream).map(this::secondsSince).orElse(0.0);
    }

    private double secondsSince(Instant moment) {
        return Duration.between(moment, clock.instant()).toMillis() / (double) TimeUnit.SECONDS.toMillis(1);
    }
}
