package ru.projectx.datareference.entity.inbound;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import ru.projectx.datareference.entity.policy.InsuredAssociationFilter;

@Entity
@Table(name = "insured_association_finalization")
public class InsuredAssociationFinalization {

    @Id
    @Column(name = "final_message_id", nullable = false, updatable = false)
    private UUID finalMessageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "final_message_id", insertable = false, updatable = false)
    private InboundMessage finalMessage;

    @Nullable
    @Column(name = "valid_from", updatable = false)
    private LocalDate validFrom;

    @Nullable
    @Column(name = "insured_policy_external_id", updatable = false)
    private UUID insuredPolicyExternalId;

    @Nullable
    @Column(name = "related_policy_external_id", updatable = false)
    private UUID relatedPolicyExternalId;

    @Nullable
    @Column(name = "relation_type_external_id", updatable = false)
    private UUID relationTypeExternalId;

    protected InsuredAssociationFinalization() {
    }

    private InsuredAssociationFinalization(InboundMessage finalMessage, InsuredAssociationFilter filter) {
        this.finalMessageId = finalMessage.receipt().messageId();
        this.finalMessage = finalMessage;
        this.validFrom = filter.validFrom();
        this.insuredPolicyExternalId = filter.insuredPolicy();
        this.relatedPolicyExternalId = filter.relatedPolicy();
        this.relationTypeExternalId = filter.relationType();
    }

    public static InsuredAssociationFinalization of(InboundMessage finalMessage, InsuredAssociationFilter filter) {
        return new InsuredAssociationFinalization(finalMessage, filter);
    }

    public InboundMessage finalMessage() {
        return finalMessage;
    }
}
