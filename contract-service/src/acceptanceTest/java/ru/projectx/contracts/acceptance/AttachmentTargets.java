package ru.projectx.contracts.acceptance;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class AttachmentTargets {

    private static final String REQUEST = "61a7982c-a0b4-4bbf-9527-60b40b752803";
    private static final String KM_PARTICIPANT = "9275471c-d2fe-484f-97a6-d6d7b1267b5e";
    private static final String LM_PARTICIPANT = "6778e8c2-985a-4d54-9bd3-0f6403b104d6";
    private static final String UNKNOWN_ATTACHMENT = "0f4e2a9c-6b18-4c73-a5d0-9e2b7f1c3a66";
    private static final String DELETED_CERTIFICATE = "3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e21";
    private static final String DELETED_CONSENT = "4d3a9f21-6b8c-4e32-8a7f-2c1b8d5e9f32";

    private static final Map<String, String> PATH_DEFECTS = Map.of(
            "не GUID", "not-a-guid",
            "короткая строка", "3c2f8e10",
            "GUID с лишним символом", "3c2f8e10-5a7b-4d21-9f6e-1b0a7c4d8e210");

    private final Map<String, Supplier<String>> contentTargets;
    private final Map<String, Supplier<String>> deletionTargets;
    private final IssuanceRecords records;
    private final PrintedFormRecords forms;

    public AttachmentTargets(IssuanceRecords records, PrintedFormRecords forms) {
        this.records = records;
        this.forms = forms;
        this.deletionTargets = Map.of(
                "неизвестное вложение", () -> UNKNOWN_ATTACHMENT,
                "печатная форма договора КМ", this::kmContractFormId,
                "печатная форма полиса ЛМ", this::lmPolicyFormId);
        this.contentTargets = Map.of(
                "неизвестное вложение", () -> UNKNOWN_ATTACHMENT,
                "печатная форма договора КМ", this::kmContractFormId,
                "печатная форма полиса ЛМ", this::lmPolicyFormId,
                "удалённая справка 070/у", () -> DELETED_CERTIFICATE,
                "удалённое согласие на обработку ПДн", () -> DELETED_CONSENT);
    }

    String contentTarget(String target) {
        return Optional.ofNullable(contentTargets.get(target))
                .orElseThrow(() -> new AssertionError("цели чтения «" + target + "» нет в перечне шага"))
                .get();
    }

    String deletionTarget(String target) {
        return Optional.ofNullable(deletionTargets.get(target))
                .orElseThrow(() -> new AssertionError("цели удаления «" + target + "» нет в перечне шага"))
                .get();
    }

    String defectSegment(String defect) {
        return Optional.ofNullable(PATH_DEFECTS.get(defect))
                .orElseThrow(() -> new AssertionError("дефекта пути «" + defect + "» нет в перечне шага"));
    }

    private String kmContractFormId() {
        return firstFormId(records.latestContractId(KM_PARTICIPANT, REQUEST));
    }

    private String lmPolicyFormId() {
        return firstFormId(records.latestLmPolicyId(LM_PARTICIPANT, REQUEST));
    }

    private String firstFormId(UUID owner) {
        return forms.of(owner).stream()
                .map(form -> form.id().toString())
                .findFirst()
                .orElseThrow(() -> new AssertionError("у записи " + owner + " нет печатных форм"));
    }
}
