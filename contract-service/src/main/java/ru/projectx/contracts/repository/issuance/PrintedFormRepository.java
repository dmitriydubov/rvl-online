package ru.projectx.contracts.repository.issuance;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.projectx.contracts.entity.document.PrintedForm;
import ru.projectx.contracts.entity.document.PrintedFormType;

public interface PrintedFormRepository extends JpaRepository<PrintedForm, UUID> {

    List<PrintedForm> findByLmPolicyIdInOrContractIdInOrderByCreatedAtAscIdAsc(Collection<UUID> lmPolicyIds,
            Collection<UUID> contractIds);

    Optional<PrintedForm> findByAttachmentAttachmentId(UUID attachmentId);

    boolean existsByAttachmentAttachmentIdAndAttachmentDeletedAtIsNull(UUID attachmentId);

    List<PrintedForm> findByAttachmentIndividualRequestIdAndAttachmentParticipantIdAndDocTypeAndAttachmentDeletedAtIsNullOrderByCreatedAtAscIdAsc(
            UUID individualRequestId, UUID participantId, PrintedFormType docType);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            update contract_documents
               set deleted_at = :deletedAt,
                   deleted_request_id = :deletedRequestId
             where attachment_id = :attachmentId
               and deleted_at is null
            """, nativeQuery = true)
    int markDeletedByAttachmentId(@Param("attachmentId") UUID attachmentId,
            @Param("deletedAt") Instant deletedAt,
            @Param("deletedRequestId") String deletedRequestId);
}
