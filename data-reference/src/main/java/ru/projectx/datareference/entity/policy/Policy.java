package ru.projectx.datareference.entity.policy;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import ru.projectx.datareference.entity.catalog.OneCEntity;

@Entity
@Table(name = "policies")
public class Policy extends OneCEntity {

    @Column(name = "external_contract_id", nullable = false)
    private UUID externalContractId;

    @Column(nullable = false)
    private String number;

    @Nullable
    @Column(name = "last_name")
    private String lastName;

    @Nullable
    @Column(name = "first_name")
    private String firstName;

    @Nullable
    @Column(name = "middle_name")
    private String middleName;

    @Nullable
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Nullable
    private String gender;

    @Nullable
    private String email;

    @Column(name = "begin_date", nullable = false)
    private LocalDate beginDate;

    @Nullable
    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(nullable = false)
    private boolean availability;

    @Nullable
    @Column(name = "archived_at")
    private Instant archivedAt;

    @Nullable
    @Column(name = "personal_data_erased_at")
    private Instant personalDataErasedAt;

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PolicyProgram> programs = new ArrayList<>();

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PolicyServiceLine> serviceLines = new ArrayList<>();

    protected Policy() {
    }

    private Policy(UUID externalId, Instant now) {
        super(externalId, now);
    }

    public static Policy create(UUID externalId, Snapshot snapshot, Instant now) {
        Policy policy = new Policy(externalId, now);
        policy.applySnapshot(snapshot, now);
        return policy;
    }

    public void applySnapshot(Snapshot snapshot, Instant now) {
        Details details = snapshot.details();
        this.externalContractId = details.externalContractId();
        this.number = details.number();
        this.lastName = details.lastName();
        this.firstName = details.firstName();
        this.middleName = details.middleName();
        this.dateOfBirth = details.dateOfBirth();
        this.gender = details.gender();
        this.email = details.email();
        this.beginDate = details.beginDate();
        this.endDate = details.endDate();
        this.availability = details.availability();
        this.archivedAt = archivedSince(details.deleteMark(), now);
        this.personalDataErasedAt = null;
        replaceTableParts(snapshot, now);
        applyCommon(details.deleteMark(), now);
    }

    public void erasePersonalDataBeyond(PersonalDataCutoff cutoff, Instant now) {
        if (endedBefore(cutoff.endedBefore()) || archivedBy(cutoff.archivedBy())) {
            this.lastName = null;
            this.firstName = null;
            this.middleName = null;
            this.dateOfBirth = null;
            this.email = null;
            this.personalDataErasedAt = now;
        }
    }

    public boolean isActiveOn(LocalDate day) {
        return !isArchive() && covers(day);
    }

    private boolean covers(LocalDate day) {
        return !beginDate.isAfter(day) && !endedBefore(day);
    }

    public boolean personalDataErased() {
        return personalDataErasedAt != null;
    }

    private @Nullable Instant archivedSince(boolean deleteMark, Instant now) {
        if (!deleteMark) {
            return null;
        }
        return archivedAt == null ? now : archivedAt;
    }

    private boolean endedBefore(LocalDate day) {
        return endDate != null && endDate.isBefore(day);
    }

    private boolean archivedBy(Instant moment) {
        return archivedAt != null && !archivedAt.isAfter(moment);
    }

    private void replaceTableParts(Snapshot snapshot, Instant now) {
        programs.clear();
        for (PolicyProgram.Row row : snapshot.programs()) {
            programs.add(PolicyProgram.of(this, row, now));
        }
        serviceLines.clear();
        for (PolicyServiceLine.Row row : snapshot.serviceLines()) {
            serviceLines.add(PolicyServiceLine.of(this, row, now));
        }
    }

    public record Snapshot(
            Details details,
            List<PolicyProgram.Row> programs,
            List<PolicyServiceLine.Row> serviceLines) {

        public Snapshot {
            programs = List.copyOf(programs);
            serviceLines = List.copyOf(serviceLines);
        }
    }

    public record Details(
            UUID externalContractId,
            String number,
            @Nullable String lastName,
            @Nullable String firstName,
            @Nullable String middleName,
            @Nullable LocalDate dateOfBirth,
            @Nullable String gender,
            @Nullable String email,
            LocalDate beginDate,
            @Nullable LocalDate endDate,
            boolean availability,
            boolean deleteMark) {

        @Override
        public String toString() {
            return "Details[externalContractId=" + externalContractId + "]";
        }
    }

    public Details details() {
        return new Details(externalContractId, number, lastName, firstName, middleName, dateOfBirth, gender, email,
                beginDate, endDate, availability, isArchive());
    }

    public List<PolicyProgram> programs() {
        return Collections.unmodifiableList(programs);
    }

    public List<PolicyServiceLine> serviceLines() {
        return Collections.unmodifiableList(serviceLines);
    }
}
