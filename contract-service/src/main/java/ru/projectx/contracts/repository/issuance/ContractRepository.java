package ru.projectx.contracts.repository.issuance;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.projectx.contracts.entity.contract.Contract;
import ru.projectx.contracts.entity.contract.ContractStatus;
import ru.projectx.contracts.repository.IssuanceRepositoryLocking;
import ru.projectx.contracts.repository.UskRecordRepository;

public interface ContractRepository extends JpaRepository<Contract, UUID>, IssuanceRepositoryLocking<Contract>,
        UskRecordRepository<Contract, ContractStatus> {

    List<Contract> findByRequestIndividualRequestIdAndStatusNotIn(UUID individualRequestId,
            Collection<ContractStatus> statuses);

    @Query("""
            select c from Contract c
             where c.request.individualRequestId = :individualRequestId
               and c.status <> :cancelled
               and (c.status <> :error or c.paidAt is not null)
            """)
    List<Contract> findRunningOrFailedAfterPayment(@Param("individualRequestId") UUID individualRequestId,
            @Param("error") ContractStatus error, @Param("cancelled") ContractStatus cancelled);

    List<Contract> findByRequestIndividualRequestId(UUID individualRequestId);

    List<Contract> findByRequestIndividualRequestIdAndInsuredParticipantId(UUID individualRequestId,
            UUID insuredParticipantId);
}
