package ru.projectx.datareference.repository.policy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.projectx.datareference.entity.policy.Policy;
import ru.projectx.datareference.repository.ReferenceId;

@Repository
public interface PolicyRepository extends JpaRepository<Policy, UUID>, PolicySearchRepository, ActivePolicyProgramsRepository {

    Optional<Policy> findByExternalId(UUID externalId);

    boolean existsByExternalId(UUID externalId);

    List<Policy> findAllByExternalIdIn(Collection<UUID> externalIds);

    List<Policy> findAllByNumberAndExternalContractId(String number, UUID externalContractId);

    List<Policy> findAllByNumber(String number);

    @Query(nativeQuery = true, value = """
            select count(*)
              from policies
             where personal_data_erased_at is null
               and (end_date < :endedBefore or archived_at <= :archivedBy)
            """)
    long countPersonalDataBeyond(@Param("endedBefore") LocalDate endedBefore, @Param("archivedBy") Instant archivedBy);

    @Modifying
    @Query(nativeQuery = true, value = """
            update policies
               set last_name = null, first_name = null, middle_name = null, date_of_birth = null, email = null,
                   personal_data_erased_at = :now
             where personal_data_erased_at is null
               and (end_date < :endedBefore or archived_at <= :archivedBy)
               and id in (select id
                            from policies
                           where personal_data_erased_at is null
                             and (end_date < :endedBefore or archived_at <= :archivedBy)
                           limit :limit)
            """)
    int erasePersonalDataBeyond(@Param("endedBefore") LocalDate endedBefore, @Param("archivedBy") Instant archivedBy,
                                @Param("now") Instant now, @Param("limit") int limit);

    List<ReferenceId> findByArchiveFalseOrderByIdAsc(Limit limit);
}
