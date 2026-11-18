package ru.projectx.datareference.entity.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "insured_associations")
public class InsuredAssociation {

    @Id
    private UUID id;

    @Column(name = "insured_policy_external_id", nullable = false, updatable = false)
    private UUID insuredPolicyExternalId;

    @Column(name = "related_policy_external_id", nullable = false, updatable = false)
    private UUID relatedPolicyExternalId;

    @Column(name = "relation_type_external_id", nullable = false, updatable = false)
    private UUID relationTypeExternalId;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private LocalDate validFrom;

    @Nullable
    @Column(name = "valid_to", updatable = false)
    private LocalDate validTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected InsuredAssociation() {
    }

    private InsuredAssociation(Row row, Instant now) {
        this.id = UUID.randomUUID();
        this.insuredPolicyExternalId = row.insuredPolicy();
        this.relatedPolicyExternalId = row.relatedPolicy();
        this.relationTypeExternalId = row.relationType();
        this.validFrom = row.validFrom();
        this.validTo = row.validTo();
        this.createdAt = now;
    }

    public static InsuredAssociation of(Row row, Instant now) {
        return new InsuredAssociation(row, now);
    }

    public Row row() {
        return new Row(insuredPolicyExternalId, relatedPolicyExternalId, relationTypeExternalId, validFrom, validTo);
    }

    public record Row(
            UUID insuredPolicy,
            UUID relatedPolicy,
            UUID relationType,
            LocalDate validFrom,
            @Nullable LocalDate validTo) {

        public List<UUID> policies() {
            return List.of(insuredPolicy, relatedPolicy);
        }

        public boolean validOn(LocalDate day) {
            return !validFrom.isAfter(day) && (validTo == null || !validTo.isBefore(day));
        }

        public InsuredAssociationFilter dimensions() {
            return new InsuredAssociationFilter(validFrom, insuredPolicy, relatedPolicy, relationType);
        }
    }
}
