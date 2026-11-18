package ru.projectx.datareference.acceptance;

import io.cucumber.spring.ScenarioScope;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.entity.inbound.OneCStream;

@Component
@ScenarioScope(proxyMode = ScopedProxyMode.NO)
public class OneCInbox {

    private static final String RECEIVE = """
            insert into inbound_message (message_id, stream, source_queue, payload, status, attempt, received_at)
            values (:messageId, :stream, :queue, cast(:payload as jsonb), 'NEW', 0,
                    greatest(clock_timestamp(), (select max(received_at) + interval '1 millisecond' from inbound_message)))
            """;
    private static final String STATUSES = "select status from inbound_message order by received_at";
    private static final String TRANSFER_STATUSES = """
            select status from inbound_message where payload -> 'data' ->> 'cid' = :cid order by received_at
            """;
    private static final String TRANSFER_MESSAGE_IDS = """
            select message_id from inbound_message where payload -> 'data' ->> 'cid' = :cid order by received_at
            """;
    private static final String MESSAGE_IDS = "select message_id from inbound_message order by received_at";
    private static final String STORED = "select exists(select 1 from inbound_message where message_id = :messageId)";
    private static final String ROW = """
            select status, attempt, last_error, cast(payload as text) as payload
            from inbound_message where message_id = :messageId
            """;
    private static final String RETRY_DUE = """
            update inbound_message set next_retry_at = cast(:now as timestamptz) - interval '1 second' where status = 'RETRY'
            """;
    private static final String RETURN_TO_PARSING = """
            update inbound_message set status = 'NEW', next_retry_at = null, last_error = null, processed_at = null
            where message_id = :messageId
            """;
    private static final String RETURN_TRANSFER_TO_PARSING = """
            update inbound_message set status = 'NEW', next_retry_at = null, last_error = null, processed_at = null
            where correlation_id = :cid
            """;
    private static final String AGE_FROM_LAST = """
            update inbound_message
               set received_at = received_at - ((select last.received_at from inbound_message last
                                                  where last.message_id = :messageId)
                                                - (clock_timestamp() - cast(:age as interval)))
            """;

    private static final String AGE_ALL = """
            update inbound_message set received_at = received_at - cast(:age as interval)
            """;
    private static final String EXPIRED_PARENTS = """
            select awaited_parent_id from inbound_message
            where stream = :stream and status = 'FAILED' and parent_wait_since is not null
            order by received_at
            """;
    private static final String AGE_PARENT_WAITS = """
            update inbound_message set parent_wait_since = parent_wait_since - cast(:age as interval)
            where status = 'AWAITING_PARENT'
            """;

    private final JdbcClient jdbc;
    private final StandClock clock;
    private final List<String> expectedStatuses = new ArrayList<>();
    private final List<OneCMessage> received = new ArrayList<>();

    public OneCInbox(JdbcClient jdbc, StandClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void receive(OneCMessage message) {
        expect(message);
        jdbc.sql(RECEIVE)
                .param("messageId", message.messageId())
                .param("stream", message.stream().name())
                .param("queue", message.queue())
                .param("payload", message.envelope())
                .update();
    }

    public void expect(OneCMessage message) {
        if (received.stream().noneMatch(known -> known.messageId().equals(message.messageId()))) {
            expectedStatuses.add(message.expected().name());
        }
        received.add(message);
    }

    public void settle(UUID messageId, InboundStatus outcome) {
        List<UUID> firstReceipts = received.stream().map(OneCMessage::messageId).distinct().toList();
        expectedStatuses.set(firstReceipts.indexOf(messageId), outcome.name());
    }

    public List<UUID> messageIdsInOrder() {
        return jdbc.sql(MESSAGE_IDS).query(UUID.class).list();
    }

    public boolean stored(UUID messageId) {
        return jdbc.sql(STORED).param("messageId", messageId).query(Boolean.class).single();
    }

    public InboundRow last() {
        return row(lastMessageId());
    }

    public UUID lastMessageId() {
        return received.getLast().messageId();
    }

    public InboundRow row(UUID messageId) {
        return jdbc.sql(ROW)
                .param("messageId", messageId)
                .query((row, number) -> new InboundRow(row.getString("status"), row.getInt("attempt"),
                        row.getString("last_error"), row.getString("payload")))
                .single();
    }

    public InboundRow first() {
        return row(received.getFirst().messageId());
    }

    public void retryDueNow() {
        if (jdbc.sql(RETRY_DUE).param("now", clock.moment()).update() == 0) {
            throw new IllegalStateException("в сырье нет сообщений, отложенных до повтора");
        }
    }

    public void returnLastToParsing() {
        int returned = jdbc.sql(RETURN_TO_PARSING).param("messageId", received.getLast().messageId()).update();
        if (returned != 1) {
            throw new IllegalStateException("в сырье нет строки последнего сообщения");
        }
    }

    public OneCMessage asNewDelivery(OneCMessage message) {
        long sent = received.stream().filter(known -> known.messageId().equals(message.messageId())).count();
        if (sent == 0) {
            return message;
        }
        UUID resent = UUID.nameUUIDFromBytes((message.messageId() + "#" + sent).getBytes(StandardCharsets.UTF_8));
        return new OneCMessage(message.stream(), message.queue(), message.data(), message.expected(), resent);
    }

    public List<OneCMessage> receivedOf(OneCStream stream) {
        return received.stream().filter(message -> message.stream() == stream).toList();
    }

    public void returnTransferToParsing(UUID cid) {
        if (jdbc.sql(RETURN_TRANSFER_TO_PARSING).param("cid", cid).update() == 0) {
            throw new IllegalStateException("в сырье нет частей передачи " + cid);
        }
    }

    public OneCMessage lastMessage() {
        return received.getLast();
    }

    public OneCMessage lastOf(OneCStream stream) {
        return received.reversed().stream()
                .filter(message -> message.stream() == stream)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("сценарий не присылал сообщений потока " + stream));
    }

    public void ageFromLast(Duration age) {
        UUID last = received.getLast().messageId();
        if (!stored(last)) {
            throw new IllegalStateException("в сырье нет строки последнего сообщения");
        }
        jdbc.sql(AGE_FROM_LAST).param("age", age.toSeconds() + " seconds").param("messageId", last).update();
    }

    public void ageAll(Duration age) {
        if (jdbc.sql(AGE_ALL).param("age", age.toSeconds() + " seconds").update() == 0) {
            throw new IllegalStateException("в сырье нет сообщений");
        }
    }

    public void ageParentWaits(Duration age) {
        if (jdbc.sql(AGE_PARENT_WAITS).param("age", age.toSeconds() + " seconds").update() == 0) {
            throw new IllegalStateException("в сырье нет сообщений, которые ждут родителя");
        }
    }

    public List<UUID> expiredParentsInOrder(OneCStream stream) {
        return jdbc.sql(EXPIRED_PARENTS).param("stream", stream.name()).query(UUID.class).list().stream()
                .distinct()
                .toList();
    }

    public String lastEnvelope() {
        return received.getLast().envelope();
    }

    public String envelopeOf(UUID messageId) {
        return received.reversed().stream()
                .filter(message -> message.messageId().equals(messageId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("сценарий не присылал сообщение " + messageId))
                .envelope();
    }

    public List<String> expectedStatuses() {
        List<OneCMessage> firstReceipts = received.stream()
                .collect(Collectors.toMap(OneCMessage::messageId, message -> message, (first, repeated) -> first,
                        LinkedHashMap::new))
                .values().stream()
                .toList();
        return IntStream.range(0, expectedStatuses.size())
                .mapToObj(index -> OneCPriceTransferMessages.batchWithoutItsFinal(firstReceipts.get(index), received)
                        ? InboundStatus.BUFFERED.name()
                        : expectedStatuses.get(index))
                .toList();
    }

    public List<String> transferStatuses(UUID cid) {
        return jdbc.sql(TRANSFER_STATUSES).param("cid", cid.toString()).query(String.class).list();
    }

    public List<UUID> transferMessageIds(UUID cid) {
        return jdbc.sql(TRANSFER_MESSAGE_IDS).param("cid", cid.toString()).query(UUID.class).list();
    }

    public List<String> statuses() {
        return jdbc.sql(STATUSES).query(String.class).list();
    }
}
