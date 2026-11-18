package ru.projectx.datareference.service.integration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ru.projectx.datareference.config.OneCQueueProperties;

final class IngestTestMetrics {

    static final OneCQueueProperties QUEUES = new OneCQueueProperties(
            "q-policies", "q-insurance-contracts", "q-contragents", "q-mkb10", "q-lpu-contracts",
            "q-programs", "q-appeal-types", "q-lpu-contacts", "q-price-lists", "q-price-lists-final",
            "q-contragent-services", "q-insured-associations",
            "q-insured-associations-final");

    private IngestTestMetrics() {
    }

    static OneCIngestMetrics on(MeterRegistry registry) {
        return new OneCIngestMetrics(registry, QUEUES);
    }

    static OneCIngestMetrics discarded() {
        return on(new SimpleMeterRegistry());
    }
}
