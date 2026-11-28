package ru.projectx.contracts.acceptance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class CertificateRecords {

    private static final String CERTIFICATES = """
            select id, attachment_id, doc_type::text as doc_type, content_type, file_size, file_hash, file_key,
                   created_at, deleted_at, deleted_request_id
              from contract_documents
             where individual_request_id = :request and participant_id = :participant
             order by created_at, id
            """;
    private static final String CONSENTS = """
            select id, attachment_id, doc_type::text as doc_type, content_type, file_size, file_hash, file_key,
                   created_at, deleted_at, deleted_request_id
              from contract_documents
             where policy_id = :policy and family_member_id = :familyMember
             order by created_at, id
            """;
    private static final String BY_ATTACHMENT = """
            select id, attachment_id, doc_type::text as doc_type, content_type, file_size, file_hash, file_key,
                   created_at, deleted_at, deleted_request_id
              from contract_documents
             where attachment_id = :attachment
            """;

    private static final String REKEY = """
            update contract_documents set file_key = :key where attachment_id = :attachment
            """;
    private static final String MARK_DELETED = """
            update contract_documents
               set deleted_at = :deletedAt, deleted_request_id = :requestId
             where attachment_id = :attachment
            """;

    private final JdbcClient jdbc;

    public CertificateRecords(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Certificate(UUID id, UUID attachment, String docType, String contentType, long size, String hash, String key,
            Instant createdAt, @Nullable Instant deletedAt, @Nullable String deletedRequestId) {
    }

    List<Certificate> of(UUID request, UUID participant) {
        return jdbc.sql(CERTIFICATES).param("request", request).param("participant", participant)
                .query(this::certificate).list();
    }

    List<Certificate> ofConsent(UUID policy, UUID familyMember) {
        return jdbc.sql(CONSENTS).param("policy", policy).param("familyMember", familyMember)
                .query(this::certificate).list();
    }

    Certificate byAttachment(String attachment) {
        return jdbc.sql(BY_ATTACHMENT).param("attachment", UUID.fromString(attachment)).query(this::certificate)
                .optional()
                .orElseThrow(() -> new AssertionError("в реестре документов нет вложения " + attachment));
    }

    void rekey(String attachment, String key) {
        int updated = jdbc.sql(REKEY).param("key", key).param("attachment", UUID.fromString(attachment)).update();
        if (updated != 1) {
            throw new AssertionError("в реестре документов нет вложения " + attachment);
        }
    }

    void markDeleted(String attachment, Instant deletedAt, String requestId) {
        int updated = jdbc.sql(MARK_DELETED)
                .param("deletedAt", OffsetDateTime.ofInstant(deletedAt, ZoneOffset.UTC))
                .param("requestId", requestId)
                .param("attachment", UUID.fromString(attachment))
                .update();
        if (updated != 1) {
            throw new AssertionError("в реестре документов нет вложения " + attachment);
        }
    }

    private Certificate certificate(ResultSet row, int index) throws SQLException {
        OffsetDateTime deletedAt = row.getObject("deleted_at", OffsetDateTime.class);
        return new Certificate(row.getObject("id", UUID.class), row.getObject("attachment_id", UUID.class),
                row.getString("doc_type"), row.getString("content_type"), row.getLong("file_size"),
                row.getString("file_hash"), row.getString("file_key"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                deletedAt == null ? null : deletedAt.toInstant(),
                row.getString("deleted_request_id"));
    }
}
