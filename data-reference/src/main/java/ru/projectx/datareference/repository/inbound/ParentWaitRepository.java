package ru.projectx.datareference.repository.inbound;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.projectx.datareference.entity.inbound.InboundMessage;
import ru.projectx.datareference.entity.inbound.OneCStream;

@Repository
public interface ParentWaitRepository extends org.springframework.data.repository.Repository<InboundMessage, UUID> {

    @Query("""
            select message
              from InboundMessage message
             where message.status = AWAITING_PARENT
               and message.parentWaitSince <= :deadline
               and (message.correlationId is null or message.batchCount is not null)
               and not exists (select lpu.id
                                 from Lpu lpu
                                where message.awaitedParentKind = CONTRAGENT
                                  and lpu.externalId = message.awaitedParentId)
               and not exists (select contract.id
                                 from LpuContract contract
                                where message.awaitedParentKind = LPU_CONTRACT
                                  and contract.externalId = message.awaitedParentId)
               and not exists (select service.id
                                 from ContragentService service
                                where message.awaitedParentKind = CONTRAGENT_SERVICE
                                  and service.externalId = message.awaitedParentId)
               and not exists (select stranger.externalId
                                 from ForeignReference stranger
                                where stranger.externalId = message.awaitedParentId
                                  and stranger.kind = message.awaitedParentKind
                                  and message.awaitedParentKind <> POLICY)
             order by message.receivedAt
            """)
    List<InboundMessage> findExpiredUnresolved(@Param("deadline") Instant deadline, Limit limit);

    @Query("""
            select message
              from InboundMessage message
             where message.status = AWAITING_PARENT
               and message.stream = :stream
               and message.recordExternalId = :record
               and message.receivedAt < :receivedBefore
            """)
    List<InboundMessage> findAwaitingOfRecordBefore(
            @Param("stream") OneCStream stream,
            @Param("record") UUID record,
            @Param("receivedBefore") Instant receivedBefore);
}
