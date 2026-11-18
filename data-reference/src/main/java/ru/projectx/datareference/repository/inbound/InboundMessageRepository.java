package ru.projectx.datareference.repository.inbound;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.InboundStatus;
import ru.projectx.datareference.entity.inbound.OneCStream;

@Repository
public interface InboundMessageRepository extends JpaRepository<InboundMessage, UUID> {

    @Query("""
            select message
              from InboundMessage message
             where (message.status = NEW
                    or (message.status = RETRY and message.nextRetryAt <= :now)
                    or (message.status = AWAITING_PARENT
                        and (message.correlationId is null or message.batchCount is not null)
                        and (exists (select lpu.id
                                       from Lpu lpu
                                      where message.awaitedParentKind = CONTRAGENT
                                        and lpu.externalId = message.awaitedParentId)
                             or exists (select contract.id
                                          from LpuContract contract
                                         where message.awaitedParentKind = LPU_CONTRACT
                                           and contract.externalId = message.awaitedParentId)
                             or exists (select service.id
                                          from ContragentService service
                                         where message.awaitedParentKind = CONTRAGENT_SERVICE
                                           and service.externalId = message.awaitedParentId)
                             or exists (select stranger.externalId
                                          from ForeignReference stranger
                                         where stranger.externalId = message.awaitedParentId
                                           and stranger.kind = message.awaitedParentKind
                                           and message.awaitedParentKind <> POLICY))))
               and not exists (select earlier.messageId
                                 from InboundMessage earlier
                                where earlier.stream = message.stream
                                  and earlier.status = RETRY
                                  and earlier.nextRetryAt > :now
                                  and earlier.receivedAt < message.receivedAt)
             order by message.receivedAt
            """)
    List<InboundMessage> findDueForImport(@Param("now") Instant now, Limit limit);

    List<InboundMessage> findByCorrelationId(UUID correlationId);

    @Modifying
    @Query(nativeQuery = true, value = """
            update inbound_message
               set status = 'NEW', next_retry_at = null
             where status = 'AWAITING_PARENT'
               and awaited_parent_kind = :kind
               and awaited_parent_id = :parent
               and (correlation_id is null or batch_count is not null)
            """)
    int releaseAwaitingParent(@Param("kind") String kind, @Param("parent") UUID parent);

    @Query(nativeQuery = true, value = """
            select payload -> 'data' ->> 'keyValue'
              from inbound_message
             where correlation_id = :cid
               and status = 'BUFFERED'
             order by batch_count nulls last, received_at
             limit 1
            """)
    Optional<String> findBufferedTransferService(@Param("cid") UUID cid);

    @Query(nativeQuery = true, value = """
            select payload -> 'data' ->> 'keyValue'
              from inbound_message
             where correlation_id = :cid
               and status = 'BUFFERED'
            """)
    List<String> findBufferedTransferServices(@Param("cid") UUID cid);

    boolean existsByCorrelationIdAndStatusIn(UUID correlationId, Set<InboundStatus> statuses);

    default List<BufferedTransfer> findTransfersBufferedBefore(
            OneCStream stream, Instant receivedBefore, Limit limit) {
        return findTransfersInStatusBefore(stream, InboundStatus.BUFFERED, receivedBefore, limit);
    }

    @Query("""
            select message.correlationId as correlationId,
                   min(message.receivedAt) as firstPartReceivedAt,
                   count(message) as parts
              from InboundMessage message
             where message.stream = :stream
               and message.status = :status
             group by message.correlationId
            having min(message.receivedAt) < :receivedBefore
             order by min(message.receivedAt)
            """)
    List<BufferedTransfer> findTransfersInStatusBefore(
            @Param("stream") OneCStream stream,
            @Param("status") InboundStatus status,
            @Param("receivedBefore") Instant receivedBefore,
            Limit limit);

    @Query("""
            select message.stream as stream,
                   message.status as status,
                   count(message) as messages,
                   min(message.receivedAt) as oldestReceivedAt
              from InboundMessage message
             where message.status in (NEW, RETRY, BUFFERED, AWAITING_PARENT, FAILED)
             group by message.stream, message.status
            """)
    List<StatusBacklog> countUnfinishedByStreamAndStatus();

    @Query("""
            select count(distinct message.correlationId)
              from InboundMessage message
             where message.stream = :stream
               and message.status = BUFFERED
               and message.receivedAt < :receivedBefore
            """)
    long countTransfersBufferedBefore(
            @Param("stream") OneCStream stream, @Param("receivedBefore") Instant receivedBefore);

    @Query(nativeQuery = true, value = """
            select count(*)
              from inbound_message
             where payload is not null
               and (stream in ('POLICIES', 'INSURANCE_CONTRACTS', 'INSURED_ASSOCIATIONS') or status = 'SKIPPED'
                    or (status = 'FAILED' and parent_wait_since is not null))
               and received_at < :receivedBefore
            """)
    long countPayloadsToForgetReceivedBefore(@Param("receivedBefore") Instant receivedBefore);

    @Modifying
    @Query(nativeQuery = true, value = """
            update inbound_message
               set payload = null,
                   last_error = case when stream = 'POLICIES' then null else last_error end
             where payload is not null
               and (stream in ('POLICIES', 'INSURANCE_CONTRACTS', 'INSURED_ASSOCIATIONS') or status = 'SKIPPED'
                    or (status = 'FAILED' and parent_wait_since is not null))
               and message_id in (select message_id
                                    from inbound_message
                                   where payload is not null
                                     and (stream in ('POLICIES', 'INSURANCE_CONTRACTS', 'INSURED_ASSOCIATIONS')
                                          or status = 'SKIPPED'
                                          or (status = 'FAILED' and parent_wait_since is not null))
                                     and received_at < :receivedBefore
                                   order by received_at
                                   limit :limit)
            """)
    int forgetPayloadsReceivedBefore(@Param("receivedBefore") Instant receivedBefore, @Param("limit") int limit);
}
