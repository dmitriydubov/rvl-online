package ru.projectx.datareference.mapper.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.projectx.datareference.support.InsuredAssociationData.INSURED;
import static ru.projectx.datareference.support.InsuredAssociationData.OTHER_RELATED;
import static ru.projectx.datareference.support.InsuredAssociationData.RELATED;
import static ru.projectx.datareference.support.InsuredAssociationData.RELATION_TYPE;
import static ru.projectx.datareference.support.InsuredAssociationData.row;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.projectx.datareference.entity.policy.InsuredAssociation;
import ru.projectx.datareference.entity.policy.InsuredAssociationFilter;
import ru.projectx.datareference.exception.OneCFieldException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class InsuredAssociationRowsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate START = LocalDate.of(2026, 11, 13);

    @Test
    void row_readsBothPoliciesTypeAndCalendarDaysAsWritten() {
        JsonNode row = JSON.readTree(row(1, INSURED, RELATED, RELATION_TYPE, "2026-11-13T00:00:00+03:00",
                "2027-03-31T00:00:00+03:00"));

        assertThat(InsuredAssociationRows.rowOf(row)).isEqualTo(
                new InsuredAssociation.Row(INSURED, RELATED, RELATION_TYPE, START, LocalDate.of(2027, 3, 31)));
    }

    @Test
    void openEnd_meansNoEndDate() {
        JsonNode row = JSON.readTree(row(1, INSURED, RELATED, RELATION_TYPE, "2026-11-13", "3000-01-01T00:00:00+03:00"));

        assertThat(InsuredAssociationRows.rowOf(row).validTo()).isNull();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            "idInsured":"11111111-2222-4333-8444-555555555501"   | "idInsured":"1-2-3-4-5"                             | поле idInsured не является GUID
            "value":"11111111-2222-4333-8444-555555555502"       | "value":"1-2-3-4-5"                                 | поле otherSide.value не является GUID
            "relationType":"797093be-2c51-11e8-8123-005056b43f21" | "relationType":"00000000-0000-0000-0000-000000000000" | нет обязательного GUID в поле relationType
            "period":"2026-11-13"                                 | "period":"0001-01-01"                               | нет обязательной даты в поле period
            """)
    void brokenRequiredValue_rejectsTheRow_namingTheFieldOnly(String good, String broken, String reason) {
        String text = row(1, INSURED, RELATED, RELATION_TYPE, "2026-11-13", "3000-01-01").replace(good, broken);

        assertThatThrownBy(() -> InsuredAssociationRows.rowOf(JSON.readTree(text)))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage(reason);
    }

    @Test
    void rowsWithinTheFilterAndDistinct_areConsistent() {
        List<InsuredAssociation.Row> rows = List.of(association(RELATED, START), association(RELATED, START.plusDays(1)),
                association(OTHER_RELATED, START));

        assertThatCode(() -> InsuredAssociationRows.requireConsistent(byInsured(), rows)).doesNotThrowAnyException();
    }

    @Test
    void rowOutsideTheFilter_rejectsTheTransfer() {
        InsuredAssociationFilter byRelated = new InsuredAssociationFilter(null, INSURED, RELATED, null);
        List<InsuredAssociation.Row> rows = List.of(association(RELATED, START), association(OTHER_RELATED, START));

        assertThatThrownBy(() -> InsuredAssociationRows.requireConsistent(byRelated, rows))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("строка связи вне отбора передачи");
    }

    @Test
    void twoRowsWithTheSameDimensions_rejectTheTransfer() {
        InsuredAssociation.Row open = association(RELATED, START);
        InsuredAssociation.Row closed = new InsuredAssociation.Row(INSURED, RELATED, RELATION_TYPE, START, START.plusDays(9));

        assertThatThrownBy(() -> InsuredAssociationRows.requireConsistent(byInsured(), List.of(open, closed)))
                .isInstanceOf(OneCFieldException.class)
                .hasMessage("в передаче две строки связи с одинаковыми измерениями");
    }

    private static InsuredAssociationFilter byInsured() {
        return new InsuredAssociationFilter(null, INSURED, null, null);
    }

    private static InsuredAssociation.Row association(UUID related, LocalDate start) {
        return new InsuredAssociation.Row(INSURED, related, RELATION_TYPE, start, null);
    }
}
