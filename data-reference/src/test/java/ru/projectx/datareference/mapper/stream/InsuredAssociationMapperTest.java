package ru.projectx.datareference.mapper.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ru.projectx.datareference.support.InsuredAssociationData.OTHER_RELATION_TYPE;
import static ru.projectx.datareference.support.InsuredAssociationData.INSURED;
import static ru.projectx.datareference.support.InsuredAssociationData.OTHER_RELATED;
import static ru.projectx.datareference.support.InsuredAssociationData.RELATED;
import static ru.projectx.datareference.support.InsuredAssociationData.RELATION_TYPE;
import static ru.projectx.datareference.support.InsuredAssociationData.byInsured;
import static ru.projectx.datareference.support.InsuredAssociationData.row;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.projectx.datareference.entity.inbound.AwaitedParent;
import ru.projectx.datareference.entity.inbound.ParentKind;
import ru.projectx.datareference.entity.policy.InsuredAssociation;
import ru.projectx.datareference.entity.policy.InsuredAssociationFilter;
import ru.projectx.datareference.exception.OneCFieldException;
import ru.projectx.datareference.mapper.ImportResult;
import ru.projectx.datareference.repository.policy.InsuredAssociationRepository;
import ru.projectx.datareference.repository.policy.PolicyRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class InsuredAssociationMapperTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final InsuredAssociationFilter BY_INSURED = new InsuredAssociationFilter(null, INSURED, null, null);
    private static final LocalDate START = LocalDate.of(2026, 11, 13);

    @Mock
    private InsuredAssociationRepository associations;

    @Mock
    private PolicyRepository policies;

    @Test
    void rowsWithKnownPolicies_replaceTheAssociationsCoveredByTheFilter() {
        when(policies.existsByExternalId(any())).thenReturn(true);

        ImportResult result = mapper().apply(BY_INSURED, List.of(
                json(row(1, INSURED, RELATED, RELATION_TYPE, "2026-11-13T00:00:00+03:00", "3000-01-01")),
                json(row(2, INSURED, OTHER_RELATED, OTHER_RELATION_TYPE, "2026-11-13", "2027-03-31"))));

        assertThat(result).isEqualTo(ImportResult.applied());
        InOrder replacement = inOrder(associations);
        replacement.verify(associations).deleteCoveredBy(BY_INSURED);
        ArgumentCaptor<List<InsuredAssociation>> saved = ArgumentCaptor.captor();
        replacement.verify(associations).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(InsuredAssociation::row).containsExactly(
                new InsuredAssociation.Row(INSURED, RELATED, RELATION_TYPE, START, null),
                new InsuredAssociation.Row(INSURED, OTHER_RELATED, OTHER_RELATION_TYPE, START, LocalDate.of(2027, 3, 31)));
    }

    @Test
    void emptyTransfer_removesTheAssociationsCoveredByTheFilter_withoutWaitingForPolicies() {
        ImportResult result = mapper().apply(BY_INSURED, List.of());

        assertThat(result).isEqualTo(ImportResult.applied());
        verify(associations).deleteCoveredBy(BY_INSURED);
        verify(associations).saveAll(List.of());
    }

    @Test
    void missingPolicyOfTheInsured_isAwaitedFirst_andNothingIsReplaced() {
        when(policies.existsByExternalId(INSURED)).thenReturn(false);

        ImportResult result = mapper().apply(BY_INSURED, List.of(json(row(1, INSURED, RELATED, RELATION_TYPE, "2026-11-13",
                "3000-01-01"))));

        assertThat(result).isEqualTo(ImportResult.awaitingParent(new AwaitedParent(ParentKind.POLICY, INSURED)));
        verify(policies, never()).existsByExternalId(RELATED);
        verify(associations, never()).deleteCoveredBy(any());
    }

    @Test
    void missingPolicyOfTheRelated_isAwaited_whenTheInsuredIsKnown() {
        when(policies.existsByExternalId(INSURED)).thenReturn(true);
        when(policies.existsByExternalId(RELATED)).thenReturn(false);

        ImportResult result = mapper().apply(BY_INSURED, List.of(json(row(1, INSURED, RELATED, RELATION_TYPE, "2026-11-13",
                "3000-01-01"))));

        assertThat(result).isEqualTo(ImportResult.awaitingParent(new AwaitedParent(ParentKind.POLICY, RELATED)));
        verify(associations, never()).saveAll(any());
    }

    @Test
    void rowOutsideTheFilter_isRejectedBeforeAnyChange() {
        InsuredAssociationFilter byRelated = new InsuredAssociationFilter(null, INSURED, OTHER_RELATED, null);

        assertThatThrownBy(() -> mapper().apply(byRelated, List.of(json(row(1, INSURED, RELATED, RELATION_TYPE, "2026-11-13",
                "3000-01-01")))))
                .isInstanceOf(OneCFieldException.class);
        verify(associations, never()).deleteCoveredBy(any());
    }

    @Test
    void checkRow_rejectsABrokenRow() {
        JsonNode broken = json(row(1, INSURED, RELATED, RELATION_TYPE, "2026-11-13", "3000-01-01")
                .replace(RELATION_TYPE.toString(), "1-2-3-4-5"));

        assertThatThrownBy(() -> mapper().checkRow(broken))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("поле relationType не является GUID");
    }

    @Test
    void filterOf_readsTheFilterOfThePart() {
        JsonNode part = json("{\"filter\":%s}".formatted(byInsured(INSURED)));

        assertThat(mapper().filterOf(part)).isEqualTo(BY_INSURED);
    }

    private InsuredAssociationMapper mapper() {
        return new InsuredAssociationMapper(associations, policies,
                Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC));
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }
}
