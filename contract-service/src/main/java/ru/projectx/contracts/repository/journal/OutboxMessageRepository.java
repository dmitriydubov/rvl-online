package ru.projectx.contracts.repository.journal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.projectx.contracts.entity.outbox.OutboxMessage;
import ru.projectx.contracts.entity.outbox.OutboxStatus;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {

    default List<OutboxMessage> findDueOf(Collection<String> eventTypes, Instant now, Limit limit) {
        return findDueIn(OutboxStatus.NEW, eventTypes, now, limit);
    }

    default List<OutboxMessage> findDueExcept(Collection<String> eventTypes, Instant now, Limit limit) {
        return findDueNotIn(OutboxStatus.NEW, eventTypes, now, limit);
    }

    @Query("""
            select m from OutboxMessage m
             where m.status = :status
               and m.eventType in :eventTypes
               and (m.nextAttemptAt is null or m.nextAttemptAt <= :now)
             order by m.createdAt, m.id
            """)
    List<OutboxMessage> findDueIn(@Param("status") OutboxStatus status,
            @Param("eventTypes") Collection<String> eventTypes, @Param("now") Instant now, Limit limit);

    @Query("""
            select m from OutboxMessage m
             where m.status = :status
               and m.eventType not in :eventTypes
               and (m.nextAttemptAt is null or m.nextAttemptAt <= :now)
             order by m.createdAt, m.id
            """)
    List<OutboxMessage> findDueNotIn(@Param("status") OutboxStatus status,
            @Param("eventTypes") Collection<String> eventTypes, @Param("now") Instant now, Limit limit);

    @Modifying
    @Query(value = """
            update outbox_messages
               set payload = null
             where id in (select id from outbox_messages
                           where payload is not null
                             and status in ('PUBLISHED', 'FAILED')
                             and created_at < :cutoff
                           limit :batch)
            """, nativeQuery = true)
    int eraseFinishedPayloadsCreatedBefore(@Param("cutoff") Instant cutoff, @Param("batch") int batch);

    boolean existsByCorrelationIdAndAggregateTypeAndAggregateIdInAndEventType(UUID correlationId,
            String aggregateType, Collection<UUID> aggregateIds, String eventType);
}
