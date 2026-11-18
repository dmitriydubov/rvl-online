package ru.projectx.datareference.service.schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.projectx.datareference.config.ImportProperties;
import ru.projectx.datareference.entity.inbound.OneCStream;
import ru.projectx.datareference.entity.inbound.ParentKind;
import ru.projectx.datareference.service.metrics.ParentWaitMetrics;
import ru.projectx.datareference.service.onec.ExpiredParentWait;
import ru.projectx.datareference.service.onec.ParentWaitExpiry;

@Component
public class OverdueParentWaits {

    private static final Logger log = LoggerFactory.getLogger(OverdueParentWaits.class);
    private static final Map<OneCStream, String> TRANSFER_TITLES = Map.of(
            OneCStream.PRICE_LISTS, "прайса",
            OneCStream.INSURED_ASSOCIATIONS, "связей застрахованных");
    private static final Map<ParentKind, String> AWAITED_TITLES = Map.of(
            ParentKind.CONTRAGENT_SERVICE, "услуги",
            ParentKind.POLICY, "полиса");

    private final ParentWaitExpiry expiry;
    private final ParentWaitMetrics metrics;
    private final ImportProperties properties;

    public OverdueParentWaits(ParentWaitExpiry expiry, ParentWaitMetrics metrics, ImportProperties properties) {
        this.expiry = expiry;
        this.metrics = metrics;
        this.properties = properties;
    }

    public void rejectAndReport() {
        report(expiry.expireOverdue());
    }

    private void report(List<ExpiredParentWait> expired) {
        for (ExpiredParentWait message : expired) {
            metrics.expired(message.stream());
        }
        reportTransfers(expired);
        reportStreams(expired);
    }

    private void reportTransfers(List<ExpiredParentWait> expired) {
        Map<UUID, List<ExpiredParentWait>> transfers = expired.stream()
                .filter(message -> message.transfer() != null)
                .collect(Collectors.groupingBy(message -> Objects.requireNonNull(message.transfer()),
                        LinkedHashMap::new, Collectors.toList()));
        transfers.forEach((transfer, parts) -> reportTransfer(transfer, parts.getFirst(), parts.size()));
    }

    private void reportTransfer(UUID transfer, ExpiredParentWait part, int parts) {
        log.error("передача {} {} не дождалась {} {} за срок ожидания {}: отклонено сообщений {}",
                TRANSFER_TITLES.get(part.stream()), transfer, AWAITED_TITLES.get(part.parent().kind()),
                part.parent().id(), properties.parentWaitTimeout(), parts);
    }

    private void reportStreams(List<ExpiredParentWait> expired) {
        Map<OneCStream, List<ExpiredParentWait>> streams = expired.stream()
                .filter(message -> message.transfer() == null)
                .collect(Collectors.groupingBy(ExpiredParentWait::stream, LinkedHashMap::new, Collectors.toList()));
        streams.forEach((stream, messages) -> log.error(
                "сообщения потока {} не дождались родителя за срок ожидания {}: отклонено {}, первые родители: {}",
                stream, properties.parentWaitTimeout(), messages.size(), firstParents(messages)));
    }

    private List<UUID> firstParents(List<ExpiredParentWait> messages) {
        return messages.stream()
                .map(message -> message.parent().id())
                .distinct()
                .limit(properties.parentWaitReportLimit())
                .toList();
    }
}
